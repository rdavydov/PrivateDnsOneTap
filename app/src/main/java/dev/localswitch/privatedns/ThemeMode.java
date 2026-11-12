package dev.localswitch.privatedns;

import android.content.Context;

import java.util.Locale;

/** Shared choice names; application and widget selections are stored independently. */
enum ThemeMode {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);

    private final int labelResource;

    ThemeMode(int labelResource) {
        this.labelResource = labelResource;
    }

    String label(Context context) {
        return context.getString(labelResource);
    }

    String storedValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ThemeMode fromStored(String value) {
        if (value != null) {
            for (ThemeMode mode : values()) {
                if (mode.storedValue().equals(value)) return mode;
            }
        }
        return SYSTEM;
    }

    static String[] labels(Context context) {
        ThemeMode[] modes = values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label(context);
        return labels;
    }
}
