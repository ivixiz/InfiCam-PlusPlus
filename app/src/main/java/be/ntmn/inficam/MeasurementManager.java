package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;

/**
 * Owns user-defined sensor-space measurements. Object count is deliberately bounded, so scans
 * are deterministic and the frame path uses no heap allocations.
 */
final class MeasurementManager {
	enum Tool { NONE, POINT, LINE, RECTANGLE }

	static final int MAX_POINTS = 5;
	static final int MAX_LINES = 3;
	static final int MAX_RECTANGLES = 3;
	private static final int ORANGE = Color.rgb(255, 152, 0);
	private static final int VIOLET = Color.rgb(138, 43, 226);
	private static final int MAGENTA = Color.MAGENTA;
	private static final int CYAN = Color.CYAN;

	interface Listener { void onMeasurementsChanged(); }

	static final class Measurement {
		final int slot;
		final Tool type;
		final String id;
		final String[] traceIds;
		final String[] defaultTraceNames;
		final int[] defaultTraceColors;
		float x1, y1, x2, y2;
		boolean active = true;
		float min = Float.NaN, max = Float.NaN, center = Float.NaN;
		int minX, minY, maxX, maxY, centerX, centerY;

		Measurement(int slot, Tool type, String[] defaultTraceNames, int[] defaultTraceColors) {
			this.slot = slot;
			this.type = type;
			this.id = prefix(type) + slot;
			this.defaultTraceNames = defaultTraceNames;
			this.defaultTraceColors = defaultTraceColors;
			if (type == Tool.POINT) traceIds = new String[]{id + "_value"};
			else if (type == Tool.LINE) traceIds = new String[]{id + "_min", id + "_max"};
			else traceIds = new String[]{id + "_min", id + "_center", id + "_max"};
		}

		void activate(float x1, float y1, float x2, float y2) {
			this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
			min = max = center = Float.NaN;
			active = true;
		}
	}

	private final ArrayList<Measurement> measurements = new ArrayList<>(11);
	private final ChartTraceConfig traces;
	private final Listener listener;
	private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final StringBuilder label = new StringBuilder(16);
	private final float density;
	private final HashSet<Integer> usedColors = new HashSet<>(24);
	private final Random colorRandom = new Random();
	private final ArrayList<Float> usedHues = new ArrayList<>(24);
	private final String[] activeTraceIds = new String[20];
	private float nextHue = colorRandom.nextFloat() * 360.0f;
	private Tool tool = Tool.NONE;
	private boolean preview;
	private float previewX1, previewY1, previewX2, previewY2;
	private Measurement moving;
	private boolean moveChanged;
	private float moveTouchX, moveTouchY;
	private float moveX1, moveY1, moveX2, moveY2;

	MeasurementManager(Context context, ChartTraceConfig traces, Listener listener) {
		this.traces = traces;
		this.listener = listener;
		density = context.getResources().getDisplayMetrics().density;
		shape.setStyle(Paint.Style.STROKE);
		shape.setStrokeCap(Paint.Cap.ROUND);
		shape.setStrokeJoin(Paint.Join.ROUND);
		shape.setColor(Color.WHITE);
		outline.set(shape);
		outline.setColor(Color.BLACK);
		text.setStyle(Paint.Style.FILL);
		textOutline.setStyle(Paint.Style.STROKE);
		textOutline.setColor(Color.BLACK);
		textOutline.setStrokeJoin(Paint.Join.ROUND);
		// Keep generated series distinct from the documented first-object defaults.
		usedColors.add(ORANGE);
		usedColors.add(VIOLET);
		usedColors.add(MAGENTA);
		usedColors.add(CYAN);
		usedHues.add(35.8f);   // orange
		usedHues.add(271.1f);  // violet
		usedHues.add(300.0f);  // magenta
		usedHues.add(180.0f);  // cyan
	}

	synchronized Tool getTool() { return tool; }

	void setTool(Tool next) {
		synchronized (this) {
			tool = next == null ? Tool.NONE : next;
			preview = false;
		}
		notifyChanged();
	}

