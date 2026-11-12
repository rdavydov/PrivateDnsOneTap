package dev.localswitch.privatedns;

import android.app.Application;
import android.content.res.Configuration;

/** Refresh running widgets on real system changes; Activity overrides remain local. */
public final class DnsApplication extends Application {
    private int lastNightMode;
    private String lastLocales;

    @Override public void onCreate() {
        super.onCreate();
        LanguageSettings.initialize(this);
        lastNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        lastLocales = getResources().getConfiguration().getLocales().toLanguageTags();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int next = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String locales = newConfig.getLocales().toLanguageTags();
        if (!locales.equals(lastLocales)) {
            lastLocales = locales;
            lastNightMode = next;
            DnsWidgetProvider.updateAll(this);
        } else if (lastNightMode != next) {
            lastNightMode = next;
            WidgetThemes.updateSystemWidgets(this);
        }
    }
}
