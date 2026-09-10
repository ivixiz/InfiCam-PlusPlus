package be.ntmn.inficam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Arrays;
import java.util.Locale;

/** Thread-safe trace registry shared by the Android chart, measurements and Web Control. */
public final class ChartTraceConfig {
	public static final int MAX = 0;
	public static final int MIN = 1;
	public static final int CENTER = 2;
	private static final String PREFS_NAME = "PREFS_CHART_TRACES";
	private static final float MIN_WIDTH = 0.25f;
	private static final float MAX_WIDTH = 16.0f;
	private static final int MAX_NAME_LENGTH = 48;

	public static final class Trace {
		public final String id;
		/** Non-null only for the three global Measurement Settings switches. */
		public final String measurementSetting;
		public final String name;
		public final float lineWidth;
		public final int color;
		public final boolean show;
		public final boolean builtIn;

		private Trace(String id, String measurementSetting, String name, float lineWidth,
				int color, boolean show, boolean builtIn) {
			this.id = id;
			this.measurementSetting = measurementSetting;
			this.name = name;
			this.lineWidth = lineWidth;
			this.color = color;
			this.show = show;
			this.builtIn = builtIn;
		}
	}

	private static final Trace[] DEFAULTS = {
			new Trace("max", "showmax", "Tmax", 2.0f, Color.RED, true, true),
			new Trace("min", "showmin", "Tmin", 2.0f, Color.BLUE, true, true),
			new Trace("center", "showcenter", "Tcenter", 2.0f, Color.RED, true, true)
	};

	private final SharedPreferences preferences;
	private volatile Trace[] snapshot;

	public ChartTraceConfig(Context context) {
		preferences = context.getApplicationContext().getSharedPreferences(
				PREFS_NAME, Context.MODE_PRIVATE);
		Trace[] loaded = new Trace[DEFAULTS.length];
		for (int i = 0; i < loaded.length; ++i) {
			Trace fallback = DEFAULTS[i];
			loaded[i] = new Trace(fallback.id, fallback.measurementSetting,
					sanitizeName(preferences.getString(key(fallback.id, "name"), fallback.name),
							fallback.name),
					clampWidth(preferences.getFloat(key(fallback.id, "width"), fallback.lineWidth)),
					preferences.getInt(key(fallback.id, "color"), fallback.color) | 0xff000000,
					fallback.show, true);
		}
		snapshot = loaded;
	}

	public static Trace[] defaults() { return DEFAULTS.clone(); }
	/** Immutable objects and an atomically replaced array make frame reads allocation-free. */
	public Trace[] snapshot() { return snapshot; }

	public synchronized Trace addCustom(String id, String name, int color) {
		Trace[] current = snapshot;
		int existing = indexOf(current, id);
		if (existing >= 0) return current[existing];
		Trace trace = new Trace(id, null, sanitizeName(name, "T"), 2.0f,
				color | 0xff000000, true, false);
		Trace[] updated = Arrays.copyOf(current, current.length + 1);
		updated[current.length] = trace;
		snapshot = updated;
		return trace;
	}

	/** Remove historical object traces when a chart is explicitly deleted. */
	public synchronized void removeCustomTraces() {
		Trace[] current = snapshot;
		int count = 0;
		for (Trace trace : current) if (trace.builtIn) count++;
		Trace[] updated = new Trace[count];
		int out = 0;
		for (Trace trace : current) if (trace.builtIn) updated[out++] = trace;
		snapshot = updated;
	}

	/** Permanently remove one user-created trace and its chart series. */
	public synchronized boolean removeCustomTrace(String id) {
		Trace[] current = snapshot;
		int index = indexOf(current, id);
		if (index < 0 || current[index].builtIn) return false;
		Trace[] updated = new Trace[current.length - 1];
		System.arraycopy(current, 0, updated, 0, index);
		System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
		snapshot = updated;
		return true;
	}

	/** Drop stale object traces in one atomic array replacement when a new chart begins. */
	public synchronized void retainCustomTraces(String[] activeIds, int activeCount) {
		Trace[] current = snapshot;
		int retained = 0;
		for (Trace trace : current)
			if (trace.builtIn || contains(activeIds, activeCount, trace.id)) retained++;
		if (retained == current.length) return;
		Trace[] updated = new Trace[retained];
		int out = 0;
		for (Trace trace : current)
			if (trace.builtIn || contains(activeIds, activeCount, trace.id))
				updated[out++] = trace;
		snapshot = updated;
	}

	public synchronized boolean setVisible(String id, boolean visible) {
		Trace[] current = snapshot;
		int index = indexOf(current, id);
		if (index < 0) return false;
		Trace old = current[index];
		if (old.show == visible) return true;
		replace(current, index, new Trace(old.id, old.measurementSetting, old.name,
				old.lineWidth, old.color, visible, old.builtIn));
		return true;
	}

	public synchronized boolean update(String id, String field, String value) {
		Trace[] current = snapshot;
		int index = indexOf(current, id);
		if (index < 0) return false;
		Trace old = current[index];
		String name = old.name;
		float width = old.lineWidth;
		int color = old.color;
		boolean show = old.show;
		try {
			switch (field) {
				case "name": name = sanitizeName(value, old.name); break;
				case "width": width = clampWidth(Float.parseFloat(value)); break;
				case "color": color = parseColor(value); break;
				case "show": show = Boolean.parseBoolean(value); break;
				default: return false;
			}
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (name.equals(old.name) && width == old.lineWidth && color == old.color &&
				show == old.show) return true;
		Trace replacement = new Trace(old.id, old.measurementSetting, name, width, color,
				show, old.builtIn);
		replace(current, index, replacement);
		if (old.builtIn && !"show".equals(field)) {
			preferences.edit().putString(key(id, "name"), name)
					.putFloat(key(id, "width"), width).putInt(key(id, "color"), color).apply();
		}
		return true;
	}

	public Trace find(String id) {
		Trace[] traces = snapshot;
		int index = indexOf(traces, id);
		return index < 0 ? null : traces[index];
	}

	public static String colorHex(int color) {
		return String.format(Locale.US, "#%06x", color & 0xffffff);
	}

	private void replace(Trace[] current, int index, Trace trace) {
		Trace[] updated = current.clone();
		updated[index] = trace;
		snapshot = updated;
	}

	private static int indexOf(Trace[] traces, String id) {
		for (int i = 0; i < traces.length; ++i)
			if (traces[i].id.equals(id)) return i;
		return -1;
	}

	private static boolean contains(String[] values, int count, String value) {
		for (int i = 0; i < count; ++i) if (value.equals(values[i])) return true;
		return false;
	}

	private static String key(String id, String field) { return id + '_' + field; }

	private static String sanitizeName(String value, String fallback) {
		if (value == null) return fallback;
		String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
		if (clean.isEmpty()) return fallback;
		return clean.length() <= MAX_NAME_LENGTH ? clean : clean.substring(0, MAX_NAME_LENGTH);
	}

	private static float clampWidth(float value) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite line width");
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value));
	}

	private static int parseColor(String value) {
		if (value == null || !value.matches("#[0-9a-fA-F]{6}"))
			throw new IllegalArgumentException("Invalid trace color");
		return Color.parseColor(value) | 0xff000000;
	}
}
