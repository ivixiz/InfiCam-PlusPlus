package be.ntmn.inficam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Locale;

/**
 * Persistent presentation metadata for Time Chart traces.
 *
 * Sample storage remains in {@link TimeChartView}; keeping presentation and measurement-setting
 * keys here gives the Android and Web Control editors one extensible source of truth.
 */
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
		public final String measurementSetting;
		public final String name;
		public final float lineWidth;
		public final int color;

		private Trace(String id, String measurementSetting, String name,
				float lineWidth, int color) {
			this.id = id;
			this.measurementSetting = measurementSetting;
			this.name = name;
			this.lineWidth = lineWidth;
			this.color = color;
		}
	}

	private static final Trace[] DEFAULTS = {
			new Trace("max", "showmax", "Tmax", 2.0f, Color.RED),
			new Trace("min", "showmin", "Tmin", 2.0f, Color.BLUE),
			new Trace("center", "showcenter", "Tcenter", 2.0f, Color.RED)
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
					clampWidth(preferences.getFloat(key(fallback.id, "width"),
							fallback.lineWidth)),
					preferences.getInt(key(fallback.id, "color"), fallback.color) | 0xff000000);
		}
		snapshot = loaded;
	}

	public static Trace[] defaults() { return DEFAULTS.clone(); }

	/** Immutable objects and an atomically replaced array make reads allocation-free. */
	public Trace[] snapshot() { return snapshot; }

	public synchronized boolean update(String id, String field, String value) {
		Trace[] current = snapshot;
		int index = indexOf(current, id);
		if (index < 0)
			return false;
		Trace old = current[index];
		String name = old.name;
		float width = old.lineWidth;
		int color = old.color;
		try {
			switch (field) {
				case "name":
					name = sanitizeName(value, DEFAULTS[index].name);
					break;
				case "width":
					width = clampWidth(Float.parseFloat(value));
					break;
				case "color":
					color = parseColor(value);
					break;
				default:
					return false;
			}
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (name.equals(old.name) && width == old.lineWidth && color == old.color)
			return true;
		Trace replacement = new Trace(old.id, old.measurementSetting, name, width, color);
		Trace[] updated = current.clone();
		updated[index] = replacement;
		snapshot = updated;
		SharedPreferences.Editor editor = preferences.edit();
		editor.putString(key(id, "name"), name);
		editor.putFloat(key(id, "width"), width);
		editor.putInt(key(id, "color"), color);
		editor.apply();
		return true;
	}

	public static String colorHex(int color) {
		return String.format(Locale.US, "#%06x", color & 0xffffff);
	}

	private static int indexOf(Trace[] traces, String id) {
		for (int i = 0; i < traces.length; ++i)
			if (traces[i].id.equals(id))
				return i;
		return -1;
	}

	private static String key(String id, String field) { return id + '_' + field; }

	private static String sanitizeName(String value, String fallback) {
		if (value == null)
			return fallback;
		String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
		if (clean.isEmpty())
			return fallback;
		return clean.length() <= MAX_NAME_LENGTH ? clean : clean.substring(0, MAX_NAME_LENGTH);
	}

	private static float clampWidth(float value) {
		if (!Float.isFinite(value))
			throw new IllegalArgumentException("Non-finite line width");
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value));
	}

	private static int parseColor(String value) {
		if (value == null || !value.matches("#[0-9a-fA-F]{6}"))
			throw new IllegalArgumentException("Invalid trace color");
		return Color.parseColor(value) | 0xff000000;
	}
}
