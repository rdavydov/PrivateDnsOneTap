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
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

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
        Context strings = LanguageSettings.context(context);
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
                    strings.getResources().getQuantityString(R.plurals.widget_server_position,
                            profiles.size(), selectedIndex + 1, profiles.size()));
            views.setTextViewText(R.id.widget_title, strings.getString(R.string.widget_heading));
            views.setTextViewText(R.id.widget_previous, strings.getString(R.string.widget_previous));
            views.setContentDescription(R.id.widget_previous,
                    strings.getString(R.string.widget_previous_description));
        }
        views.setTextViewText(R.id.widget_next, strings.getString(compact
                ? R.string.widget_next_symbol : R.string.widget_next));
        views.setContentDescription(R.id.widget_next,
                strings.getString(R.string.widget_next_description));
        views.setContentDescription(R.id.widget_settings,
                strings.getString(R.string.widget_edit_description));
        views.setContentDescription(R.id.widget_power,
                strings.getString(R.string.widget_toggle_description));

        String status;
        WidgetThemes.Status statusColor;
        String powerLabel;
        if (!permitted) {
            status = strings.getString(compact ? R.string.widget_mini_status_setup
                    : R.string.widget_status_setup);
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = strings.getString(R.string.widget_power_setup);
        } else if (state == DnsSettings.State.ON) {
            status = strings.getString(R.string.widget_status_active);
            statusColor = WidgetThemes.Status.SUCCESS;
            powerLabel = strings.getString(R.string.widget_power_disable);
        } else if (state == DnsSettings.State.OFF) {
            status = strings.getString(R.string.widget_status_off);
            statusColor = WidgetThemes.Status.ERROR;
            powerLabel = strings.getString(R.string.widget_power_enable);
        } else if (state == DnsSettings.State.OTHER) {
            status = strings.getString(R.string.widget_status_other);
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = strings.getString(R.string.widget_power_use);
        } else {
            status = strings.getString(R.string.widget_status_auto);
            statusColor = WidgetThemes.Status.WARNING;
            powerLabel = strings.getString(R.string.widget_power_use);
        }
        WidgetThemes.status(views, false, statusColor, status);
        views.setTextViewText(R.id.widget_power, compact
                ? strings.getString(!permitted ? R.string.widget_mini_status_setup
                    : state == DnsSettings.State.ON ? R.string.widget_power_short_off
                    : state == DnsSettings.State.OFF ? R.string.widget_power_short_on
                    : R.string.widget_power_short_use)
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
