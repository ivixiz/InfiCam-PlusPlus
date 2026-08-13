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

import java.util.ArrayList;
import java.util.Locale;

/** Lightweight on-device temperature history plot. Samples are collected by MainActivity. */
public final class TimeChartView extends View {
	private static final long DEFAULT_SAMPLE_NS = 100000000L;
	private static final int MAX_SAMPLES = 12000;
	private final ArrayList<Float> maxValues = new ArrayList<>();
	private final ArrayList<Float> minValues = new ArrayList<>();
	private final ArrayList<Float> centerValues = new ArrayList<>();
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private long startedNs;
	private long lastSampleNs;
	private long sampleIntervalNs = DEFAULT_SAMPLE_NS;
	private long effectiveIntervalNs = DEFAULT_SAMPLE_NS;
	private boolean recording;
	private boolean showMax, showMin, showCenter;
	private int unit;

	public TimeChartView(Context context) { super(context); init(); }
	public TimeChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

	private void init() {
		setBackgroundColor(Color.WHITE);
		textPaint.setColor(Color.DKGRAY);
		textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 15.0f);
		setLayerType(View.LAYER_TYPE_SOFTWARE, null);
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center) {
		start(tempUnit, max, min, center, DEFAULT_SAMPLE_NS);
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center,
			long intervalNs) {
		maxValues.clear(); minValues.clear(); centerValues.clear();
		startedNs = System.nanoTime();
		lastSampleNs = 0;
		sampleIntervalNs = Math.max(1_000_000L, intervalNs);
		effectiveIntervalNs = sampleIntervalNs;
		unit = tempUnit;
		showMax = max; showMin = min; showCenter = center;
		recording = true;
		invalidate();
	}

	public synchronized void stop() { recording = false; }
	public synchronized boolean isRecording() { return recording; }

	/** Capture the currently visible chart for inclusion in a saved photo. */
	public synchronized Bitmap snapshot() {
		int w = getWidth(), h = getHeight();
		if (w <= 0 || h <= 0)
			return null;
		Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		draw(canvas);
		return bitmap;
	}

	public synchronized void sample(float max, float min, float center, int tempUnit,
			boolean showMaxValue, boolean showMinValue, boolean showCenterValue) {
		if (!recording)
			return;
		long now = System.nanoTime();
		if (lastSampleNs != 0 && now - lastSampleNs < sampleIntervalNs)
			return;
		lastSampleNs = now;
		unit = tempUnit;
		showMax = showMaxValue; showMin = showMinValue; showCenter = showCenterValue;
		if (maxValues.size() >= MAX_SAMPLES) {
			/* Decimate in one pass instead of shifting one element for every new
			 * sample. This keeps memory and drawing time bounded for multi-hour logs. */
			int out = 0;
			for (int i = 0; i + 1 < maxValues.size(); i += 2) {
				maxValues.set(out, (maxValues.get(i) + maxValues.get(i + 1)) * .5f);
				minValues.set(out, (minValues.get(i) + minValues.get(i + 1)) * .5f);
				centerValues.set(out, (centerValues.get(i) + centerValues.get(i + 1)) * .5f);
				out++;
			}
			if ((maxValues.size() & 1) != 0) {
				int i = maxValues.size() - 1;
				maxValues.set(out, maxValues.get(i)); minValues.set(out, minValues.get(i));
				centerValues.set(out, centerValues.get(i)); out++;
			}
			while (maxValues.size() > out) {
				maxValues.remove(maxValues.size() - 1);
				minValues.remove(minValues.size() - 1);
				centerValues.remove(centerValues.size() - 1);
			}
			effectiveIntervalNs = Math.min(Long.MAX_VALUE / 2, effectiveIntervalNs * 2L);
		}
		maxValues.add(convert(max, tempUnit));
		minValues.add(convert(min, tempUnit));
		centerValues.add(convert(center, tempUnit));
		postInvalidateOnAnimation();
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
		int w = getWidth(), h = getHeight();
		if (w < 80 || h < 60 || maxValues.isEmpty())
			return;
		/* Leave at least one character of breathing room around axis labels.  The
		 * left side is based on the widest usual numeric label so negative values
		 * cannot touch the edge; the right side keeps the last time label visible. */
		float axisTextWidth = textPaint.measureText("-000.0");
		float left = Math.max(52.0f, axisTextWidth + 4.0f);
		float right = w - Math.max(22.0f, textPaint.measureText("0") + 8.0f);
		float top = 52, bottom = h - 38;
		if (right <= left + 10.0f)
			return;
		float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < maxValues.size(); ++i) {
			if (showMax) { hi = Math.max(hi, maxValues.get(i)); lo = Math.min(lo, maxValues.get(i)); }
			if (showMin) { hi = Math.max(hi, minValues.get(i)); lo = Math.min(lo, minValues.get(i)); }
			if (showCenter) { hi = Math.max(hi, centerValues.get(i)); lo = Math.min(lo, centerValues.get(i)); }
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
			textPaint.setColor(Color.DKGRAY); textPaint.setTextAlign(Paint.Align.RIGHT);
			canvas.drawText(String.format(Locale.US, "%.1f", yv), left - 5, y + 4, textPaint);
		}
		long durationNs = Math.max(effectiveIntervalNs,
				(long) maxValues.size() * effectiveIntervalNs);
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
		drawSeries(canvas, maxValues, Color.RED, lo, hi, left, right, top, bottom);
		drawSeries(canvas, minValues, Color.BLUE, lo, hi, left, right, top, bottom);
		drawSeries(canvas, centerValues, Color.rgb(220, 170, 0), lo, hi, left, right, top, bottom);
		/* A strong outer contour separates the chart from the camera and controls,
		 * while the lighter grid remains readable inside it. */
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(5.0f);
		paint.setColor(Color.DKGRAY);
		canvas.drawRect(left, top, right, bottom, paint);
		textPaint.setTextAlign(Paint.Align.LEFT);
		float legendX = left, legendY = 33;
		String suffix = " [" + unitName(unit) + "]";
		if (showMax) { textPaint.setColor(Color.RED); String s = "Max. T" + suffix;
			canvas.drawText(s, legendX, legendY, textPaint); legendX += textPaint.measureText(s) + 18; }
		if (showMin) { textPaint.setColor(Color.BLUE); String s = "Min. T" + suffix;
			canvas.drawText(s, legendX, legendY, textPaint); legendX += textPaint.measureText(s) + 18; }
		if (showCenter) { textPaint.setColor(Color.rgb(220, 170, 0));
			canvas.drawText("Temperature" + suffix, legendX, legendY, textPaint); }
	}

	private void drawSeries(Canvas canvas, ArrayList<Float> values, int color, float lo, float hi,
			float left, float right, float top, float bottom) {
		if ((color == Color.RED && !showMax) || (color == Color.BLUE && !showMin) ||
				(color == Color.rgb(220, 170, 0) && !showCenter)) return;
		path.reset();
		for (int i = 0; i < values.size(); ++i) {
			float x = left + (float) i / Math.max(1, values.size() - 1) * (right - left);
			float y = bottom - (values.get(i) - lo) / (hi - lo) * (bottom - top);
			if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
		}
		paint.setColor(color); paint.setStrokeWidth(4); paint.setStyle(Paint.Style.STROKE);
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
