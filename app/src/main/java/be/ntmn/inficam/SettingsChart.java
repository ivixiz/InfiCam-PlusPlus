package be.ntmn.inficam;

import android.content.Context;

/** Chart acquisition/export settings, shown together with the trace table. */
final class SettingsChart extends Settings {
	SettingsChart(Context context) {
		super(context, "PREFS", R.string.chart_properties);
		setOrientation(VERTICAL);
		settings = new Setting[]{
				new SettingFloatInput("chart_sample_rate", R.string.set_chart_sample_rate,
						0.1f, 1.0f / 25.0f, 1800.0f) {
					@Override void onSet(float value) { act.setChartSampleRate(value); }
				},
				new SettingIntInput("chart_average_samples", R.string.set_chart_average_samples,
						1, 1, 16) {
					@Override void onSet(int value) { act.setChartAverageSamples(value); }
				},
				new SettingBool("export_chart_separately", R.string.set_export_chart_separately,
						false) {
					@Override void onSet(boolean value) { act.setExportChartSeparately(value); }
				}
		};
	}
}
