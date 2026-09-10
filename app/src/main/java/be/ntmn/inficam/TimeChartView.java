package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

/** Lightweight dynamic temperature history plot. Samples are collected by MainActivity. */
public final class TimeChartView extends View {
	private static final long DEFAULT_SAMPLE_NS = 100000000L;
	private static final int MAX_SAMPLES = 12000;
	private static final int INITIAL_CAPACITY = 256;
	private static final long[] TIME_STEP_MULTIPLIERS = {1L, 2L, 5L, 10L};

	private static final class Series {
		final String id;
		float[] values = new float[INITIAL_CAPACITY];
		double sum;
		int sumCount;
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;

		Series(String id, int existingSamples) {
			this.id = id;
			if (existingSamples > values.length)
				values = new float[Math.min(MAX_SAMPLES, existingSamples * 2)];
			Arrays.fill(values, 0, existingSamples, Float.NaN);
		}

		void include(float value) {
			if (!Float.isFinite(value)) return;
			if (value < min) min = value;
			if (value > max) max = value;
		}

		void resetBounds() {
			min = Float.POSITIVE_INFINITY;
			max = Float.NEGATIVE_INFINITY;
		}
	}

	private Series[] series = new Series[0];
	private ChartTraceConfig.Trace[] traceStyles = ChartTraceConfig.defaults();
	private int sampleCount;
	private long dataGeneration;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private long lastSampleNs;
	private long acquisitionIntervalNs = DEFAULT_SAMPLE_NS;
	private long effectiveIntervalNs = DEFAULT_SAMPLE_NS;
	private int averageSamples = 1;
	private int averageCount;
	private boolean recording;
	private float density;
	private int unit;