	boolean begin(float x, float y) {
		synchronized (this) {
			if (tool == Tool.NONE) return false;
			previewX1 = previewX2 = clamp01(x);
			previewY1 = previewY2 = clamp01(y);
			preview = tool != Tool.POINT;
			return true;
		}
	}

	void updatePreview(float x, float y) {
		synchronized (this) {
			if (!preview) return;
			previewX2 = clamp01(x); previewY2 = clamp01(y);
		}
	}

	boolean finish(float x, float y) {
		Tool selected;
		float x1, y1;
		synchronized (this) {
			selected = tool;
			x1 = previewX1; y1 = previewY1;
			preview = false;
		}
		if (selected == Tool.NONE) return false;
		return add(selected, x1, y1, clamp01(x), clamp01(y)) != null;
	}

	void cancelPreview() { synchronized (this) { preview = false; } }

	Measurement add(Tool type, float x1, float y1, float x2, float y2) {
		Measurement added;
		synchronized (this) {
			if (type == null || type == Tool.NONE || atLimit(type)) return null;
			added = findReusable(type);
			if (added == null) {
				int slot = slotCount(type) + 1;
				added = createMeasurement(type, slot);
				measurements.add(added);
			}
			added.activate(clamp01(x1), clamp01(y1), clamp01(x2), clamp01(y2));
			// addCustom() is idempotent: a reused slot keeps its edited name, colour and width.
			registerTraces(added);
		}
		notifyChanged();
		return added;
	}

	boolean remove(String id) {
		boolean changed = false;
		synchronized (this) {
			for (int i = measurements.size() - 1; i >= 0; --i) {
				Measurement item = measurements.get(i);
				if (item.active && item.id.equals(id)) {
					item.active = false;
					item.min = item.max = item.center = Float.NaN;
					if (moving == item) moving = null;
					changed = true;
					break;
				}
			}
		}
		if (changed) notifyChanged();
		return changed;
	}

	/** Whether a custom trace belongs to an object that is still on the thermogram. */
	synchronized boolean isTraceActive(String traceId) {
		for (int i = 0; i < measurements.size(); ++i) {
			Measurement item = measurements.get(i);
			if (!item.active) continue;
			for (int role = 0; role < item.traceIds.length; ++role)
				if (item.traceIds[role].equals(traceId)) return true;
		}
		return false;
	}

	/**
	 * Deactivate objects whose last trace was explicitly deleted in Chart Properties. Keeping
	 * this rule in the measurement owner avoids UI-specific point/line/rectangle branching.
	 */
	boolean deactivateObjectsWithoutTraces() {
		boolean changed = false;
		synchronized (this) {
			for (int i = 0; i < measurements.size(); ++i) {
				Measurement item = measurements.get(i);
				if (!item.active) continue;
				boolean hasTrace = false;
				for (int role = 0; role < item.traceIds.length; ++role) {
					if (traces.find(item.traceIds[role]) != null) {
						hasTrace = true;
						break;
					}
				}
				if (hasTrace) continue;
				item.active = false;
				item.min = item.max = item.center = Float.NaN;
				if (moving == item) moving = null;
				changed = true;
			}
		}
		if (changed) notifyChanged();
		return changed;
	}

	/** Return the stable id of the visually topmost object at a screen coordinate. */
	synchronized String hitAt(float sx, float sy, Rect rect, Overlay.Data data,
			float tolerancePx) {
		for (int i = measurements.size() - 1; i >= 0; --i) {
			Measurement item = measurements.get(i);
			if (item.active && hit(item, sx, sy, rect, data, tolerancePx)) return item.id;
		}
		return null;
	}

	/** Delete the visually topmost object at the supplied screen coordinate. */
	boolean removeAt(float sx, float sy, Rect rect, Overlay.Data data, float tolerancePx) {
		String hit = hitAt(sx, sy, rect, data, tolerancePx);
		return hit != null && remove(hit);
	}

