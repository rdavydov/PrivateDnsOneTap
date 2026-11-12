package dev.localswitch.privatedns;

import android.app.Application;
import android.content.res.Configuration;

/** Refresh running widgets on real system changes; Activity overrides remain local. */
public final class DnsApplication extends Application {
    private int lastNightMode;

    @Override public void onCreate() {
        super.onCreate();
        lastNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int next = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (lastNightMode != next) {
            lastNightMode = next;
            WidgetThemes.updateSystemWidgets(this);
        }
    }
}
