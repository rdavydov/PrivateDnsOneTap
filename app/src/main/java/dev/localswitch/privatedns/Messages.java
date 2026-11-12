package dev.localswitch.privatedns;

import android.content.Context;

/** Keeps validation independent of UI contexts while resolving its message in the current language. */
final class Messages {
    private Messages() { }

    static IllegalArgumentException invalid(int resource) {
        return new InvalidInput(resource);
    }

    static String message(Context context, Throwable error) {
        Context localized = LanguageSettings.context(context);
        if (error instanceof InvalidInput) {
            return localized.getString(((InvalidInput) error).resource);
        }
        String detail = error.getMessage();
        return detail == null || detail.isEmpty() ? localized.getString(R.string.error_unknown) : detail;
    }

    private static final class InvalidInput extends IllegalArgumentException {
        final int resource;

        InvalidInput(int resource) {
            super("Invalid input (message resource " + resource + ")");
            this.resource = resource;
        }
    }
}
