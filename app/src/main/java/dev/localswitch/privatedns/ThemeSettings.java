package dev.localswitch.privatedns;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;

/** App-only appearance overrides; application and launcher contexts keep the system configuration. */
final class ThemeSettings {
    private static final String PREFERENCES = "appearance";
    private static final String APP_MODE = "app_theme";

    private ThemeSettings() { }

    static ThemeMode getAppMode(Context context) {
        Object value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getAll().get(APP_MODE);
        return ThemeMode.fromStored(value instanceof String ? (String) value : null);
    }

    static void setAppMode(Context context, ThemeMode mode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(APP_MODE, mode.storedValue()).apply();
    }

    static Context wrap(Context context) {
        ThemeMode mode = getAppMode(context);
        if (mode == ThemeMode.SYSTEM) return context;
        // Override only night mode. Copying the whole configuration would also pin orientation,
        // locale, density and font scale, preventing this context from following later changes.
        Configuration configuration = new Configuration();
        configuration.uiMode = mode == ThemeMode.DARK ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(configuration);
    }

    static void showAppThemeDialog(Activity activity) {
        ThemeMode selected = getAppMode(activity);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.app_theme_title)
                .setSingleChoiceItems(ThemeMode.labels(activity), selected.ordinal(), (dialog, index) -> {
                    ThemeMode mode = ThemeMode.values()[index];
                    dialog.dismiss();
                    if (mode != selected) {
                        setAppMode(activity, mode);
                        activity.recreate();
                    }
                })
                .setNegativeButton(R.string.theme_cancel, null)
                .show();
    }
}
