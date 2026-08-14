package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

public class SettingsPalette extends Settings {

	public int[] paletteMap;
	public Bitmap paletteBitmap;
	private final SettingPalette settingPalette = new SettingPalette();
	private boolean automatic = true;
	private final SettingFloatInput paletteMin = new SettingFloatInput(
			"palette_min", R.string.set_palette_min, 0.0f, -1000.0f, 10000.0f) {
		@Override void onSet(float value) {
			if (!automatic) act.setPaletteMin(value);
		}
		@Override void onSetByUser(float value) {
			setAutomatic(false);
			act.setPaletteMin(value);
		}
	};
	private final SettingFloatInput paletteMax = new SettingFloatInput(
			"palette_max", R.string.set_palette_max, 100.0f, -1000.0f, 10000.0f) {
		@Override void onSet(float value) {
			if (!automatic) act.setPaletteMax(value);
		}
		@Override void onSetByUser(float value) {
			setAutomatic(false);
			act.setPaletteMax(value);
		}
	};
	private final SettingButton paletteAuto = new SettingButton(R.string.set_palette_auto) {
		@Override void onPress() { setAutomatic(true); }
	};

	public SettingsPalette(Context context) {
		super(context, "PREFS_PALETTE", R.string.dialog_set_palette);
		init();
	}

	public SettingsPalette(Context context, @Nullable AttributeSet attrs) {
		super(context, "PREFS_PALETTE", R.string.dialog_set_palette, attrs);
		init();
	}

	public SettingsPalette(
		Context context,
		@Nullable AttributeSet attrs,
		int defStyleAttr
	) {
		super(
			context,
			"PREFS_PALETTE",
			R.string.dialog_set_palette,
			attrs,
			defStyleAttr
		);
		init();
	}

	private void init() {
		settings = new Setting[] { settingPalette, paletteMin, paletteMax, paletteAuto, settingDefaults };
	}

	@Override
	public void load() {
		automatic = sp.getBoolean("palette_auto", true);
		super.load();
		if (automatic) act.setPaletteAuto();
	}

	@Override
	public void setDefaults() {
		super.setDefaults();
		setAutomatic(true);
	}

	private void setAutomatic(boolean value) {
		automatic = value;
		ed.putBoolean("palette_auto", value).commit();
		if (value) act.setPaletteAuto();
	}

	void setManualRangeMode() { setAutomatic(false); }
	void setAutoRangeMode() { setAutomatic(true); }
	void setManualValues(float min, float max) {
		setManualRangeMode();
		ed.putFloat("palette_min", min).putFloat("palette_max", max).commit();
	}

	public class SettingPalette extends SettingRadio {
		SettingPalette() {
			super("palette", R.string.set_palette, 6, new int[] {});
			items = new int[Palette.palettes.length];
			for (int i = 0; i < Palette.palettes.length; ++i)
				items[i] = Palette.palettes[i].name;
		}

		@Override
		void onSet(int i) {
			paletteMap = Palette.palettes[i].getMap();
			paletteBitmap = Palette.palettes[i].getBitmap();
		}
	}

	SettingPalette getPalette(){ return settingPalette; }
}
