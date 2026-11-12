package dev.localswitch.privatedns;

import android.app.Activity;
import android.content.Context;

/** All screens share the saved app appearance without overriding widget or system resources. */
abstract class ThemedActivity extends Activity {
    private ThemeMode appliedMode;

    @Override
    protected void attachBaseContext(Context base) {
        appliedMode = ThemeSettings.getAppMode(base);
        super.attachBaseContext(ThemeSettings.wrap(base));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedMode != ThemeSettings.getAppMode(this)) recreate();
    }
}
