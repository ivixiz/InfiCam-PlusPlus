#include "SpatialCalibrationEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <jni.h>
#include <new>

using CalibrationClock = std::chrono::steady_clock;

bool SpatialCalibrationEngineNative::configure(const int pixel_count){
	if(pixel_count <= 0){
		last_error = "Invalid thermal frame dimensions.";
		return false;
	}
	pixels = pixel_count;
	active_map.clear();
	residual_mean.resize(pixels);
	residual_m2.resize(pixels);
	sample_count.resize(pixels);
	median_scratch.resize(pixels);
	candidate_map.resize(pixels);
	cancel();
	last_error.clear();
	return true;
}

bool SpatialCalibrationEngineNative::set_active_map(const float *offsets,
		const int pixel_count){
	if(offsets == nullptr || pixel_count != pixels){
		last_error = "Calibration profile dimensions do not match the camera.";
		return false;
	}
	for(int i = 0; i < pixels; ++i){
		if(!std::isfinite(offsets[i])){
			last_error = "Calibration profile contains an invalid value.";
			return false;
		}
	}
	active_map.assign(offsets, offsets + pixels);
	last_error.clear();
	return true;
}

void SpatialCalibrationEngineNative::clear_active_map(){
	active_map.clear();
}

void SpatialCalibrationEngineNative::begin(){
	if(pixels <= 0){
		last_error = "Calibration engine is not configured.";
		return;
	}
	std::fill(residual_mean.begin(), residual_mean.end(), 0.0f);
	std::fill(residual_m2.begin(), residual_m2.end(), 0.0f);
	std::fill(sample_count.begin(), sample_count.end(), 0u);
	std::fill(candidate_map.begin(), candidate_map.end(), 0.0f);
	accepted_frames = 0;
	rejected_frames = 0;
	invalid_fraction = 1.0f;
	candidate_rms = 0.0f;
	processed_calls = 0;
	total_processing_us = 0.0;
	max_processing_us = 0.0;
	last_error.clear();
	collecting = true;
}

void SpatialCalibrationEngineNative::cancel(){
	collecting = false;
	accepted_frames = 0;
	rejected_frames = 0;
	last_error.clear();
}

bool SpatialCalibrationEngineNative::process(float *temperatures,
		const int pixel_count, const bool collect){
	if(temperatures == nullptr || pixel_count != pixels){
		last_error = "Thermal frame dimensions changed during calibration.";
		return false;
	}
	/* Normal operation without a committed profile is a true no-op: no clock
	 * reads, copies or per-pixel pass are added to the camera callback. */
	if((!collect || !collecting) && active_map.empty()){
		return true;
	}
	const auto started = CalibrationClock::now();

	if(collect && collecting){
		int finite_count = 0;
		for(int i = 0; i < pixels; ++i){
			if(std::isfinite(temperatures[i])){
				median_scratch[finite_count++] = temperatures[i];
			}
		}
		if(finite_count < static_cast<int>(pixels * MIN_FINITE_FRAME_FRACTION)){
			rejected_frames++;
		} else {
			/* Exact order-statistic median over all finite thermal pixels. nth_element
			 * is linear on average and operates on a permanently allocated scratch
			 * buffer, so no frame is retained and the hot path allocates nothing. */
			float *middle = median_scratch.data() + finite_count / 2;
			std::nth_element(median_scratch.data(), middle,
					median_scratch.data() + finite_count);
			const float spatial_median = *middle;
			for(int i = 0; i < pixels; ++i){
				const float temperature = temperatures[i];
				if(!std::isfinite(temperature)) continue;
				const float residual = temperature - spatial_median;
				uint32_t count = sample_count[i];
				const float delta = residual - residual_mean[i];
				/* Bootstrap with a bounded online location estimator instead of
				 * blindly averaging the first samples. A transient in frame zero can
				 * therefore move the estimate by at most 0.5 C on each following
				 * observation, while a true persistent pixel offset is retained. */
				if(accepted_frames < ROBUST_INITIALIZATION_FRAMES){
					if(count == 0){
						residual_mean[i] = residual;
					} else {
						residual_mean[i] += 0.5f * std::max(-1.0f,
								std::min(1.0f, delta));
					}
					residual_m2[i] = 0.0f;
					sample_count[i] = 1;
					continue;
				}
				if(count == 0){
					residual_mean[i] = residual;
					residual_m2[i] = 0.0f;
					sample_count[i] = 1;
					continue;
				}
				const float variance = residual_m2[i] /
						static_cast<float>(std::max(1u, count - 1));
				const float sigma = std::sqrt(std::max(0.0f, variance));
				/* A 0.25 C floor avoids rejecting quantisation noise. The cap
				 * prevents a later transient from disabling rejection forever. */
				const float threshold = std::min(1.5f,
						std::max(0.25f, 6.0f * sigma));
				const bool accept = std::fabs(delta) <= threshold;
				if(!accept) continue;
				count++;
				sample_count[i] = count;
				residual_mean[i] += delta / static_cast<float>(count);
				const float delta_after = residual - residual_mean[i];
				residual_m2[i] += delta * delta_after;
			}
			accepted_frames++;
		}
	}

	if(active_map.size() == static_cast<size_t>(pixels)){
		for(int i = 0; i < pixels; ++i){
			temperatures[i] -= active_map[i];
		}
	}

	const double elapsed = std::chrono::duration<double, std::micro>(
			CalibrationClock::now() - started).count();
	processed_calls++;
	total_processing_us += elapsed;
	max_processing_us = std::max(max_processing_us, elapsed);
	return true;
}

