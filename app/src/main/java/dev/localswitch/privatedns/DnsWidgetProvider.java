package dev.localswitch.privatedns;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

/** Native Nova-compatible home-screen widget: power, previous, next, settings. */
public final class DnsWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) updateOne(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, android.os.Bundle options) {
        updateOne(context, manager, appWidgetId);
    }

    static void updateAll(Context context) {
        context = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, DnsWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(component)) updateOne(context, manager, id);
        DnsMiniWidgetProvider.updateAll(context);
    }

    static void updateOne(Context context, AppWidgetManager manager, int id) {
        context = context.getApplicationContext();
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options == null ? 320
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
        int height = options == null ? 130
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 130);
        // Some launchers report zero before their first size negotiation.
        if (width <= 0) width = 320;
        if (height <= 0) height = 130;
        boolean compact = width < 240 || height < 100;
        RemoteViews views = new RemoteViews(context.getPackageName(),
                WidgetThemes.layout(context, id, compact ? WidgetThemes.Layout.COMPACT
                        : WidgetThemes.Layout.FULL));
        boolean permitted = DnsSettings.hasPermission(context);
        DnsProfiles.Profile selected = DnsProfiles.selected(context);
        List<DnsProfiles.Profile> profiles = DnsProfiles.all(context);
        int selectedIndex = DnsProfiles.selectedIndex(context);
        DnsSettings.State state = DnsSettings.getState(context);

        views.setTextViewText(R.id.widget_profile, selected.name);
        views.setTextViewText(R.id.widget_hostname, selected.hostname);
        if (!compact) {
            views.setTextViewText(R.id.widget_position,
                    (selectedIndex + 1) + " / " + profiles.size() + " servers");
        }

        String status;
        WidgetThemes.Status statusColor;
        String powerLabel;
        if (!permitted) {
            status = "SETUP NEEDED";
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = "SETUP";
        } else if (state == DnsSettings.State.ON) {
            status = "ACTIVE";
            statusColor = WidgetThemes.Status.SUCCESS;
            powerLabel = "TURN OFF";
        } else if (state == DnsSettings.State.OFF) {
            status = "OFF";
            statusColor = WidgetThemes.Status.ERROR;
            powerLabel = "TURN ON";
        } else if (state == DnsSettings.State.OTHER) {
            status = "OTHER DNS";
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = "USE THIS";
        } else {
            status = "AUTO";
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = "USE THIS";
        }
        WidgetThemes.status(views, false, statusColor, status);
        views.setTextViewText(R.id.widget_power, compact
                ? (!permitted ? "SETUP" : state == DnsSettings.State.ON ? "OFF"
                    : state == DnsSettings.State.OFF ? "ON" : "USE")
                : powerLabel);

        Intent openApp = new Intent(context, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openApp.setData(Uri.parse("privatednswidget://widget/" + id + "/settings"));
        openApp.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent settingsIntent = PendingIntent.getActivity(context, id, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_settings, settingsIntent);

        views.setOnClickPendingIntent(R.id.widget_power,
                permitted ? actionIntent(context, id, ToggleReceiver.ACTION_TOGGLE) : settingsIntent);
        if (!compact) {
            views.setOnClickPendingIntent(R.id.widget_previous,
                    permitted ? actionIntent(context, id, ToggleReceiver.ACTION_PREVIOUS) : settingsIntent);
        }
        views.setOnClickPendingIntent(R.id.widget_next,
                permitted ? actionIntent(context, id, ToggleReceiver.ACTION_NEXT) : settingsIntent);
        // Tap on the profile itself also advances to the next provider.
        views.setOnClickPendingIntent(R.id.widget_profile_area,
                permitted ? actionIntent(context, id, ToggleReceiver.ACTION_NEXT) : settingsIntent);

        manager.updateAppWidget(id, views);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        WidgetThemes.deleted(context, ids);
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        WidgetThemes.restored(context, oldIds, newIds);
    }

    private static PendingIntent actionIntent(Context context, int widgetId, String action) {
        Intent command = new Intent(context, ToggleReceiver.class);
        command.setAction(action);
        // Distinct URI identities prevent different widget buttons/widgets sharing PendingIntents.
        command.setData(Uri.parse("privatednswidget://widget/" + widgetId + "/" + action));
        return PendingIntent.getBroadcast(context, 0, command,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
