package dev.localswitch.privatedns;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/** Each placed widget has its own theme; the application theme never changes these values. */
final class WidgetThemes {
    private static final String PREFS = "private_dns_widget_themes";
    private static final String KEY_PREFIX = "widget_";

    enum Layout { FULL, COMPACT, MINI }
    enum Status { SUCCESS, ERROR, WARNING }

    private WidgetThemes() { }

    static boolean isOwned(Context context, int appWidgetId) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || appWidgetId < 0) return false;
        Context app = context.getApplicationContext();
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(app).getAppWidgetInfo(appWidgetId);
        return info != null && (new ComponentName(app, DnsWidgetProvider.class).equals(info.provider)
                || new ComponentName(app, DnsMiniWidgetProvider.class).equals(info.provider));
    }

    static ThemeMode mode(Context context, int appWidgetId) {
        Object value = preferences(context).getAll().get(key(appWidgetId));
        return ThemeMode.fromStored(value instanceof String ? (String) value : null);
    }

    static void showDialog(Activity activity, int appWidgetId, Runnable onChanged) {
        if (!isOwned(activity, appWidgetId)) {
            Toast.makeText(activity, R.string.widget_theme_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.widget_theme_title)
                .setSingleChoiceItems(ThemeMode.labels(activity), mode(activity, appWidgetId).ordinal(),
                        (dialog, which) -> {
                            // The launcher may remove the widget while this dialog is open.
                            if (isOwned(activity, appWidgetId)) {
                                preferences(activity).edit().putString(key(appWidgetId),
                                        ThemeMode.values()[which].storedValue()).apply();
                                updateOne(activity.getApplicationContext(), appWidgetId);
                                if (onChanged != null) onChanged.run();
                            } else {
                                Toast.makeText(activity, R.string.widget_theme_unavailable,
                                        Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static int layout(Context context, int appWidgetId, Layout kind) {
        ThemeMode mode = mode(context, appWidgetId);
        boolean night = (context.getApplicationContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        switch (kind) {
            case MINI:
                if (mode == ThemeMode.LIGHT) return R.layout.widget_private_dns_mini_light;
                if (mode == ThemeMode.DARK) return R.layout.widget_private_dns_mini_dark;
                return night ? R.layout.widget_private_dns_mini_system_night
                        : R.layout.widget_private_dns_mini;
            case COMPACT:
                if (mode == ThemeMode.LIGHT) return R.layout.widget_private_dns_compact_light;
                if (mode == ThemeMode.DARK) return R.layout.widget_private_dns_compact_dark;
                return night ? R.layout.widget_private_dns_compact_system_night
                        : R.layout.widget_private_dns_compact;
            default:
                if (mode == ThemeMode.LIGHT) return R.layout.widget_private_dns_light;
                if (mode == ThemeMode.DARK) return R.layout.widget_private_dns_dark;
                return night ? R.layout.widget_private_dns_system_night : R.layout.widget_private_dns;
        }
    }

    static void status(RemoteViews views, boolean mini, Status status, String text) {
        int success = mini ? R.id.mini_status_success : R.id.widget_status_success;
        int error = mini ? R.id.mini_status_error : R.id.widget_status_error;
        int warning = mini ? R.id.mini_status_warning : R.id.widget_status_warning;
        views.setTextViewText(success, text);
        views.setTextViewText(error, text);
        views.setTextViewText(warning, text);
        views.setViewVisibility(success, status == Status.SUCCESS ? View.VISIBLE : View.GONE);
        views.setViewVisibility(error, status == Status.ERROR ? View.VISIBLE : View.GONE);
        views.setViewVisibility(warning, status == Status.WARNING ? View.VISIBLE : View.GONE);
    }

    static void deleted(Context context, int[] ids) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (int id : ids) editor.remove(key(id));
        editor.apply();
    }

    static void restored(Context context, int[] oldIds, int[] newIds) {
        // Snapshot all old values first: a restored ID can overlap another old ID.
        int count = Math.min(oldIds.length, newIds.length);
        ThemeMode[] modes = new ThemeMode[count];
        for (int i = 0; i < count; i++) modes[i] = mode(context, oldIds[i]);
        SharedPreferences.Editor editor = preferences(context).edit();
        for (int id : oldIds) editor.remove(key(id));
        for (int i = 0; i < count; i++) {
            editor.putString(key(newIds[i]), modes[i].storedValue());
        }
        editor.apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context.getApplicationContext());
            for (int id : newIds) {
                Bundle options = new Bundle();
                options.putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true);
                manager.updateAppWidgetOptions(id, options);
            }
        }
    }

    static void updateSystemWidgets(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        for (Class<?> provider : new Class<?>[]{DnsWidgetProvider.class, DnsMiniWidgetProvider.class}) {
            for (int id : manager.getAppWidgetIds(new ComponentName(app, provider))) {
                if (mode(app, id) == ThemeMode.SYSTEM) updateOne(app, id);
            }
        }
    }

    private static void updateOne(Context context, int appWidgetId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(appWidgetId);
        if (info == null) return;
        if (new ComponentName(context, DnsMiniWidgetProvider.class).equals(info.provider)) {
            DnsMiniWidgetProvider.updateOne(context, manager, appWidgetId);
        } else if (new ComponentName(context, DnsWidgetProvider.class).equals(info.provider)) {
            DnsWidgetProvider.updateOne(context, manager, appWidgetId);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(int id) {
        return KEY_PREFIX + id;
    }
}