bool SpatialCalibrationEngineNative::finish(){
	collecting = false;
	if(accepted_frames < MIN_COLLECTED_FRAMES){
		last_error = "Insufficient valid calibration frames.";
		return false;
	}
	const uint64_t estimation_frames = accepted_frames > ROBUST_INITIALIZATION_FRAMES ?
			accepted_frames - ROBUST_INITIALIZATION_FRAMES : 0;
	const uint32_t required_samples = static_cast<uint32_t>(std::max<uint64_t>(
			64, estimation_frames * 7 / 10));
	int invalid_pixels = 0;
	double offset_sum = 0.0;
	int valid_pixels = 0;
	for(int i = 0; i < pixels; ++i){
		if(sample_count[i] < required_samples || !std::isfinite(residual_mean[i])){
			invalid_pixels++;
			candidate_map[i] = active_map.size() == static_cast<size_t>(pixels) ?
					active_map[i] : 0.0f;
			continue;
		}
		candidate_map[i] = residual_mean[i];
		offset_sum += candidate_map[i];
		valid_pixels++;
	}
	invalid_fraction = static_cast<float>(invalid_pixels) /
			static_cast<float>(pixels);
	if(valid_pixels == 0 || invalid_fraction > MAX_INVALID_PIXEL_FRACTION){
		last_error = "Too many unstable or invalid thermal pixels.";
		return false;
	}

	/* A single-temperature procedure may estimate additive offsets only. Remove
	 * the map's common mode so applying it cannot change absolute temperature. */
	const float common_offset = static_cast<float>(offset_sum / valid_pixels);
	double squared_sum = 0.0;
	int extreme_pixels = 0;
	float maximum = 0.0f;
	for(int i = 0; i < pixels; ++i){
		if(sample_count[i] >= required_samples){
			candidate_map[i] -= common_offset;
		}
		const float absolute = std::fabs(candidate_map[i]);
		maximum = std::max(maximum, absolute);
		if(absolute > 10.0f) extreme_pixels++;
		squared_sum += static_cast<double>(candidate_map[i]) * candidate_map[i];
	}
	candidate_rms = static_cast<float>(std::sqrt(squared_sum / pixels));
	if(maximum > 30.0f || extreme_pixels > std::max(1, pixels / 1000)){
		last_error = "The candidate offset map is physically implausible.";
		return false;
	}
	last_error.clear();
	return true;
}

