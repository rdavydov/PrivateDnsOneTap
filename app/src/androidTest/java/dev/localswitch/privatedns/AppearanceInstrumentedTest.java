package dev.localswitch.privatedns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;

/** Exercises actual framework inflation and widget identity, rather than just palette helpers. */
@RunWith(AndroidJUnit4.class)
public final class AppearanceInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences appearance;
    private SharedPreferences widgetPreferences;
    private Object savedAppMode;
    private final Map<String, Object> savedWidgetModes = new HashMap<>();
    private AppWidgetHost host;
    private int fullWidget;
    private int miniWidget;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        widgetPreferences = context.getSharedPreferences("private_dns_widget_themes", Context.MODE_PRIVATE);
        savedAppMode = appearance.getAll().get("app_theme");
    }

    @After public void tearDown() {
        if (appearance != null) restore(appearance, "app_theme", savedAppMode);
        if (widgetPreferences != null) {
            for (Map.Entry<String, Object> entry : savedWidgetModes.entrySet()) {
                restore(widgetPreferences, entry.getKey(), entry.getValue());
            }
        }
        if (host != null) host.deleteHost();
        if (Build.VERSION.SDK_INT >= 29 && instrumentation != null) {
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @Test public void missingAndInvalidModesUseSystemAndAppChoiceIsIndependent() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("invalid-mode"));
        appearance.edit().remove("app_theme").commit();
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.getAppMode(context));
        appearance.edit().putInt("app_theme", 7).commit();
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.getAppMode(context));

        int first = 2_000_000_001;
        int second = 2_000_000_002;
        rememberWidget(first);
        rememberWidget(second);
        widgetPreferences.edit().remove(key(first)).putInt(key(second), 9).commit();
        assertEquals(ThemeMode.SYSTEM, WidgetThemes.mode(context, first));
        assertEquals(ThemeMode.SYSTEM, WidgetThemes.mode(context, second));
        putMode(first, ThemeMode.DARK);
        putMode(second, ThemeMode.LIGHT);
        for (ThemeMode appMode : ThemeMode.values()) {
            ThemeSettings.setAppMode(context, appMode);
            assertEquals(appMode, ThemeSettings.getAppMode(context));
            assertEquals(ThemeMode.DARK, WidgetThemes.mode(context, first));
            assertEquals(ThemeMode.LIGHT, WidgetThemes.mode(context, second));
        }
    }

    @Test public void applicationOverrideKeepsSystemAndOtherConfigurationFields() {
        for (boolean systemNight : new boolean[]{false, true}) {
            Context system = configured(systemNight);
            Configuration before = new Configuration(system.getResources().getConfiguration());
            for (ThemeMode mode : ThemeMode.values()) {
                ThemeSettings.setAppMode(context, mode);
                Context themed = ThemeSettings.wrap(system);
                Configuration after = themed.getResources().getConfiguration();
                boolean expectedNight = mode == ThemeMode.SYSTEM ? systemNight : mode == ThemeMode.DARK;
                assertEquals(expectedNight ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
                        after.uiMode & Configuration.UI_MODE_NIGHT_MASK);
                assertEquals(before.orientation, after.orientation);
                assertEquals(before.densityDpi, after.densityDpi);
                assertEquals(before.fontScale, after.fontScale, 0.0001f);
                assertEquals(before.getLocales(), after.getLocales());
                assertEquals(before.uiMode, system.getResources().getConfiguration().uiMode);
                if (mode == ThemeMode.SYSTEM) assertSame(system, themed);
            }
        }
    }

    @Test public void allRemoteViewLayoutsResolveTheirOwnPaletteInEitherSystemTheme() {
        int id = 2_000_000_003;
        rememberWidget(id);
        instrumentation.runOnMainSync(() -> {
            for (boolean systemNight : new boolean[]{false, true}) {
                Context hostContext = configured(systemNight);
                for (ThemeMode mode : ThemeMode.values()) {
                    putMode(id, mode);
                    boolean dark = mode == ThemeMode.SYSTEM ? systemNight : mode == ThemeMode.DARK;
                    int expectedBackground = context.getColor(dark
                            ? R.color.widgetBackground_dark : R.color.widgetBackground_light);
                    int expectedText = context.getColor(dark
                            ? R.color.widgetTextPrimary_dark : R.color.widgetTextPrimary_light);
                    for (WidgetThemes.Layout kind : WidgetThemes.Layout.values()) {
                        RemoteViews remote = new RemoteViews(context.getPackageName(),
                                WidgetThemes.layout(context, id, kind));
                        View view = remote.apply(hostContext, new FrameLayout(hostContext));
                        boolean mini = kind == WidgetThemes.Layout.MINI;
                        View body = view.findViewById(mini ? R.id.mini_root : R.id.widget_root);
                        assertNotNull(body);
                        assertEquals(mode + " / " + kind + " background", expectedBackground,
                                centerColor(body.getBackground()));
                        TextView label = view.findViewById(mini
                                ? R.id.mini_picker_area : R.id.widget_profile);
                        assertEquals(mode + " / " + kind + " text", expectedText,
                                label.getCurrentTextColor());
                    }
                }
            }
        });
    }

    @Test public void statusActionsPreserveThemeColorsAndSingleVisibleLabelOnReapply() {
        int id = 2_000_000_004;
        rememberWidget(id);
        instrumentation.runOnMainSync(() -> {
            for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                putMode(id, mode);
                boolean dark = mode == ThemeMode.DARK;
                int[] expectedColors = {
                        context.getColor(dark ? R.color.widgetSuccess_dark : R.color.widgetSuccess_light),
                        context.getColor(dark ? R.color.widgetError_dark : R.color.widgetError_light),
                        context.getColor(dark ? R.color.widgetWarning_dark : R.color.widgetWarning_light)
                };
                for (WidgetThemes.Layout kind : WidgetThemes.Layout.values()) {
                    boolean mini = kind == WidgetThemes.Layout.MINI;
                    int[] ids = mini
                            ? new int[]{R.id.mini_status_success, R.id.mini_status_error, R.id.mini_status_warning}
                            : new int[]{R.id.widget_status_success, R.id.widget_status_error, R.id.widget_status_warning};
                    RemoteViews remote = new RemoteViews(context.getPackageName(), WidgetThemes.layout(context, id, kind));
                    View view = remote.apply(context, new FrameLayout(context));
                    for (WidgetThemes.Status status : WidgetThemes.Status.values()) {
                        WidgetThemes.status(remote, mini, status, "CHECK " + status);
                        remote.reapply(context, view);
                        for (int i = 0; i < ids.length; i++) {
                            TextView label = view.findViewById(ids[i]);
                            assertEquals(i == status.ordinal() ? View.VISIBLE : View.GONE, label.getVisibility());
                            assertEquals("CHECK " + status, label.getText().toString());
                            assertEquals(expectedColors[i], label.getCurrentTextColor());
                        }
                    }
                }
            }
        });
    }

    @Test public void realWidgetIdsAreOwnedAndDeletionOnlyRemovesThatInstancesChoice() {
        bindWidgets();
        assertTrue(WidgetThemes.isOwned(context, fullWidget));
        assertTrue(WidgetThemes.isOwned(context, miniWidget));
        assertFalse(WidgetThemes.isOwned(context, AppWidgetManager.INVALID_APPWIDGET_ID));
        assertFalse(WidgetThemes.isOwned(context, -100));
        assertFalse(WidgetThemes.isOwned(context, 2_000_000_005));
        putMode(fullWidget, ThemeMode.DARK);
        putMode(miniWidget, ThemeMode.LIGHT);
        new DnsWidgetProvider().onDeleted(context, new int[]{fullWidget});
        assertFalse(widgetPreferences.contains(key(fullWidget)));
        assertEquals(ThemeMode.LIGHT, WidgetThemes.mode(context, miniWidget));
        new DnsMiniWidgetProvider().onDeleted(context, new int[]{miniWidget});
        assertFalse(widgetPreferences.contains(key(miniWidget)));
    }

    @Test public void restoreSnapshotsOverlappingIdsAndSetsRestoreCompletedWithoutLosingOptions() {
        bindWidgets();
        putMode(fullWidget, ThemeMode.DARK);
        putMode(miniWidget, ThemeMode.LIGHT);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 111);
        manager.updateAppWidgetOptions(fullWidget, options);
        WidgetThemes.restored(context, new int[]{fullWidget, miniWidget}, new int[]{miniWidget, fullWidget});
        assertEquals(ThemeMode.LIGHT, WidgetThemes.mode(context, fullWidget));
        assertEquals(ThemeMode.DARK, WidgetThemes.mode(context, miniWidget));
        assertEquals(111, manager.getAppWidgetOptions(fullWidget)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH));
        assertTrue(manager.getAppWidgetOptions(fullWidget)
                .getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED));
        assertTrue(manager.getAppWidgetOptions(miniWidget)
                .getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED));
    }

    @Test public void manualThemeChangesProduceDifferentLayoutsForImmediateHostReinflation() {
        int id = 2_000_000_006;
        rememberWidget(id);
        for (WidgetThemes.Layout kind : WidgetThemes.Layout.values()) {
            putMode(id, ThemeMode.SYSTEM);
            int systemLayout = WidgetThemes.layout(context, id, kind);
            putMode(id, ThemeMode.LIGHT);
            int lightLayout = WidgetThemes.layout(context, id, kind);
            putMode(id, ThemeMode.DARK);
            int darkLayout = WidgetThemes.layout(context, id, kind);
            assertNotEquals(systemLayout, lightLayout);
            assertNotEquals(systemLayout, darkLayout);
            assertNotEquals(lightLayout, darkLayout);
        }
    }

    @Test public void mainScreenUsesSavedAppThemeAndHidesUnscopedWidgetSetting() {
        ThemeSettings.setAppMode(context, ThemeMode.DARK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                assertEquals(context.getString(R.string.app_theme_current, ThemeMode.DARK.label(context)),
                        ((TextView) activity.findViewById(R.id.app_theme)).getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.widget_theme).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.widget_theme_hint).getVisibility());
                assertEquals(Configuration.UI_MODE_NIGHT_YES,
                        activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void unsavedProfileEditorSurvivesThemeRecreation() throws Exception {
        ThemeSettings.setAppMode(context, ThemeMode.DARK);
        MainActivity original = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        MainActivity restored = null;
        Instrumentation.ActivityMonitor monitor = null;
        try {
            instrumentation.runOnMainSync(() -> {
                original.findViewById(R.id.add_profile).performClick();
                editor(original, "editorName").setText("Unsaved provider draft");
                editor(original, "editorHostname").setText("draft.example.org");
            });
            monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
            ThemeSettings.setAppMode(context, ThemeMode.LIGHT);
            instrumentation.runOnMainSync(original::recreate);
            restored = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5_000);
            assertNotNull("Theme recreation must create a replacement Activity", restored);
            instrumentation.waitForIdleSync();
            MainActivity current = restored;
            instrumentation.runOnMainSync(() -> {
                assertEquals("Unsaved provider draft", editor(current, "editorName").getText().toString());
                assertEquals("draft.example.org", editor(current, "editorHostname").getText().toString());
                assertEquals(Configuration.UI_MODE_NIGHT_NO,
                        current.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
            });
        } finally {
            if (monitor != null) instrumentation.removeMonitor(monitor);
            MainActivity current = restored;
            instrumentation.runOnMainSync(() -> {
                if (current != null) current.finish();
                original.finish();
            });
        }
    }

    private void bindWidgets() {
        Assume.assumeTrue("Binding temporary widgets requires API 29+ shell adoption", Build.VERSION.SDK_INT >= 29);
        if (Build.VERSION.SDK_INT < 29) return;
        instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        host = new AppWidgetHost(context, (int) (System.nanoTime() & 0x7fffffff));
        fullWidget = host.allocateAppWidgetId();
        miniWidget = host.allocateAppWidgetId();
        rememberWidget(fullWidget);
        rememberWidget(miniWidget);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        assertTrue("Bind test full widget", manager.bindAppWidgetIdIfAllowed(fullWidget,
                new ComponentName(context, DnsWidgetProvider.class)));
        assertTrue("Bind test mini widget", manager.bindAppWidgetIdIfAllowed(miniWidget,
                new ComponentName(context, DnsMiniWidgetProvider.class)));
    }

    private Context configured(boolean night) {
        Configuration config = new Configuration();
        config.uiMode = night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(config);
    }

    private void putMode(int id, ThemeMode mode) {
        rememberWidget(id);
        assertTrue(widgetPreferences.edit().putString(key(id), mode.storedValue()).commit());
    }

    private void rememberWidget(int id) {
        String key = key(id);
        if (!savedWidgetModes.containsKey(key)) savedWidgetModes.put(key, widgetPreferences.getAll().get(key));
    }

    private static String key(int id) { return "widget_" + id; }

    private static EditText editor(MainActivity activity, String fieldName) {
        try {
            Field field = MainActivity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            EditText value = (EditText) field.get(activity);
            assertNotNull("Editor must remain open after recreation", value);
            return value;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the open profile editor", exception);
        }
    }

    private static int centerColor(Drawable drawable) {
        assertNotNull(drawable);
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 64, 64);
        drawable.draw(new Canvas(bitmap));
        int color = bitmap.getPixel(32, 32);
        bitmap.recycle();
        return color;
    }

    private static void restore(SharedPreferences preferences, String key, Object value) {
        SharedPreferences.Editor editor = preferences.edit().remove(key);
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        editor.commit();
    }
}
