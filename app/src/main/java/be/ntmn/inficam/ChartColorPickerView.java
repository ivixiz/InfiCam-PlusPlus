package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/** Compact allocation-free HSV picker: saturation/value field plus a hue gradient. */
public final class ChartColorPickerView extends View {
	public interface Listener { void onColorChanged(int color); }

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF saturationValueRect = new RectF();
	private final RectF hueRect = new RectF();
	private final float[] hsv = {0.0f, 1.0f, 1.0f};
	private Shader saturationValueShader;
	private Shader hueShader;
	private Listener listener;
	private boolean editingHue;

	public ChartColorPickerView(Context context) { super(context); init(); }
	public ChartColorPickerView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs); init();
	}

	private void init() {
		markerPaint.setStyle(Paint.Style.STROKE);
		markerPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2.0f);
		markerPaint.setColor(Color.WHITE);
		setMinimumHeight(Math.round(getResources().getDisplayMetrics().density * 240.0f));
	}

	public void setListener(Listener listener) { this.listener = listener; }

	public void setColor(int color) {
		Color.colorToHSV(color, hsv);
		rebuildSaturationValueShader();
		invalidate();
	}

	public int getColor() { return Color.HSVToColor(hsv); }

	@Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
		float density = getResources().getDisplayMetrics().density;
		float gap = 12.0f * density;
		float hueHeight = 30.0f * density;
		saturationValueRect.set(0, 0, width, Math.max(1, height - hueHeight - gap));
		hueRect.set(0, saturationValueRect.bottom + gap, width, height);
		int[] hueColors = {
				Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
				Color.BLUE, Color.MAGENTA, Color.RED
		};
		hueShader = new LinearGradient(0, 0, width, 0, hueColors, null,
				Shader.TileMode.CLAMP);
		rebuildSaturationValueShader();
	}

	private void rebuildSaturationValueShader() {
		if (saturationValueRect.width() <= 0 || saturationValueRect.height() <= 0)
			return;
		Shader saturation = new LinearGradient(saturationValueRect.left, 0,
				saturationValueRect.right, 0, Color.WHITE,
				Color.HSVToColor(new float[]{hsv[0], 1.0f, 1.0f}), Shader.TileMode.CLAMP);
		Shader value = new LinearGradient(0, saturationValueRect.top, 0,
				saturationValueRect.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP);
		saturationValueShader = new ComposeShader(saturation, value, PorterDuff.Mode.MULTIPLY);
	}

	@Override protected void onDraw(Canvas canvas) {
		paint.setStyle(Paint.Style.FILL);
		paint.setShader(saturationValueShader);
		canvas.drawRect(saturationValueRect, paint);
		paint.setShader(hueShader);
		canvas.drawRect(hueRect, paint);
		paint.setShader(null);
		float x = saturationValueRect.left + hsv[1] * saturationValueRect.width();
		float y = saturationValueRect.top + (1.0f - hsv[2]) * saturationValueRect.height();
		canvas.drawCircle(x, y, markerPaint.getStrokeWidth() * 3.0f, markerPaint);
		float hueX = hueRect.left + (hsv[0] / 360.0f) * hueRect.width();
		canvas.drawLine(hueX, hueRect.top, hueX, hueRect.bottom, markerPaint);
	}

	@Override public boolean onTouchEvent(MotionEvent event) {
		if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
			editingHue = hueRect.contains(event.getX(), event.getY());
			getParent().requestDisallowInterceptTouchEvent(true);
		} else if (event.getActionMasked() != MotionEvent.ACTION_MOVE &&
				event.getActionMasked() != MotionEvent.ACTION_UP) {
			return true;
		}
		if (editingHue) {
			hsv[0] = clamp01((event.getX() - hueRect.left) / hueRect.width()) * 360.0f;
			rebuildSaturationValueShader();
		} else {
			hsv[1] = clamp01((event.getX() - saturationValueRect.left) /
					saturationValueRect.width());
			hsv[2] = 1.0f - clamp01((event.getY() - saturationValueRect.top) /
					saturationValueRect.height());
		}
		invalidate();
		if (listener != null)
			listener.onColorChanged(getColor());
		if (event.getActionMasked() == MotionEvent.ACTION_UP)
			performClick();
		return true;
	}

	@Override public boolean performClick() {
		super.performClick();
		return true;
	}

	private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
}