	boolean beginMove(String id, float touchX, float touchY) {
		synchronized (this) {
			moving = null;
			moveChanged = false;
			for (int i = measurements.size() - 1; i >= 0; --i) {
				Measurement item = measurements.get(i);
				if (!item.active || !item.id.equals(id)) continue;
				moving = item;
				moveTouchX = clamp01(touchX); moveTouchY = clamp01(touchY);
				moveX1 = item.x1; moveY1 = item.y1;
				moveX2 = item.x2; moveY2 = item.y2;
				return true;
			}
			return false;
		}
	}

	/** Translate the whole object without changing its shape or letting it leave the sensor. */
	synchronized boolean updateMove(float touchX, float touchY) {
		if (moving == null || !moving.active) return false;
		float dx = clamp01(touchX) - moveTouchX;
		float dy = clamp01(touchY) - moveTouchY;
		dx = Math.max(-Math.min(moveX1, moveX2),
				Math.min(1.0f - Math.max(moveX1, moveX2), dx));
		dy = Math.max(-Math.min(moveY1, moveY2),
				Math.min(1.0f - Math.max(moveY1, moveY2), dy));
		moving.x1 = moveX1 + dx; moving.y1 = moveY1 + dy;
		moving.x2 = moveX2 + dx; moving.y2 = moveY2 + dy;
		moveChanged |= dx != 0.0f || dy != 0.0f;
		return true;
	}

	void finishMove() {
		boolean changed;
		synchronized (this) {
			changed = moving != null && moveChanged;
			moving = null;
			moveChanged = false;
		}
		if (changed) notifyChanged();
	}

	void cancelMove() {
		boolean changed;
		synchronized (this) {
			changed = moving != null && moveChanged;
			if (moving != null) {
				moving.x1 = moveX1; moving.y1 = moveY1;
				moving.x2 = moveX2; moving.y2 = moveY2;
			}
			moving = null;
			moveChanged = false;
		}
		if (changed) notifyChanged();
	}

	boolean setGeometry(String id, float x1, float y1, float x2, float y2) {
		boolean changed = false;
		synchronized (this) {
			for (int i = measurements.size() - 1; i >= 0; --i) {
				Measurement item = measurements.get(i);
				if (!item.active || !item.id.equals(id)) continue;
				item.x1 = clamp01(x1); item.y1 = clamp01(y1);
				item.x2 = clamp01(x2); item.y2 = clamp01(y2);
				changed = true;
				break;
			}
		}
		if (changed) notifyChanged();
		return changed;
	}

	/** Convert a point in the displayed/cropped image back to normalized sensor coordinates. */
	synchronized boolean screenToSensor(float sx, float sy, Rect rect, Overlay.Data data,
			float[] result) {
		if (result == null || result.length < 2 || rect == null || rect.width() <= 0 ||
				rect.height() <= 0 || data.fi == null || data.fi.width <= 0 || data.fi.height <= 0)
			return false;
		float scale = Math.max(1.0f, data.scale);
		float ox = (sx - rect.left + (rect.width() * scale - rect.width()) * 0.5f) /
				(rect.width() * scale);
		float oy = (sy - rect.top + (rect.height() * scale - rect.height()) * 0.5f) /
				(rect.height() * scale);
		if (ox < 0.0f || ox > 1.0f || oy < 0.0f || oy > 1.0f) return false;
		if (data.mirror) ox = 1.0f - ox;
		if (data.rotate) { ox = 1.0f - ox; oy = 1.0f - oy; }
		if (data.rotate90) {
			float rotatedX = ox;
			ox = oy;
			oy = 1.0f - rotatedX;
		}
		result[0] = clamp01(ox); result[1] = clamp01(oy);
		return true;
	}

	/** Compute only requested metrics. Rectangle scans are skipped when both extrema are hidden. */
	synchronized void compute(float[] temperatures, int width, int height) {
		if (temperatures == null || width <= 0 || height <= 0 ||
				temperatures.length < width * height) return;
		for (int i = 0, count = measurements.size(); i < count; ++i) {
			Measurement item = measurements.get(i);
			if (!item.active) continue;
			if (item.type == Tool.POINT) computePoint(item, temperatures, width, height);
			else if (item.type == Tool.LINE) computeLine(item, temperatures, width, height);
			else computeRectangle(item, temperatures, width, height);
		}
	}