double SpatialCalibrationEngineNative::average_process_us() const {
	return processed_calls == 0 ? 0.0 : total_processing_us / processed_calls;
}

static SpatialCalibrationEngineNative *engine_from(const jlong handle){
	return reinterpret_cast<SpatialCalibrationEngineNative *>(handle);
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeCreate(JNIEnv *, jclass){
	return reinterpret_cast<jlong>(new(std::nothrow) SpatialCalibrationEngineNative());
}

JNIEXPORT void JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeDestroy(JNIEnv *, jclass,
		jlong handle){
	delete engine_from(handle);
}

JNIEXPORT jboolean JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeConfigure(JNIEnv *, jclass,
		jlong handle, jint pixel_count){
	return engine_from(handle)->configure(pixel_count);
}

JNIEXPORT jboolean JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeSetActiveMap(JNIEnv *env, jclass,
		jlong handle, jfloatArray offsets){
	if(offsets == nullptr) return false;
	const jsize length = env->GetArrayLength(offsets);
	jfloat *values = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(offsets, nullptr));
	if(values == nullptr) return false;
	const bool result = engine_from(handle)->set_active_map(values, length);
	env->ReleasePrimitiveArrayCritical(offsets, values, JNI_ABORT);
	return result;
}

JNIEXPORT void JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeClearActiveMap(JNIEnv *, jclass,
		jlong handle){
	engine_from(handle)->clear_active_map();
}

JNIEXPORT void JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeBegin(JNIEnv *, jclass,
		jlong handle){
	engine_from(handle)->begin();
}

JNIEXPORT void JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeCancel(JNIEnv *, jclass,
		jlong handle){
	engine_from(handle)->cancel();
}

JNIEXPORT jboolean JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeProcess(JNIEnv *env, jclass,
		jlong handle, jfloatArray temperatures, jboolean collect){
	if(temperatures == nullptr) return false;
	const jsize length = env->GetArrayLength(temperatures);
	jfloat *values = static_cast<jfloat *>(
			env->GetPrimitiveArrayCritical(temperatures, nullptr));
	if(values == nullptr) return false;
	const bool result = engine_from(handle)->process(values, length, collect);
	env->ReleasePrimitiveArrayCritical(temperatures, values, 0);
	return result;
}

JNIEXPORT jfloatArray JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeFinish(JNIEnv *env, jclass,
		jlong handle){
	SpatialCalibrationEngineNative *engine = engine_from(handle);
	if(!engine->finish()) return nullptr;
	const std::vector<float>& candidate = engine->candidate();
	jfloatArray output = env->NewFloatArray(candidate.size());
	if(output != nullptr){
		env->SetFloatArrayRegion(output, 0, candidate.size(), candidate.data());
	}
	return output;
}

JNIEXPORT jstring JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetError(JNIEnv *env, jclass,
		jlong handle){
	return env->NewStringUTF(engine_from(handle)->error().c_str());
}

JNIEXPORT jlong JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetCollectedFrames(JNIEnv *, jclass,
		jlong handle){
	return static_cast<jlong>(engine_from(handle)->collected_frames());
}

JNIEXPORT jfloat JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetInvalidFraction(JNIEnv *, jclass,
		jlong handle){
	return engine_from(handle)->invalid_pixel_fraction();
}

JNIEXPORT jfloat JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetSpatialRms(JNIEnv *, jclass,
		jlong handle){
	return engine_from(handle)->spatial_rms();
}

JNIEXPORT jdouble JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetAverageProcessUs(JNIEnv *, jclass,
		jlong handle){
	return engine_from(handle)->average_process_us();
}

JNIEXPORT jdouble JNICALL
Java_be_ntmn_libinficam_SpatialCalibrationEngine_nativeGetMaxProcessUs(JNIEnv *, jclass,
		jlong handle){
	return engine_from(handle)->max_process_us();
}
}
