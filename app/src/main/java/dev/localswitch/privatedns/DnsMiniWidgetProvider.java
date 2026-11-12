package dev.localswitch.privatedns;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/** A genuine 1x1 widget with two generous hit areas: power and DNS picker. */
public final class DnsMiniWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateOne(context, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, android.os.Bundle options) {
        updateOne(context, manager, id);
    }

    static void updateAll(Context context) {
        context = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, DnsMiniWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(provider)) updateOne(context, manager, id);
    }

    static void updateOne(Context context, AppWidgetManager manager, int id) {
        context = context.getApplicationContext();
        RemoteViews views = new RemoteViews(context.getPackageName(),
                WidgetThemes.layout(context, id, WidgetThemes.Layout.MINI));
        boolean allowed = DnsSettings.hasPermission(context);
        DnsProfiles.Profile profile = DnsProfiles.selected(context);
        DnsSettings.State state = DnsSettings.getState(context);

        String label;
        WidgetThemes.Status color;
        if (!allowed) {
            label = "SETUP";
            color = WidgetThemes.Status.WARNING;
        } else if (state == DnsSettings.State.ON) {
            label = "ON";
            color = WidgetThemes.Status.SUCCESS;
        } else if (state == DnsSettings.State.OFF) {
            label = "OFF";
            color = WidgetThemes.Status.ERROR;
        } else if (state == DnsSettings.State.OTHER) {
            label = "OTHER";
            color = WidgetThemes.Status.WARNING;
        } else {
            label = "AUTO";
            color = WidgetThemes.Status.WARNING;
        }
        WidgetThemes.status(views, true, color, label);
        views.setTextViewText(R.id.mini_picker_area, profile.name + "  \u25be");

        Intent app = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.setData(Uri.parse("privatednswidget://mini/" + id + "/settings"));
        app.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent setup = PendingIntent.getActivity(context, id + 100000, app,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(context, ToggleReceiver.class);
        toggle.setAction(ToggleReceiver.ACTION_TOGGLE);
        toggle.setData(Uri.parse("privatednswidget://mini/" + id + "/toggle"));
        PendingIntent power = PendingIntent.getBroadcast(context, 0, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.mini_power_area, allowed ? power : setup);

        Intent choose = new Intent(context, ProfilePickerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        choose.setData(Uri.parse("privatednswidget://mini/" + id + "/picker"));
        choose.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent picker = PendingIntent.getActivity(context, 0, choose,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.mini_picker_area, picker);

        manager.updateAppWidget(id, views);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        WidgetThemes.deleted(context, ids);
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        WidgetThemes.restored(context, oldIds, newIds);
    }
}
