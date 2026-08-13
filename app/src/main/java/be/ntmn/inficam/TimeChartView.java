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
	private static final long SAMPLE_NS = 100000000L;
	private static final int MAX_SAMPLES = 36000; // one hour at 100 ms
	private final ArrayList<Float> maxValues = new ArrayList<>();
	private final ArrayList<Float> minValues = new ArrayList<>();
	private final ArrayList<Float> centerValues = new ArrayList<>();
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private long startedNs;
	private long lastSampleNs;
	private boolean recording;
	private boolean showMax, showMin, showCenter;
	private int unit;

	public TimeChartView(Context context) { super(context); init(); }
	public TimeChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

	private void init() {
		setBackgroundColor(Color.WHITE);
		textPaint.setColor(Color.DKGRAY);
		textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 10.0f);
		setLayerType(View.LAYER_TYPE_SOFTWARE, null);
	}

	public synchronized void start(int tempUnit, boolean max, boolean min, boolean center) {
		maxValues.clear(); minValues.clear(); centerValues.clear();
		startedNs = System.nanoTime();
		lastSampleNs = 0;
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
		if (lastSampleNs != 0 && now - lastSampleNs < SAMPLE_NS)
			return;
		lastSampleNs = now;
		unit = tempUnit;
		showMax = showMaxValue; showMin = showMinValue; showCenter = showCenterValue;
		if (maxValues.size() >= MAX_SAMPLES) {
			maxValues.remove(0); minValues.remove(0); centerValues.remove(0);
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
		float left = Math.max(60.0f, axisTextWidth + textPaint.measureText("0") + 8.0f);
		float right = w - Math.max(22.0f, textPaint.measureText("0") + 8.0f);
		float top = 44, bottom = h - 30;
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
		float step = niceStep((hi - lo) / 4.0f);
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
		long durationNs = Math.max(SAMPLE_NS, (long) maxValues.size() * SAMPLE_NS);
		long xStepNs = timeStep(durationNs);
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
		for (long t = 0; t <= durationNs; t += xStepNs) {
			float x = left + (float) t / durationNs * (right - left);
			canvas.drawLine(x, top, x, bottom, paint);
			textPaint.setTextAlign(Paint.Align.CENTER);
			canvas.drawText(formatDuration(t), x, h - 7, textPaint);
		}
		drawSeries(canvas, maxValues, Color.RED, lo, hi, left, right, top, bottom);
		drawSeries(canvas, minValues, Color.BLUE, lo, hi, left, right, top, bottom);
		drawSeries(canvas, centerValues, Color.rgb(220, 170, 0), lo, hi, left, right, top, bottom);
		textPaint.setTextAlign(Paint.Align.LEFT);
		StringBuilder labels = new StringBuilder();
		if (showMax) labels.append("Max. T [").append(unitName(unit)).append("]   ");
		if (showMin) labels.append("Min. T [").append(unitName(unit)).append("]   ");
		if (showCenter) labels.append("Temperature [").append(unitName(unit)).append("]");
		textPaint.setColor(Color.DKGRAY); canvas.drawText(labels.toString(), left, 23, textPaint);
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
	private static long timeStep(long duration) {
		if (duration <= 10000000000L) return 1000000000L;
		if (duration <= 60000000000L) return 10000000000L;
		if (duration <= 3600000000000L) return 60000000000L;
		return 600000000000L;
	}
	private static String formatDuration(long ns) {
		long s = ns / 1000000000L;
		if (s < 60) return s + "s";
		return (s / 60) + "m" + (s % 60 == 0 ? "" : (s % 60) + "s");
	}
}
