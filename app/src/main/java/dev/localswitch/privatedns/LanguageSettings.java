package dev.localswitch.privatedns;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

/** Native per-app languages on Android 13+, with a local override on Android 9–12. */
final class LanguageSettings {
    private static final String PREFS = "appearance";
    private static final String KEY = "app_language";
    private static final String MIGRATED = "platform_language_initialized";

    enum Mode {
        SYSTEM("system", "", R.string.language_system),
        RUSSIAN("ru", "ru", R.string.language_russian),
        ENGLISH("en", "en", R.string.language_english);

        final String storedValue;
        final String tag;
        final int labelResource;

        Mode(String storedValue, String tag, int labelResource) {
            this.storedValue = storedValue;
            this.tag = tag;
            this.labelResource = labelResource;
        }

        String label(Context context) { return context.getString(labelResource); }

        static Mode fromStored(Object value) {
            for (Mode mode : values()) if (mode.storedValue.equals(value)) return mode;
            return SYSTEM;
        }
    }

    private LanguageSettings() { }

    static void initialize(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        SharedPreferences prefs = preferences(context);
        if (Boolean.TRUE.equals(prefs.getAll().get(MIGRATED))) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        Mode old = Mode.fromStored(prefs.getAll().get(KEY));
        // Preserve a language chosen before an OS upgrade, without replacing an OS-side choice.
        if (manager.getApplicationLocales().isEmpty() && old != Mode.SYSTEM) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(old.tag));
        }
        prefs.edit().putBoolean(MIGRATED, true).apply();
    }

    static Mode getMode(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            if (locales.isEmpty()) return Mode.SYSTEM;
            return "ru".equals(locales.get(0).getLanguage()) ? Mode.RUSSIAN : Mode.ENGLISH;
        }
        return Mode.fromStored(preferences(context).getAll().get(KEY));
    }

    static void setMode(Context context, Mode mode) {
        preferences(context).edit().putString(KEY, mode.storedValue).apply();
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class)
                    .setApplicationLocales(LocaleList.forLanguageTags(mode.tag));
        }
        DnsWidgetProvider.updateAll(context.getApplicationContext());
    }

    static Context wrap(Context context) {
        Mode mode = getMode(context);
        if (mode == Mode.SYSTEM) return context;
        Configuration override = new Configuration();
        override.setLocales(LocaleList.forLanguageTags(mode.tag));
        return context.createConfigurationContext(override);
    }

    /** Resolve messages from a fresh app context, not from a destroyed Activity or a stale locale. */
    static Context context(Context context) {
        return wrap(context.getApplicationContext());
    }

    static void showDialog(Activity activity, Runnable onChanged) {
        Mode selected = getMode(activity);
        Mode[] modes = Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label(activity);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, selected.ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    if (modes[index] == selected) return;
                    setMode(activity, modes[index]);
                    if (Build.VERSION.SDK_INT < 33) activity.recreate();
                    else if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