	/** Values are matched by stable trace id, so adding/removing objects never reorders history. */
	synchronized void fillTraceValues(ChartTraceConfig.Trace[] styles, float[] values) {
		for (int i = 3; i < styles.length && i < values.length; ++i) {
			values[i] = Float.NaN;
			for (int itemIndex = 0, count = measurements.size(); itemIndex < count; ++itemIndex) {
				Measurement item = measurements.get(itemIndex);
				int role = traceRole(item, styles[i].id);
				if (role < 0) continue;
				if (item.active && styles[i].show) values[i] = valueForRole(item, role);
				break;
			}
		}
	}

	/** Remove legends which have neither an active thermogram measurement nor a new sample. */
	synchronized void pruneInactiveTraces() {
		int count = 0;
		for (int i = 0; i < measurements.size(); ++i) {
			Measurement item = measurements.get(i);
			if (!item.active) continue;
			for (int role = 0; role < item.traceIds.length; ++role)
				activeTraceIds[count++] = item.traceIds[role];
		}
		traces.retainCustomTraces(activeTraceIds, count);
		for (int i = 0; i < count; ++i) activeTraceIds[i] = null;
	}

	synchronized void appendWebJson(StringBuilder json) {
		json.append('[');
		boolean first = true;
		for (Measurement item : measurements) {
			if (!item.active) continue;
			if (!first) json.append(',');
			first = false;
			json.append('{');
			json.append("\"id\":\"").append(item.id).append("\",\"type\":\"")
					.append(toolName(item.type)).append("\",\"x1\":").append(item.x1)
					.append(",\"y1\":").append(item.y1).append(",\"x2\":").append(item.x2)
					.append(",\"y2\":").append(item.y2).append(",\"metrics\":[");
			appendMetricsJson(json, item);
			json.append("]}");
		}
		json.append(']');
	}

