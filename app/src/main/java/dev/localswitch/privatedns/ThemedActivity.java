package dev.localswitch.privatedns;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

/** All screens share the saved app appearance without overriding widget or system resources. */
abstract class ThemedActivity extends Activity {
    private ThemeMode appliedMode;
    private LanguageSettings.Mode appliedLanguage;

    @Override
    protected void attachBaseContext(Context base) {
        appliedMode = ThemeSettings.getAppMode(base);
        appliedLanguage = LanguageSettings.getMode(base);
        // On Android 13+, framework locales must remain unpinned so external app-language
        // changes reach this Activity. On older Android, combine locale/night overrides once:
        // wrapping two configuration contexts can discard the first context's overrides.
        Configuration overrides = new Configuration();
        boolean changed = false;
        if (Build.VERSION.SDK_INT < 33 && appliedLanguage != LanguageSettings.Mode.SYSTEM) {
            overrides.setLocales(LocaleList.forLanguageTags(appliedLanguage.tag));
            changed = true;
        }
        if (appliedMode != ThemeMode.SYSTEM) {
            overrides.uiMode = appliedMode == ThemeMode.DARK
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            changed = true;
        }
        super.attachBaseContext(changed ? base.createConfigurationContext(overrides) : base);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedMode != ThemeSettings.getAppMode(this)
                || appliedLanguage != LanguageSettings.getMode(this)) recreate();
    }
}