	public TimeChartView(Context context) { super(context); init(); }
	public TimeChartView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs); init();
	}

	private void init() {
		setBackgroundColor(Color.WHITE);
		textPaint.setColor(Color.DKGRAY);
		textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 15.0f);
		density = getResources().getDisplayMetrics().density;
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
		setTraceStyles(traceStyles);
	}

	public synchronized void setTraceStyles(ChartTraceConfig.Trace[] styles) {
		if (styles == null || styles.length < 3) return;
		boolean structureChanged = styles.length != series.length;
		Series[] updated = new Series[styles.length];
		for (int i = 0; i < styles.length; ++i) {
			for (Series old : series) if (old.id.equals(styles[i].id)) {
				updated[i] = old;
				break;
			}
			if (updated[i] == null) {
				updated[i] = new Series(styles[i].id, sampleCount);
				structureChanged = true;
			}
		}
		traceStyles = styles;
		series = updated;
		if (structureChanged && sampleCount > 0) dataGeneration++;
		invalidate();
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center,
			long intervalNs, int samplesToAverage) {
		sampleCount = 0;
		dataGeneration++;
		lastSampleNs = 0;
		acquisitionIntervalNs = Math.max(1_000_000L, intervalNs);
		averageSamples = Math.max(1, Math.min(16, samplesToAverage));
		effectiveIntervalNs = saturatedMultiply(acquisitionIntervalNs, averageSamples);
		resetAverage();
		for (Series item : series) item.resetBounds();
		unit = tempUnit;
		recording = true;
		invalidate();
	}

	public synchronized void stop() { recording = false; invalidate(); }
	public synchronized void resume() { lastSampleNs = 0; recording = true; invalidate(); }
	public synchronized void clear() {
		sampleCount = 0;
		lastSampleNs = 0;
		resetAverage();
		recording = false;
		dataGeneration++;
		for (Series item : series) item.resetBounds();
		invalidate();
	}
	public synchronized boolean isRecording() { return recording; }

	public synchronized Bitmap snapshot() {
		int width = getWidth(), height = getHeight();
		if (width <= 0 || height <= 0) return null;
		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		canvas.drawColor(Color.WHITE);
		drawChart(canvas);
		return bitmap;
	}

	public synchronized String getWebStateJson(int state, long requestedGeneration,
			int requestedFrom, boolean exportSeparately, int imageType, int imageQuality,
			boolean videoRecording) {
		int visibleCount = state == 0 ? 0 : sampleCount;
		boolean reset = requestedGeneration != dataGeneration || requestedFrom < 0 ||
				requestedFrom > visibleCount;
		int from = reset ? 0 : requestedFrom;
		StringBuilder json = new StringBuilder(384 + Math.max(0, visibleCount - from) *
				Math.max(24, series.length * 8));
		json.append('{').append("\"state\":").append(state)
				.append(",\"recording\":").append(recording)
				.append(",\"videoRecording\":").append(videoRecording)
				.append(",\"generation\":").append(dataGeneration)
				.append(",\"reset\":").append(reset)
				.append(",\"from\":").append(from)
				.append(",\"count\":").append(visibleCount)
				.append(",\"intervalNs\":").append(effectiveIntervalNs)
				.append(",\"unit\":").append(unit)
				.append(",\"showMax\":").append(isVisible(ChartTraceConfig.MAX))
				.append(",\"showMin\":").append(isVisible(ChartTraceConfig.MIN))
				.append(",\"showCenter\":").append(isVisible(ChartTraceConfig.CENTER))
				.append(",\"exportSeparately\":").append(exportSeparately)
				.append(",\"imageType\":").append(imageType)
				.append(",\"imageQuality\":").append(imageQuality)
				.append(",\"viewWidth\":").append(getWidth())
				.append(",\"viewHeight\":").append(getHeight())
				.append(",\"traces\":[");
		for (int i = 0; i < traceStyles.length; ++i) {
			if (i != 0) json.append(',');
			appendTraceJson(json, traceStyles[i]);
		}
		json.append("],\"series\":{");
		for (int i = 0; i < series.length; ++i) {
			if (i != 0) json.append(',');
			appendJsonString(json, series[i].id);
			json.append(':');
			appendJsonArray(json, series[i].values, from, visibleCount);
		}
		return json.append("}}").toString();
	}

	private static void appendTraceJson(StringBuilder json, ChartTraceConfig.Trace trace) {
		json.append('{').append("\"id\":"); appendJsonString(json, trace.id);
		json.append(",\"measurement\":");
		if (trace.measurementSetting == null) json.append("null");
		else appendJsonString(json, trace.measurementSetting);
		json.append(",\"name\":"); appendJsonString(json, trace.name);
		json.append(",\"lineWidth\":").append(trace.lineWidth)
				.append(",\"color\":\"").append(ChartTraceConfig.colorHex(trace.color))
				.append("\",\"show\":").append(trace.show).append('}');
	}

	private static void appendJsonString(StringBuilder json, String value) {
		json.append('"');
		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			if (c == '"' || c == '\\') json.append('\\');
			if (c >= 0x20) json.append(c);
		}
		json.append('"');
	}

	private static void appendJsonArray(StringBuilder json, float[] values, int from, int count) {
		json.append('[');
		for (int i = from; i < count; ++i) {
			if (i > from) json.append(',');
			if (Float.isFinite(values[i])) json.append(values[i]); else json.append("null");
		}
		json.append(']');
	}

	/** Values are Celsius and aligned to the current trace-style snapshot. */
	public synchronized void sample(float[] values, int tempUnit) {
		if (!recording || values == null) return;
		long now = System.nanoTime();
		if (lastSampleNs != 0 && now - lastSampleNs < acquisitionIntervalNs) return;
		lastSampleNs = now;
		unit = tempUnit;
		int count = Math.min(Math.min(values.length, series.length), traceStyles.length);
		for (int i = 0; i < count; ++i) {
			Series item = series[i];
			if (!traceStyles[i].show) { item.sum = 0.0; item.sumCount = 0; }
			else if (Float.isFinite(values[i])) { item.sum += values[i]; item.sumCount++; }
		}
		if (++averageCount < averageSamples) return;
		if (sampleCount >= MAX_SAMPLES) decimate();
		ensureCapacity(sampleCount + 1);
		for (int i = 0; i < series.length; ++i) {
			Series item = series[i];
			float average = i >= traceStyles.length || !traceStyles[i].show || item.sumCount == 0 ?
					Float.NaN : (float) (item.sum / item.sumCount);
			float converted = convert(average, tempUnit);
			item.values[sampleCount] = converted;
			item.include(converted);
		}
		sampleCount++;
		resetAverage();
		postInvalidateOnAnimation();
	}

	private void decimate() {
		for (Series item : series) item.resetBounds();
		int out = 0;
		for (int i = 0; i + 1 < sampleCount; i += 2) {
			for (Series item : series) {
				item.values[out] = finiteAverage(item.values[i], item.values[i + 1]);
				item.include(item.values[out]);
			}
			out++;
		}
		if ((sampleCount & 1) != 0) {
			int last = sampleCount - 1;
			for (Series item : series) {
				item.values[out] = item.values[last];
				item.include(item.values[out]);
			}
			out++;
		}
		sampleCount = out;
		acquisitionIntervalNs = Math.min(Long.MAX_VALUE / 2, acquisitionIntervalNs * 2L);
		effectiveIntervalNs = Math.min(Long.MAX_VALUE / 2, effectiveIntervalNs * 2L);
		dataGeneration++;
	}

	private static float finiteAverage(float first, float second) {
		if (!Float.isFinite(first)) return second;
		if (!Float.isFinite(second)) return first;
		return (first + second) * 0.5f;
	}

	private void resetAverage() {
		averageCount = 0;
		for (Series item : series) { item.sum = 0.0; item.sumCount = 0; }
	}

	private void ensureCapacity(int required) {
		for (Series item : series) if (required > item.values.length) {
			int capacity = Math.min(MAX_SAMPLES, Math.max(required, item.values.length * 2));
			item.values = Arrays.copyOf(item.values, capacity);
		}
	}

	private static long saturatedMultiply(long value, int multiplier) {
		return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
	}

	private static float convert(float celsius, int tempUnit) {
		switch (tempUnit) {
			case Util.TEMPUNIT_FAHRENHEIT: return celsius * 9.0f / 5.0f + 32.0f;
			case Util.TEMPUNIT_KELVIN: return celsius + 273.15f;
			case Util.TEMPUNIT_RANKINE: return (celsius + 273.15f) * 9.0f / 5.0f;
			default: return celsius;
		}
	}

	private static String unitName(int unit) {
		switch (unit) {
			case Util.TEMPUNIT_FAHRENHEIT: return "°F";
			case Util.TEMPUNIT_KELVIN: return "K";
			case Util.TEMPUNIT_RANKINE: return "°R";
			default: return "°C";
		}
	}

	@Override protected synchronized void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		drawChart(canvas);
		if (!recording) drawPausedOverlay(canvas);
	}

	private void drawChart(Canvas canvas) {
		int width = getWidth(), height = getHeight();
		if (width < 80 || height < 60 || sampleCount == 0) return;
		float left = Math.max(52.0f, textPaint.measureText("-000.0") + 4.0f);
		float right = width - Math.max(22.0f, textPaint.measureText("0") + 8.0f);
		String suffix = " [" + unitName(unit) + "]";
		float legendBaseline = textPaint.getTextSize() + 8.0f;
		float legendLineHeight = textPaint.getTextSize() * 1.25f;
		int legendRows = countLegendRows(left, right, suffix);
		float top = legendBaseline + Math.max(0, legendRows - 1) * legendLineHeight + 14.0f;
		float bottom = height - 38;
		if (right <= left + 10.0f || bottom <= top + 10.0f) return;
		float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
		for (int trace = 0; trace < series.length; ++trace) if (isVisible(trace)) {
			lo = Math.min(lo, series[trace].min);
			hi = Math.max(hi, series[trace].max);
		}
		if (!Float.isFinite(lo) || !Float.isFinite(hi)) return;
		if (hi - lo < 0.5f) { hi += 0.25f; lo -= 0.25f; }
		float step = niceStep((hi - lo) / 8.0f);
		lo = (float) Math.floor(lo / step) * step;
		hi = (float) Math.ceil(hi / step) * step;
		int majorCount = (int) Math.ceil((hi - lo) / step);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(1.0f); paint.setColor(Color.rgb(238, 238, 238));
		for (int major = 0; major <= majorCount; ++major) for (int sub = 1; sub < 5; ++sub) {
			float value = lo + major * step + sub * step / 5.0f;
			if (value >= hi) continue;
			float y = bottom - (value - lo) / (hi - lo) * (bottom - top);
			canvas.drawLine(left, y, right, y, paint);
		}
		paint.setStrokeWidth(2.0f); paint.setColor(Color.LTGRAY);
		for (int major = 0; major <= majorCount; ++major) {
			float value = lo + major * step;
			if (value > hi + step * 0.1f) continue;
			float y = bottom - (value - lo) / (hi - lo) * (bottom - top);
			canvas.drawLine(left, y, right, y, paint);
			textPaint.setColor(Color.BLACK); textPaint.setTextAlign(Paint.Align.RIGHT);
			canvas.drawText(String.format(Locale.US, "%.1f", value), left - 5, y + 4, textPaint);
		}
		long durationNs = Math.max(effectiveIntervalNs, (long) sampleCount * effectiveIntervalNs);
		long xStepNs = timeStep(durationNs, right - left);
		paint.setStrokeWidth(1.0f); paint.setColor(Color.rgb(238, 238, 238));
		for (long time = 0; time <= durationNs; time += xStepNs) for (int sub = 1; sub < 5; ++sub) {
			long minor = time + xStepNs * sub / 5L;
			if (minor >= durationNs) continue;
			float x = left + (float) minor / durationNs * (right - left);
			canvas.drawLine(x, top, x, bottom, paint);
		}
		paint.setStrokeWidth(2.0f); paint.setColor(Color.LTGRAY);
		float previousLabelRight = Float.NEGATIVE_INFINITY;
		for (long time = 0; time <= durationNs; time += xStepNs) {
			float x = left + (float) time / durationNs * (right - left);
			canvas.drawLine(x, top, x, bottom, paint);
			textPaint.setColor(Color.BLACK); textPaint.setTextAlign(Paint.Align.CENTER);
			String label = formatDuration(time, durationNs);
			float halfWidth = textPaint.measureText(label) * 0.5f;
			if (x - halfWidth >= previousLabelRight + 4.0f || time == 0) {
				canvas.drawText(label, x, height - 7, textPaint);
				previousLabelRight = x + halfWidth;
			}
		}
		for (int i = 0; i < series.length; ++i)
			drawSeries(canvas, series[i].values, traceStyles[i], lo, hi, left, right, top, bottom);
		paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5.0f); paint.setColor(Color.DKGRAY);
		canvas.drawRect(left, top, right, bottom, paint);
		textPaint.setTextAlign(Paint.Align.LEFT);
		drawLegend(canvas, left, right, legendBaseline, legendLineHeight, suffix);
	}

	private int countLegendRows(float left, float right, String suffix) {
		int rows = 1; float x = left;
		for (int i = 0; i < traceStyles.length; ++i) if (isVisible(i)) {
			float width = textPaint.measureText(traceStyles[i].name + suffix);
			if (x > left && x + width > right) { rows++; x = left; }
			x += width + 18.0f;
		}
		return rows;
	}

	private void drawLegend(Canvas canvas, float left, float right, float baseline,
			float lineHeight, String suffix) {
		float x = left, y = baseline;
		for (int i = 0; i < traceStyles.length; ++i) if (isVisible(i)) {
			ChartTraceConfig.Trace trace = traceStyles[i];
			String value = trace.name + suffix;
			float width = textPaint.measureText(value);
			if (x > left && x + width > right) { x = left; y += lineHeight; }
			textPaint.setColor(trace.color); canvas.drawText(value, x, y, textPaint);
			x += width + 18.0f;
		}
	}

	private boolean isVisible(int trace) {
		return trace >= 0 && trace < traceStyles.length && traceStyles[trace].show;
	}

	private void drawPausedOverlay(Canvas canvas) {
		int width = getWidth(), height = getHeight();
		if (width <= 0 || height <= 0) return;
		paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(145, 0, 0, 0));
		canvas.drawRect(0, 0, width, height, paint);
		float oldSize = textPaint.getTextSize(); Paint.Align oldAlign = textPaint.getTextAlign();
		int oldColor = textPaint.getColor();
		textPaint.setTextSize(Math.max(oldSize,
				getResources().getDisplayMetrics().scaledDensity * 16));
		textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setColor(Color.WHITE);
		float spacing = textPaint.getTextSize() * 1.45f;
		float first = height * 0.5f - spacing;
		canvas.drawText("Stopped", width * 0.5f, first, textPaint);
		canvas.drawText("Click to continue", width * 0.5f, first + spacing, textPaint);
		canvas.drawText("Hold to delete", width * 0.5f, first + spacing * 2, textPaint);
		textPaint.setTextSize(oldSize); textPaint.setTextAlign(oldAlign); textPaint.setColor(oldColor);
	}

	private void drawSeries(Canvas canvas, float[] values, ChartTraceConfig.Trace trace,
			float lo, float hi, float left, float right, float top, float bottom) {
		if (!trace.show) return;
		path.reset();
		int columns = Math.max(1, Math.round(right - left));
		if (sampleCount <= columns * 2) {
			boolean started = false;
			for (int i = 0; i < sampleCount; ++i) {
				if (!Float.isFinite(values[i])) { started = false; continue; }
				float x = left + (float) i / Math.max(1, sampleCount - 1) * (right - left);
				float y = chartY(values[i], lo, hi, top, bottom);
				if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
			}
		} else {
			// ngspice-style line compression: retain first/last and the extrema of every
			// display-pixel column. Work is bounded by the viewport, while narrow spikes survive.
			boolean started = false;
			int column = -1;
			float firstY = 0.0f, lastY = 0.0f, minY = 0.0f, maxY = 0.0f;
			for (int i = 0; i <= sampleCount; ++i) {
				boolean finite = i < sampleCount && Float.isFinite(values[i]);
				int nextColumn = finite ? (int) ((long) i * (columns - 1) /
						Math.max(1, sampleCount - 1)) : -1;
				if (column >= 0 && (!finite || nextColumn != column)) {
					float x = left + column;
					if (!started) path.moveTo(x, firstY); else path.lineTo(x, firstY);
					if (minY != firstY) path.lineTo(x, minY);
					if (maxY != minY) path.lineTo(x, maxY);
					if (lastY != maxY) path.lineTo(x, lastY);
					started = true;
					column = -1;
				}
				if (!finite) { started = false; continue; }
				float y = chartY(values[i], lo, hi, top, bottom);
				if (column < 0) {
					column = nextColumn;
					firstY = lastY = minY = maxY = y;
				} else {
					lastY = y;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}
		paint.setColor(trace.color); paint.setStrokeWidth(trace.lineWidth * density);
		paint.setStyle(Paint.Style.STROKE); canvas.drawPath(path, paint);
	}

	private static float chartY(float value, float lo, float hi, float top, float bottom) {
		return bottom - (value - lo) / (hi - lo) * (bottom - top);
	}

	private static float niceStep(float raw) {
		float power = (float) Math.pow(10, Math.floor(Math.log10(raw)));
		float normalized = raw / power;
		return (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * power;
	}

	private long timeStep(long duration, float plotWidth) {
		long step;
		if (duration <= 20000000000L) step = 1000000000L;
		else if (duration <= 120000000000L) step = 5000000000L;
		else if (duration <= 600000000000L) step = 10000000000L;
		else if (duration <= 3600000000000L) step = 60000000000L;
		else if (duration <= 21600000000000L) step = 3600000000000L;
		else if (duration <= 43200000000000L) step = 7200000000000L;
		else step = 21600000000000L;
		for (long multiplier : TIME_STEP_MULTIPLIERS) {
			long candidate = step > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : step * multiplier;
			float pixelStep = (float) candidate / Math.max(1L, duration) * plotWidth;
			float labelWidth = 0.0f;
			for (long time = 0; time <= duration; time += candidate)
				labelWidth = Math.max(labelWidth,
						textPaint.measureText(formatDuration(time, duration)));
			if (pixelStep >= Math.max(28.0f, labelWidth + 8.0f)) return candidate;
		}
		return step * 10L;
	}

	private static String formatDuration(long ns, long totalNs) {
		long seconds = ns / 1000000000L;
		if (totalNs < 300000000000L) return seconds + "s";
		if (seconds < 60) return seconds + "s";
		if (seconds < 3600)
			return (seconds / 60) + "m" + (seconds % 60 == 0 ? "" : (seconds % 60) + "s");
		return (seconds / 3600) + "h" + ((seconds % 3600) == 0 ? "" :
				(seconds % 3600) / 60 + "m");
	}
}