	synchronized void draw(Canvas canvas, Overlay.Data data, Rect rect) {
		if (canvas == null || data == null || rect == null || data.fi == null) return;
		float base = data.rotate90 ? rect.height() : rect.width();
		shape.setStrokeWidth(Math.max(0.75f, base * 0.0015f));
		outline.setStrokeWidth(Math.max(1.5f, Math.min(8.0f, base * 0.0045f)));
		text.setTextSize(Math.max(11.52f * density, base * 0.0252f));
		textOutline.setTextSize(text.getTextSize());
		textOutline.setStrokeWidth(Math.max(1.8f, base * 0.0048f));
		for (int i = 0, count = measurements.size(); i < count; ++i) {
			Measurement item = measurements.get(i);
			if (!item.active) continue;
			drawShape(canvas, item, data, rect);
			drawMetrics(canvas, item, data, rect);
		}
		if (preview && tool != Tool.NONE) {
			shape.setColor(Color.WHITE);
			float x1 = screenX(previewX1, previewY1, rect, data);
			float y1 = screenY(previewX1, previewY1, rect, data);
			float x2 = screenX(previewX2, previewY2, rect, data);
			float y2 = screenY(previewX2, previewY2, rect, data);
			canvas.drawLine(x1, y1, x2, y2, outline);
			if (tool == Tool.RECTANGLE)
				canvas.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2),
						Math.max(y1, y2), shape);
			else canvas.drawLine(x1, y1, x2, y2, shape);
		}
	}

	private void computePoint(Measurement item, float[] temp, int width, int height) {
		int x = pixel(item.x1, width), y = pixel(item.y1, height);
		item.centerX = x; item.centerY = y;
		item.center = shown(item.traceIds[0]) ? temp[y * width + x] : Float.NaN;
	}

	private void computeLine(Measurement item, float[] temp, int width, int height) {
		boolean wantMin = shown(item.traceIds[0]), wantMax = shown(item.traceIds[1]);
		if (!wantMin) item.min = Float.NaN;
		if (!wantMax) item.max = Float.NaN;
		if (!wantMin && !wantMax) return;
		int x1 = pixel(item.x1, width), y1 = pixel(item.y1, height);
		int x2 = pixel(item.x2, width), y2 = pixel(item.y2, height);
		item.min = item.max = Float.NaN;
		int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
		int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
		int error = dx + dy;
		int x = x1, y = y1;
		while (true) {
			float value = temp[y * width + x];
			if (wantMin && (!Float.isFinite(item.min) || value < item.min)) {
				item.min = value; item.minX = x; item.minY = y;
			}
			if (wantMax && (!Float.isFinite(item.max) || value > item.max)) {
				item.max = value; item.maxX = x; item.maxY = y;
			}
			if (x == x2 && y == y2) break;
			int twiceError = error * 2;
			if (twiceError >= dy) { error += dy; x += sx; }
			if (twiceError <= dx) { error += dx; y += sy; }
		}
	}

	private void computeRectangle(Measurement item, float[] temp, int width, int height) {
		boolean wantMin = shown(item.traceIds[0]);
		boolean wantCenter = shown(item.traceIds[1]);
		boolean wantMax = shown(item.traceIds[2]);
		int x1 = pixel(item.x1, width), y1 = pixel(item.y1, height);
		int x2 = pixel(item.x2, width), y2 = pixel(item.y2, height);
		int left = Math.min(x1, x2), right = Math.max(x1, x2);
		int top = Math.min(y1, y2), bottom = Math.max(y1, y2);
		item.centerX = (left + right) / 2; item.centerY = (top + bottom) / 2;
		item.center = wantCenter ? temp[item.centerY * width + item.centerX] : Float.NaN;
		item.min = item.max = Float.NaN;
		if (!wantMin && !wantMax) return;
		for (int y = top; y <= bottom; ++y) {
			int index = y * width + left;
			for (int x = left; x <= right; ++x, ++index) {
				float value = temp[index];
				if (wantMin && (!Float.isFinite(item.min) || value < item.min)) {
					item.min = value; item.minX = x; item.minY = y;
				}
				if (wantMax && (!Float.isFinite(item.max) || value > item.max)) {
					item.max = value; item.maxX = x; item.maxY = y;
				}
			}
		}
	}

	private void registerTraces(Measurement item) {
		for (int i = 0; i < item.traceIds.length; ++i)
			traces.addCustom(item.traceIds[i], item.defaultTraceNames[i],
					item.defaultTraceColors[i]);
	}

	private Measurement createMeasurement(Tool type, int slot) {
		String suffix = Integer.toString(slot);
		if (type == Tool.POINT) {
			return new Measurement(slot, type, new String[]{"Tp" + suffix},
					new int[]{slot == 1 ? ORANGE : uniqueColor()});
		}
		if (type == Tool.LINE) {
			return new Measurement(slot, type,
					new String[]{"Tlmin" + suffix, "Tlmax" + suffix},
					new int[]{slot == 1 ? VIOLET : uniqueColor(),
							slot == 1 ? MAGENTA : uniqueColor()});
		}
		return new Measurement(slot, type,
				new String[]{"Trmin" + suffix, "Trcen" + suffix, "Trmax" + suffix},
				new int[]{slot == 1 ? VIOLET : uniqueColor(),
						slot == 1 ? CYAN : uniqueColor(),
						slot == 1 ? MAGENTA : uniqueColor()});
	}

	/** Reuse the most recently removed slot, keeping its chart series and custom style. */
	private Measurement findReusable(Tool type) {
		for (int i = measurements.size() - 1; i >= 0; --i) {
			Measurement item = measurements.get(i);
			if (!item.active && item.type == type) return item;
		}
		return null;
	}

	private int slotCount(Tool type) {
		int count = 0;
		for (Measurement item : measurements) if (item.type == type) count++;
		return count;
	}

	/** Randomized golden-angle hues stay visually separated and never duplicate an ARGB value. */
	private int uniqueColor() {
		for (int attempt = 0; attempt < 720; ++attempt) {
			nextHue = (nextHue + 137.50776f) % 360.0f;
			if (!hueSeparated(nextHue, 14.0f)) continue;
			int color = Color.HSVToColor(new float[]{nextHue, 0.78f, 1.0f}) | 0xff000000;
			if (usedColors.add(color)) {
				usedHues.add(nextHue);
				return color;
			}
		}
		// Practically unreachable; still guarantees progress without an unbounded retry loop.
		int color = 0xff000001;
		while (!usedColors.add(color)) color++;
		return color;
	}

	private boolean hueSeparated(float hue, float minimumDegrees) {
		for (int i = 0; i < usedHues.size(); ++i) {
			float distance = Math.abs(hue - usedHues.get(i));
			if (Math.min(distance, 360.0f - distance) < minimumDegrees) return false;
		}
		return true;
	}

	private void appendMetricsJson(StringBuilder json, Measurement item) {
		for (int role = 0; role < item.traceIds.length; ++role) {
			if (role != 0) json.append(',');
			int x = roleX(item, role), y = roleY(item, role);
			json.append("{\"trace\":\"").append(item.traceIds[role])
					.append("\",\"value\":");
			float value = valueForRole(item, role);
			if (Float.isFinite(value)) json.append(value); else json.append("null");
			json.append(",\"x\":").append(x).append(",\"y\":").append(y).append('}');
		}
	}

	private void drawShape(Canvas canvas, Measurement item, Overlay.Data data, Rect rect) {
		if (item.type == Tool.POINT) return;
		shape.setColor(Color.WHITE);
		float x1 = screenX(item.x1, item.y1, rect, data);
		float y1 = screenY(item.x1, item.y1, rect, data);
		float x2 = screenX(item.x2, item.y2, rect, data);
		float y2 = screenY(item.x2, item.y2, rect, data);
		if (item.type == Tool.LINE) {
			canvas.drawLine(x1, y1, x2, y2, outline);
			canvas.drawLine(x1, y1, x2, y2, shape);
		} else {
			float left = Math.min(x1, x2), right = Math.max(x1, x2);
			float top = Math.min(y1, y2), bottom = Math.max(y1, y2);
			canvas.drawRect(left, top, right, bottom, outline);
			canvas.drawRect(left, top, right, bottom, shape);
		}
	}

	private void drawMetrics(Canvas canvas, Measurement item, Overlay.Data data, Rect rect) {
		for (int role = 0; role < item.traceIds.length; ++role) {
			ChartTraceConfig.Trace trace = traces.find(item.traceIds[role]);
			float value = valueForRole(item, role);
			if (trace == null || !trace.show || !Float.isFinite(value)) continue;
			drawPoint(canvas, data, rect, roleX(item, role), roleY(item, role), value, trace.color);
		}
	}

	private void drawPoint(Canvas canvas, Overlay.Data data, Rect rect, int px, int py,
			float value, int color) {
		float nx = (px + 0.5f) / Math.max(1, data.fi.width);
		float ny = (py + 0.5f) / Math.max(1, data.fi.height);
		float x = screenX(nx, ny, rect, data), y = screenY(nx, ny, rect, data);
		float marker = Math.max(3.0f, rect.width() * 0.009f);
		shape.setColor(color);
		canvas.drawLine(x - marker, y, x + marker, y, outline);
		canvas.drawLine(x, y - marker, x, y + marker, outline);
		canvas.drawLine(x - marker, y, x + marker, y, shape);
		canvas.drawLine(x, y - marker, x, y + marker, shape);
		Util.formatTemp(label, value, data.tempUnit);
		float offset = rect.width() * 0.02592f;
		float textWidth = text.measureText(label, 0, label.length());
		boolean left = x + offset + textWidth > rect.right;
		float tx = x + (left ? -offset : offset);
		float ty = Math.max(rect.top - text.ascent(), Math.min(rect.bottom - text.descent(), y));
		text.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
		textOutline.setTextAlign(text.getTextAlign());
		text.setColor(color);
		canvas.drawText(label, 0, label.length(), tx, ty, textOutline);
		canvas.drawText(label, 0, label.length(), tx, ty, text);
	}

	private boolean hit(Measurement item, float x, float y, Rect rect, Overlay.Data data,
			float tolerance) {
		float ax = screenX(item.x1, item.y1, rect, data);
		float ay = screenY(item.x1, item.y1, rect, data);
		if (item.type == Tool.POINT) return distanceSquared(x, y, ax, ay) <= tolerance * tolerance;
		float bx = screenX(item.x2, item.y2, rect, data);
		float by = screenY(item.x2, item.y2, rect, data);
		if (item.type == Tool.LINE)
			return segmentDistanceSquared(x, y, ax, ay, bx, by) <= tolerance * tolerance;
		return x >= Math.min(ax, bx) - tolerance && x <= Math.max(ax, bx) + tolerance &&
				y >= Math.min(ay, by) - tolerance && y <= Math.max(ay, by) + tolerance;
	}

	private float screenX(float nx, float ny, Rect rect, Overlay.Data data) {
		float ox = data.rotate90 ? 1.0f - ny : nx;
		float scale = Math.max(1.0f, data.scale);
		float x = ox * rect.width() * scale;
		if (data.rotate) x = rect.width() * scale - x;
		if (data.mirror) x = rect.width() * scale - x;
		return rect.left + x - (rect.width() * scale - rect.width()) * 0.5f;
	}

	private float screenY(float nx, float ny, Rect rect, Overlay.Data data) {
		float oy = data.rotate90 ? nx : ny;
		float scale = Math.max(1.0f, data.scale);
		float y = oy * rect.height() * scale;
		if (data.rotate) y = rect.height() * scale - y;
		return rect.top + y - (rect.height() * scale - rect.height()) * 0.5f;
	}

	private boolean atLimit(Tool type) {
		int count = 0;
		for (Measurement item : measurements) if (item.active && item.type == type) count++;
		return count >= (type == Tool.POINT ? MAX_POINTS :
				type == Tool.LINE ? MAX_LINES : MAX_RECTANGLES);
	}

	private boolean shown(String id) {
		ChartTraceConfig.Trace trace = traces.find(id);
		return trace != null && trace.show;
	}

	private static int traceRole(Measurement item, String id) {
		for (int i = 0; i < item.traceIds.length; ++i) if (item.traceIds[i].equals(id)) return i;
		return -1;
	}

	private static float valueForRole(Measurement item, int role) {
		if (item.type == Tool.POINT) return item.center;
		if (item.type == Tool.LINE) return role == 0 ? item.min : item.max;
		return role == 0 ? item.min : role == 1 ? item.center : item.max;
	}

	private static int roleX(Measurement item, int role) {
		if (item.type == Tool.POINT) return item.centerX;
		if (item.type == Tool.LINE) return role == 0 ? item.minX : item.maxX;
		return role == 0 ? item.minX : role == 1 ? item.centerX : item.maxX;
	}

	private static int roleY(Measurement item, int role) {
		if (item.type == Tool.POINT) return item.centerY;
		if (item.type == Tool.LINE) return role == 0 ? item.minY : item.maxY;
		return role == 0 ? item.minY : role == 1 ? item.centerY : item.maxY;
	}

	private static int pixel(float normalized, int size) {
		return Math.max(0, Math.min(size - 1, Math.round(normalized * (size - 1))));
	}

	private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
	private static String prefix(Tool type) {
		return type == Tool.POINT ? "p" : type == Tool.LINE ? "l" : "r";
	}
	static String toolName(Tool type) { return type.name().toLowerCase(Locale.US); }

	private static float distanceSquared(float x1, float y1, float x2, float y2) {
		float dx = x1 - x2, dy = y1 - y2; return dx * dx + dy * dy;
	}

	private static float segmentDistanceSquared(float px, float py, float ax, float ay,
			float bx, float by) {
		float dx = bx - ax, dy = by - ay;
		float length = dx * dx + dy * dy;
		float t = length <= 0.0f ? 0.0f : ((px - ax) * dx + (py - ay) * dy) / length;
		t = Math.max(0.0f, Math.min(1.0f, t));
		return distanceSquared(px, py, ax + t * dx, ay + t * dy);
	}

	private void notifyChanged() { if (listener != null) listener.onMeasurementsChanged(); }
}
