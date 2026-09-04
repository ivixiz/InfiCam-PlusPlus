#ifndef SPATIAL_CALIBRATION_ENGINE_H_
#define SPATIAL_CALIBRATION_ENGINE_H_

#include <cstdint>
#include <string>
#include <vector>

/**
 * Camera-agnostic additive fixed-pattern calibration. This class deliberately
 * knows nothing about UVC, a camera model, a shutter or Android lifecycle.
 */
class SpatialCalibrationEngineNative {
public:
	bool configure(int pixel_count);
	bool set_active_map(const float *offsets, int pixel_count);
	void clear_active_map();
	void begin();
	void cancel();
	bool process(float *temperatures, int pixel_count, bool collect);
	bool finish();

	const std::vector<float>& candidate() const { return candidate_map; }
	const std::string& error() const { return last_error; }
	uint64_t collected_frames() const { return accepted_frames; }
	float invalid_pixel_fraction() const { return invalid_fraction; }
	float spatial_rms() const { return candidate_rms; }
	double average_process_us() const;
	double max_process_us() const { return max_processing_us; }

private:
	static constexpr uint64_t ROBUST_INITIALIZATION_FRAMES = 128;
	static constexpr uint64_t MIN_COLLECTED_FRAMES = 300;
	static constexpr float MIN_FINITE_FRAME_FRACTION = 0.98f;
	static constexpr float MAX_INVALID_PIXEL_FRACTION = 0.01f;

	int pixels = 0;
	bool collecting = false;
	std::vector<float> active_map;
	std::vector<float> residual_mean;
	std::vector<float> residual_m2;
	std::vector<uint32_t> sample_count;
	std::vector<float> median_scratch;
	std::vector<float> candidate_map;
	uint64_t accepted_frames = 0;
	uint64_t rejected_frames = 0;
	float invalid_fraction = 1.0f;
	float candidate_rms = 0.0f;
	uint64_t processed_calls = 0;
	double total_processing_us = 0.0;
	double max_processing_us = 0.0;
	std::string last_error;
};

#endif
