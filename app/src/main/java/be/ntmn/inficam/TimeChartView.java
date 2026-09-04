package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Lightweight on-device temperature history plot. Samples are collected by MainActivity. */
public final class TimeChartView extends View {
	private static final long DEFAULT_SAMPLE_NS = 100000000L;
	private static final int MAX_SAMPLES = 12000;
	private static final int INITIAL_CAPACITY = 256;
	private float[] maxValues = new float[INITIAL_CAPACITY];
	private float[] minValues = new float[INITIAL_CAPACITY];
	private float[] centerValues = new float[INITIAL_CAPACITY];
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
	private double maxSum, minSum, centerSum;
	private int maxSumCount, minSumCount, centerSumCount;
	private boolean recording;
	private boolean showMax, showMin, showCenter;
	private ChartTraceConfig.Trace[] traceStyles = ChartTraceConfig.defaults();
	private float density;
	private int unit;

	public TimeChartView(Context context) { super(context); init(); }
	public TimeChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

	private void init() {
		setBackgroundColor(Color.WHITE);
		textPaint.setColor(Color.DKGRAY);
		textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 15.0f);
		density = getResources().getDisplayMetrics().density;
		setLayerType(View.LAYER_TYPE_SOFTWARE, null);
	}

	public synchronized void setTraceStyles(ChartTraceConfig.Trace[] styles) {
		if (styles == null || styles.length < 3)
			return;
		traceStyles = styles;
		invalidate();
	}

	public synchronized void setTraceVisibility(int trace, boolean visible) {
		if (trace == ChartTraceConfig.MAX) showMax = visible;
		else if (trace == ChartTraceConfig.MIN) showMin = visible;
		else if (trace == ChartTraceConfig.CENTER) showCenter = visible;
		invalidate();
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center) {
		start(tempUnit, max, min, center, DEFAULT_SAMPLE_NS);
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center,
			long intervalNs) {
		start(tempUnit, max, min, center, intervalNs, 1);
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
		unit = tempUnit;
		showMax = max; showMin = min; showCenter = center;
		recording = true;
		invalidate();
	}

	public synchronized void stop() {
		recording = false;
		invalidate();
	}

	/** Continue the current series. Paused wall-clock time is intentionally omitted. */
	public synchronized void resume() {
		lastSampleNs = 0;
		recording = true;
		invalidate();
	}

	/** Delete the current series without allocating new sample buffers. */
	public synchronized void clear() {
		sampleCount = 0;
		lastSampleNs = 0;
		resetAverage();
		recording = false;
		dataGeneration++;
		invalidate();
	}

	public synchronized boolean isRecording() { return recording; }

	/** Capture the currently visible chart for inclusion in a saved photo. */
	public synchronized Bitmap snapshot() {
		int w = getWidth(), h = getHeight();
		if (w <= 0 || h <= 0)
			return null;
		Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		canvas.drawColor(Color.WHITE);
		drawChart(canvas);
		return bitmap;
	}

	/**
	 * Returns an incremental JSON update for Web Control. A generation change means that the
	 * browser must replace its data (new recording or in-place decimation); otherwise only samples
	 * after {@code requestedFrom} are serialized.
	 */
	public synchronized String getWebStateJson(int state, long requestedGeneration,
			int requestedFrom, boolean exportSeparately, int imageType, int imageQuality,
			boolean videoRecording) {
		int visibleCount = state == 0 ? 0 : sampleCount;
		boolean reset = requestedGeneration != dataGeneration || requestedFrom < 0 ||
				requestedFrom > visibleCount;
		int from = reset ? 0 : requestedFrom;
		StringBuilder json = new StringBuilder(256 + Math.max(0, visibleCount - from) * 24);
		json.append('{')
				.append("\"state\":").append(state)
				.append(",\"recording\":").append(recording)
				.append(",\"videoRecording\":").append(videoRecording)
				.append(",\"generation\":").append(dataGeneration)
				.append(",\"reset\":").append(reset)
				.append(",\"from\":").append(from)
				.append(",\"count\":").append(visibleCount)
				.append(",\"intervalNs\":").append(effectiveIntervalNs)
				.append(",\"unit\":").append(unit)
				.append(",\"showMax\":").append(showMax)
				.append(",\"showMin\":").append(showMin)
				.append(",\"showCenter\":").append(showCenter)
				.append(",\"exportSeparately\":").append(exportSeparately)
				.append(",\"imageType\":").append(imageType)
				.append(",\"imageQuality\":").append(imageQuality)
					.append(",\"viewWidth\":").append(getWidth())
					.append(",\"viewHeight\":").append(getHeight());
		json.append(",\"traces\":[");
		appendTraceJson(json, traceStyles[ChartTraceConfig.MAX], showMax, false);
		appendTraceJson(json, traceStyles[ChartTraceConfig.MIN], showMin, true);
		appendTraceJson(json, traceStyles[ChartTraceConfig.CENTER], showCenter, true);
		json.append(']');
		appendJsonArray(json, "max", maxValues, from, visibleCount);
		appendJsonArray(json, "min", minValues, from, visibleCount);
		appendJsonArray(json, "center", centerValues, from, visibleCount);
		return json.append('}').toString();
	}

	private static void appendTraceJson(StringBuilder json, ChartTraceConfig.Trace trace,
			boolean visible, boolean comma) {
		if (comma) json.append(',');
		json.append('{').append("\"id\":");
		appendJsonString(json, trace.id);
		json.append(",\"measurement\":");
		appendJsonString(json, trace.measurementSetting);
		json.append(",\"name\":");
		appendJsonString(json, trace.name);
		json.append(",\"lineWidth\":").append(trace.lineWidth)
				.append(",\"color\":\"").append(ChartTraceConfig.colorHex(trace.color))
				.append("\",\"show\":").append(visible).append('}');
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

	private static void appendJsonArray(StringBuilder json, String name, float[] values,
			int from, int count) {
		json.append(",\"").append(name).append("\":[");
		for (int i = from; i < count; ++i) {
			if (i > from)
				json.append(',');
			float value = values[i];
			if (Float.isFinite(value))
				json.append(value);
			else
				json.append("null");
		}
		json.append(']');
	}

	public synchronized void sample(float max, float min, float center, int tempUnit,
			boolean showMaxValue, boolean showMinValue, boolean showCenterValue) {
		if (!recording)
			return;
		long now = System.nanoTime();
		/* Once old points are decimated, increase the acquisition interval too. The
		 * output interval additionally includes the averaging window, keeping the time
		 * axis correct while progressively reducing work during multi-hour logs. */
		if (lastSampleNs != 0 && now - lastSampleNs < acquisitionIntervalNs)
			return;
		lastSampleNs = now;
		unit = tempUnit;
		showMax = showMaxValue; showMin = showMinValue; showCenter = showCenterValue;
		if (!showMaxValue) { maxSum = 0.0; maxSumCount = 0; }
		else if (Float.isFinite(max)) { maxSum += max; maxSumCount++; }
		if (!showMinValue) { minSum = 0.0; minSumCount = 0; }
		else if (Float.isFinite(min)) { minSum += min; minSumCount++; }
		if (!showCenterValue) { centerSum = 0.0; centerSumCount = 0; }
		else if (Float.isFinite(center)) { centerSum += center; centerSumCount++; }
		if (++averageCount < averageSamples)
			return;
		float averagedMax = !showMaxValue || maxSumCount == 0 ? Float.NaN :
				(float)(maxSum / maxSumCount);
		float averagedMin = !showMinValue || minSumCount == 0 ? Float.NaN :
				(float)(minSum / minSumCount);
		float averagedCenter = !showCenterValue || centerSumCount == 0 ? Float.NaN :
				(float)(centerSum / centerSumCount);
		resetAverage();
		if (sampleCount >= MAX_SAMPLES) {
			/* Decimate in one pass instead of shifting one element for every new
			 * sample. This keeps memory and drawing time bounded for multi-hour logs. */
			int out = 0;
			for (int i = 0; i + 1 < sampleCount; i += 2) {
				maxValues[out] = finiteAverage(maxValues[i], maxValues[i + 1]);
				minValues[out] = finiteAverage(minValues[i], minValues[i + 1]);
				centerValues[out] = finiteAverage(centerValues[i], centerValues[i + 1]);
				out++;
			}
			if ((sampleCount & 1) != 0) {
				int i = sampleCount - 1;
				maxValues[out] = maxValues[i];
				minValues[out] = minValues[i];
				centerValues[out] = centerValues[i];
				out++;
			}
			sampleCount = out;
			acquisitionIntervalNs = Math.min(Long.MAX_VALUE / 2, acquisitionIntervalNs * 2L);
			effectiveIntervalNs = Math.min(Long.MAX_VALUE / 2, effectiveIntervalNs * 2L);
			dataGeneration++;
		}
		ensureCapacity(sampleCount + 1);
		maxValues[sampleCount] = convert(averagedMax, tempUnit);
		minValues[sampleCount] = convert(averagedMin, tempUnit);
		centerValues[sampleCount] = convert(averagedCenter, tempUnit);
		sampleCount++;
		postInvalidateOnAnimation();
	}

	private static float finiteAverage(float first, float second) {
		if (!Float.isFinite(first)) return second;
		if (!Float.isFinite(second)) return first;
		return (first + second) * 0.5f;
	}

	private void resetAverage() {
		averageCount = 0;
		maxSum = minSum = centerSum = 0.0;
		maxSumCount = minSumCount = centerSumCount = 0;
	}

	private static long saturatedMultiply(long value, int multiplier) {
		return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
	}

	private void ensureCapacity(int required) {
		if (required <= maxValues.length)
			return;
		int capacity = Math.min(MAX_SAMPLES, Math.max(required, maxValues.length * 2));
		maxValues = java.util.Arrays.copyOf(maxValues, capacity);
		minValues = java.util.Arrays.copyOf(minValues, capacity);
		centerValues = java.util.Arrays.copyOf(centerValues, capacity);
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
		if (!recording)
			drawPausedOverlay(canvas);
	}

	private void drawChart(Canvas canvas) {
		int w = getWidth(), h = getHeight();
		if (w < 80 || h < 60 || sampleCount == 0)
			return;
		/* Leave at least one character of breathing room around axis labels.  The
		 * left side is based on the widest usual numeric label so negative values
		 * cannot touch the edge; the right side keeps the last time label visible. */
		float axisTextWidth = textPaint.measureText("-000.0");
		float left = Math.max(52.0f, axisTextWidth + 4.0f);
		float right = w - Math.max(22.0f, textPaint.measureText("0") + 8.0f);
		String suffix = " [" + unitName(unit) + "]";
		float legendBaseline = textPaint.getTextSize() + 8.0f;
		float legendLineHeight = textPaint.getTextSize() * 1.25f;
		int legendRows = countLegendRows(left, right, suffix);
		float top = legendBaseline + Math.max(0, legendRows - 1) * legendLineHeight + 14.0f;
		float bottom = h - 38;
		if (right <= left + 10.0f)
			return;
		float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < sampleCount; ++i) {
			if (showMax && Float.isFinite(maxValues[i])) {
				hi = Math.max(hi, maxValues[i]); lo = Math.min(lo, maxValues[i]);
			}
			if (showMin && Float.isFinite(minValues[i])) {
				hi = Math.max(hi, minValues[i]); lo = Math.min(lo, minValues[i]);
			}
			if (showCenter && Float.isFinite(centerValues[i])) {
				hi = Math.max(hi, centerValues[i]); lo = Math.min(lo, centerValues[i]);
			}
		}
		if (!Float.isFinite(lo) || !Float.isFinite(hi)) return;
		if (hi - lo < 0.5f) { hi += 0.25f; lo -= 0.25f; }
		float step = niceStep((hi - lo) / 8.0f);
		lo = (float) Math.floor(lo / step) * step;
		hi = (float) Math.ceil(hi / step) * step;
		/* Five subdivisions per major interval give a useful minor grid without
		 * turning the plot into a dense, expensive raster. */
		int minorGrid = Color.rgb(238, 238, 238);
		int majorCount = (int) Math.ceil((hi - lo) / step);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(1.0f);
		paint.setColor(minorGrid);
		for (int major = 0; major <= majorCount; ++major) {
			float majorValue = lo + major * step;
			for (int sub = 1; sub < 5; ++sub) {
				float yv = majorValue + sub * step / 5.0f;
				if (yv >= hi)
					continue;
				float y = bottom - (yv - lo) / (hi - lo) * (bottom - top);
				canvas.drawLine(left, y, right, y, paint);
			}
		}
		paint.setStrokeWidth(2.0f);
		paint.setColor(Color.LTGRAY);
		for (int major = 0; major <= majorCount; ++major) {
			float yv = lo + major * step;
			if (yv > hi + step * 0.1f)
				continue;
			float y = bottom - (yv - lo) / (hi - lo) * (bottom - top);
			canvas.drawLine(left, y, right, y, paint);
			textPaint.setColor(Color.BLACK); textPaint.setTextAlign(Paint.Align.RIGHT);
			canvas.drawText(String.format(Locale.US, "%.1f", yv), left - 5, y + 4, textPaint);
		}
		long durationNs = Math.max(effectiveIntervalNs,
				(long) sampleCount * effectiveIntervalNs);
		long xStepNs = timeStep(durationNs, right - left);
		paint.setStrokeWidth(1.0f);
		paint.setColor(minorGrid);
		for (long t = 0; t <= durationNs; t += xStepNs) {
			for (int sub = 1; sub < 5; ++sub) {
				long minorTime = t + xStepNs * sub / 5L;
				if (minorTime >= durationNs)
					continue;
				float x = left + (float) minorTime / durationNs * (right - left);
				canvas.drawLine(x, top, x, bottom, paint);
			}
		}
		paint.setStrokeWidth(2.0f);
		paint.setColor(Color.LTGRAY);
		float previousLabelRight = Float.NEGATIVE_INFINITY;
		for (long t = 0; t <= durationNs; t += xStepNs) {
			float x = left + (float) t / durationNs * (right - left);
			canvas.drawLine(x, top, x, bottom, paint);
			textPaint.setColor(Color.BLACK);
			textPaint.setTextAlign(Paint.Align.CENTER);
			String label = formatDuration(t, durationNs);
			float halfWidth = textPaint.measureText(label) * 0.5f;
			/* A final safety net for rounded/variable-width labels: never paint a
			 * label over the preceding one. Grid lines remain visible even when a
			 * label is skipped. */
			if (x - halfWidth >= previousLabelRight + 4.0f || t == 0) {
				canvas.drawText(label, x, h - 7, textPaint);
				previousLabelRight = x + halfWidth;
			}
		}
		drawSeries(canvas, maxValues, traceStyles[ChartTraceConfig.MAX], showMax,
				lo, hi, left, right, top, bottom);
		drawSeries(canvas, minValues, traceStyles[ChartTraceConfig.MIN], showMin,
				lo, hi, left, right, top, bottom);
		drawSeries(canvas, centerValues, traceStyles[ChartTraceConfig.CENTER], showCenter,
				lo, hi, left, right, top, bottom);
		/* A strong outer contour separates the chart from the camera and controls,
		 * while the lighter grid remains readable inside it. */
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(5.0f);
		paint.setColor(Color.DKGRAY);
		canvas.drawRect(left, top, right, bottom, paint);
		textPaint.setTextAlign(Paint.Align.LEFT);
		drawLegend(canvas, left, right, legendBaseline, legendLineHeight, suffix);
	}

	private int countLegendRows(float left, float right, String suffix) {
		int rows = 1;
		float x = left;
		for (int i = 0; i < traceStyles.length; ++i) {
			if (!isTraceVisible(i)) continue;
			float width = textPaint.measureText(traceStyles[i].name + suffix);
			if (x > left && x + width > right) { rows++; x = left; }
			x += width + 18.0f;
		}
		return rows;
	}

	private void drawLegend(Canvas canvas, float left, float right, float baseline,
			float lineHeight, String suffix) {
		float x = left, y = baseline;
		for (int i = 0; i < traceStyles.length; ++i) {
			if (!isTraceVisible(i)) continue;
			ChartTraceConfig.Trace trace = traceStyles[i];
			String text = trace.name + suffix;
			float width = textPaint.measureText(text);
			if (x > left && x + width > right) { x = left; y += lineHeight; }
			textPaint.setColor(trace.color);
			canvas.drawText(text, x, y, textPaint);
			x += width + 18.0f;
		}
	}

	private boolean isTraceVisible(int trace) {
		return trace == ChartTraceConfig.MAX ? showMax :
				trace == ChartTraceConfig.MIN ? showMin : showCenter;
	}

	private void drawPausedOverlay(Canvas canvas) {
		int width = getWidth(), height = getHeight();
		if (width <= 0 || height <= 0)
			return;
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(Color.argb(145, 0, 0, 0));
		canvas.drawRect(0, 0, width, height, paint);

		float oldSize = textPaint.getTextSize();
		Paint.Align oldAlign = textPaint.getTextAlign();
		int oldColor = textPaint.getColor();
		textPaint.setTextSize(Math.max(oldSize, getResources().getDisplayMetrics().scaledDensity * 16));
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setColor(Color.WHITE);
		float spacing = textPaint.getTextSize() * 1.45f;
		float firstBaseline = height * .5f - spacing;
		canvas.drawText("Stopped", width * .5f, firstBaseline, textPaint);
		canvas.drawText("Click to continue", width * .5f, firstBaseline + spacing, textPaint);
		canvas.drawText("Hold to delete", width * .5f, firstBaseline + spacing * 2, textPaint);
		textPaint.setTextSize(oldSize);
		textPaint.setTextAlign(oldAlign);
		textPaint.setColor(oldColor);
	}

	private void drawSeries(Canvas canvas, float[] values, ChartTraceConfig.Trace trace,
			boolean visible, float lo, float hi,
			float left, float right, float top, float bottom) {
		if (!visible) return;
		path.reset();
		boolean started = false;
		for (int i = 0; i < sampleCount; ++i) {
			if (!Float.isFinite(values[i])) {
				started = false;
				continue;
			}
			float x = left + (float) i / Math.max(1, sampleCount - 1) * (right - left);
			float y = bottom - (values[i] - lo) / (hi - lo) * (bottom - top);
			if (!started) { path.moveTo(x, y); started = true; }
			else path.lineTo(x, y);
		}
		paint.setColor(trace.color);
		paint.setStrokeWidth(trace.lineWidth * density);
		paint.setStyle(Paint.Style.STROKE);
		canvas.drawPath(path, paint);
	}

	private static float niceStep(float raw) {
		float p = (float) Math.pow(10, Math.floor(Math.log10(raw)));
		float n = raw / p;
		return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
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

		/* The nominal step is selected for the duration, but the available chart
		 * width can be smaller in split/landscape mode. Increase it until every
		 * neighbouring label has enough room. This prevents the transient overlap
		 * around 17–20 seconds and at the one-minute boundary. */
		final long[] multipliers = {1L, 2L, 5L, 10L};
		for (long multiplier : multipliers) {
			long candidate = step > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : step * multiplier;
			float pixelStep = (float) candidate / Math.max(1L, duration) * plotWidth;
			float labelWidth = 0.0f;
			for (long t = 0; t <= duration; t += candidate)
				labelWidth = Math.max(labelWidth, textPaint.measureText(formatDuration(t, duration)));
			float required = Math.max(28.0f, labelWidth + 8.0f);
			if (pixelStep >= required)
				return candidate;
		}
		return step * 10L;
	}
	private static String formatDuration(long ns) {
		return formatDuration(ns, ns);
	}
	private static String formatDuration(long ns, long totalNs) {
		long s = ns / 1000000000L;
		/* Before five minutes, elapsed seconds are shorter and unambiguous (e.g.
		 * 80s instead of the wider 1m20s). */
		if (totalNs < 300000000000L) return s + "s";
		if (s < 60) return s + "s";
		if (s < 3600) return (s / 60) + "m" + (s % 60 == 0 ? "" : (s % 60) + "s");
		return (s / 3600) + "h" + ((s % 3600) == 0 ? "" : (s % 3600) / 60 + "m");
	}
}
