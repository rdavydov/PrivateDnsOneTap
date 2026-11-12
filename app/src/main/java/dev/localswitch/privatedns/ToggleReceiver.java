package dev.localswitch.privatedns;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Private broadcast target for widget button clicks; performs no background work. */
public final class ToggleReceiver extends BroadcastReceiver {
    static final String ACTION_TOGGLE = "dev.localswitch.privatedns.TOGGLE";
    static final String ACTION_PREVIOUS = "dev.localswitch.privatedns.PREVIOUS";
    static final String ACTION_NEXT = "dev.localswitch.privatedns.NEXT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        try {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) DnsSettings.toggle(context);
            else if (ACTION_PREVIOUS.equals(action)) DnsSettings.cycleAndApply(context, -1);
            else if (ACTION_NEXT.equals(action)) DnsSettings.cycleAndApply(context, 1);
            else return;
        } catch (RuntimeException exception) {
            Context localized = LanguageSettings.context(context);
            Toast.makeText(localized, localized.getString(R.string.dns_operation_error,
                            Messages.message(localized, exception)),
                    Toast.LENGTH_LONG).show();
        } finally {
            DnsWidgetProvider.updateAll(context);
        }
    }
}
