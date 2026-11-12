package dev.localswitch.privatedns;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Resource, framework per-app locale, widget-host and Activity-recreation integration checks. */
@RunWith(AndroidJUnit4.class)
public final class LanguageInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> savedPreferences;
    private LocaleList savedApplicationLocales;
    private final List<MainActivity> activities = new ArrayList<>();
    private CapturingHost host;
    private int fullWidget;
    private int miniWidget;
    private CapturingView fullView;
    private CapturingView miniView;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext().getApplicationContext();
        preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        savedPreferences = new HashMap<>(preferences.getAll());
        if (Build.VERSION.SDK_INT >= 33) {
            savedApplicationLocales = context.getSystemService(LocaleManager.class).getApplicationLocales();
        }
    }

    @After public void tearDown() {
        if (instrumentation == null) return;
        instrumentation.runOnMainSync(() -> {
            for (MainActivity activity : activities) {
                if (!activity.isFinishing()) activity.finish();
            }
            if (host != null) {
                host.stopListening();
                host.deleteHost();
            }
            if (Build.VERSION.SDK_INT >= 33 && savedApplicationLocales != null) {
                context.getSystemService(LocaleManager.class).setApplicationLocales(savedApplicationLocales);
            }
        });
        restorePreferences(preferences, savedPreferences);
        if (Build.VERSION.SDK_INT >= 29) instrumentation.getUiAutomation().dropShellPermissionIdentity();
        instrumentation.waitForIdleSync();
        awaitApplicationLanguage();
    }

    @Test public void systemChoiceMatchesRussianRegionsEnglishAndUnsupportedLanguageFallback() {
        assertEquals(LanguageSettings.Mode.SYSTEM, LanguageSettings.Mode.fromStored(null));
        assertEquals(LanguageSettings.Mode.SYSTEM, LanguageSettings.Mode.fromStored("unsupported"));
        assertEquals(LanguageSettings.Mode.SYSTEM, LanguageSettings.Mode.fromStored(7));
        setLanguage(LanguageSettings.Mode.SYSTEM);
        for (boolean night : new boolean[]{false, true}) {
            for (String tag : new String[]{"ru", "ru-RU", "ru-KZ", "en", "en-GB", "fr-FR"}) {
                Context system = configured(tag, night);
                Context localized = LanguageSettings.wrap(system);
                assertSame("System mode must retain the supplied configuration", system, localized);
                assertEquals(tag.startsWith("ru") ? "Сохранённые DNS-серверы" : "Saved DNS servers",
                        localized.getString(R.string.main_saved_profiles));
                assertEquals(night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
                        localized.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
            }
        }
    }

    @Test public void manualLanguageOverridesSystemWithoutChangingThemeProfilesOrActualDns() {
        // Complete normal initial profile migration before comparing language-only changes.
        DnsProfiles.all(context);
        Map<String, ?> profileBefore = new HashMap<>(context.getSharedPreferences(
                "private_dns_widget_settings", Context.MODE_PRIVATE).getAll());
        Map<String, ?> widgetBefore = new HashMap<>(context.getSharedPreferences(
                "private_dns_widget_themes", Context.MODE_PRIVATE).getAll());
        Object themeBefore = preferences.getAll().get("app_theme");
        String dnsMode = DnsSettings.getSystemMode(context);
        String dnsHost = DnsSettings.getSystemHost(context);
        LocaleList systemBefore = Build.VERSION.SDK_INT >= 33
                ? context.getSystemService(LocaleManager.class).getSystemLocales() : null;
        for (LanguageSettings.Mode mode : new LanguageSettings.Mode[]{
                LanguageSettings.Mode.RUSSIAN, LanguageSettings.Mode.ENGLISH}) {
            setLanguage(mode);
            assertEquals(mode, LanguageSettings.getMode(context));
            assertEquals(mode.storedValue, preferences.getString("app_language", null));
            // Service/widget localization starts from the real application context. Synthetic
            // createConfigurationContext layers need not retain each other's override fields.
            Configuration before = new Configuration(context.getResources().getConfiguration());
            Context localized = LanguageSettings.context(context);
            Configuration after = localized.getResources().getConfiguration();
            assertEquals(mode.tag, after.getLocales().get(0).getLanguage());
            assertEquals(mode == LanguageSettings.Mode.RUSSIAN
                    ? "Сохранённые DNS-серверы" : "Saved DNS servers",
                    localized.getString(R.string.main_saved_profiles));
            assertEquals(before.uiMode, after.uiMode);
            assertEquals(before.orientation, after.orientation);
            assertEquals(before.densityDpi, after.densityDpi);
            assertEquals(before.fontScale, after.fontScale, 0.001f);
            assertEquals(themeBefore, preferences.getAll().get("app_theme"));
            assertEquals(profileBefore, context.getSharedPreferences("private_dns_widget_settings", Context.MODE_PRIVATE).getAll());
            assertEquals(widgetBefore, context.getSharedPreferences("private_dns_widget_themes", Context.MODE_PRIVATE).getAll());
            assertEquals(dnsMode, DnsSettings.getSystemMode(context));
            assertEquals(dnsHost, DnsSettings.getSystemHost(context));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(systemBefore, context.getSystemService(LocaleManager.class).getSystemLocales());
        }
    }

    @Test public void platformSettingsAreAuthoritativeAndClearingThemRestoresSystemMode() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
        if (Build.VERSION.SDK_INT < 33) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        instrumentation.runOnMainSync(() -> manager.setApplicationLocales(LocaleList.forLanguageTags("ru-RU,en-US")));
        assertEquals(LanguageSettings.Mode.RUSSIAN, LanguageSettings.getMode(context));
        preferences.edit().putString("app_language", "en").putBoolean("platform_language_initialized", true).commit();
        assertEquals("Stale preferences must not override Android's app-language setting",
                LanguageSettings.Mode.RUSSIAN, LanguageSettings.getMode(context));
        instrumentation.runOnMainSync(() -> manager.setApplicationLocales(LocaleList.forLanguageTags("en-GB")));
        assertEquals(LanguageSettings.Mode.ENGLISH, LanguageSettings.getMode(context));
        instrumentation.runOnMainSync(() -> manager.setApplicationLocales(LocaleList.getEmptyLocaleList()));
        assertEquals(LanguageSettings.Mode.SYSTEM, LanguageSettings.getMode(context));
        preferences.edit().putString("app_language", "ru").commit();
        instrumentation.runOnMainSync(() -> LanguageSettings.initialize(context));
        assertEquals("Migration must not overwrite an explicit OS-side reset to system language",
                LanguageSettings.Mode.SYSTEM, LanguageSettings.getMode(context));
    }

    @Test public void validationExceptionResolvesInTheCurrentlySelectedLanguage() {
        IllegalArgumentException invalid;
        try {
            DnsSettings.normalizeHostname(null);
            throw new AssertionError("Missing hostname must be rejected");
        } catch (IllegalArgumentException exception) {
            invalid = exception;
        }
        for (LanguageSettings.Mode mode : new LanguageSettings.Mode[]{
                LanguageSettings.Mode.RUSSIAN, LanguageSettings.Mode.ENGLISH}) {
            setLanguage(mode);
            Context expected = configured(mode.tag, false);
            assertEquals(expected.getString(R.string.error_hostname_required), Messages.message(context, invalid));
            assertEquals(expected.getString(R.string.error_unknown),
                    Messages.message(context, new IllegalStateException()));
            assertFalse(Messages.message(context, invalid).contains("message resource"));
        }
    }

    @Test public void editorLabelsAndNativeButtonsUseEachLanguageInBothThemes() {
        for (LanguageSettings.Mode language : new LanguageSettings.Mode[]{
                LanguageSettings.Mode.RUSSIAN, LanguageSettings.Mode.ENGLISH}) {
            setLanguage(language);
            for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                ThemeSettings.setAppMode(context, theme);
                MainActivity activity = launch();
                instrumentation.runOnMainSync(() -> {
                    assertEquals(language.tag, activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
                    assertEquals(theme == ThemeMode.DARK
                                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
                            activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                    assertEquals(activity.getString(R.string.main_add_profile),
                            ((TextView) activity.findViewById(R.id.add_profile)).getText().toString());
                    assertEquals(activity.getString(R.string.language_current, language.label(activity)),
                            ((TextView) activity.findViewById(R.id.app_language)).getText().toString());
                    activity.findViewById(R.id.add_profile).performClick();
                    assertEquals(activity.getString(R.string.main_profile_name_hint), editor(activity, "editorName").getHint().toString());
                    assertEquals(activity.getString(R.string.main_profile_host_hint), editor(activity, "editorHostname").getHint().toString());
                    AlertDialog dialog = field(activity, "profileEditor", AlertDialog.class);
                    assertEquals(activity.getString(R.string.main_save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                    assertEquals(activity.getString(android.R.string.cancel), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
                    dialog.dismiss();
                    activity.finish();
                });
                instrumentation.waitForIdleSync();
            }
        }
    }

    @Test public void languageSelectorRecreatesScreenAndUpdatesVisibleUi() {
        setLanguage(LanguageSettings.Mode.ENGLISH);
        MainActivity original = launch();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> original.findViewById(R.id.app_language).performClick());
            List<AccessibilityNodeInfo> choices = awaitLanguageChoice("Русский");
            assertFalse("Russian must be available in the language selector", choices.isEmpty());
            boolean clicked = false;
            for (AccessibilityNodeInfo choice : choices) {
                AccessibilityNodeInfo target = choice;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true;
                    break;
                }
            }
            assertTrue("Russian choice must be actionable", clicked);
            MainActivity replacement = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5_000);
            assertNotNull("Selecting a new language must recreate the Activity", replacement);
            activities.add(replacement);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals("ru", replacement.getResources().getConfiguration().getLocales().get(0).getLanguage());
                assertEquals("+ Добавить DNS-сервер", ((TextView) replacement.findViewById(R.id.add_profile)).getText().toString());
            });
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void languageRecreationPreservesUnsavedProfileFields() {
        setLanguage(LanguageSettings.Mode.ENGLISH);
        MainActivity original = launch();
        instrumentation.runOnMainSync(() -> {
            original.findViewById(R.id.add_profile).performClick();
            editor(original, "editorName").setText("Мой незавершённый профиль");
            editor(original, "editorHostname").setText("draft.example.org");
        });
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> {
                LanguageSettings.setMode(original, LanguageSettings.Mode.RUSSIAN);
                if (Build.VERSION.SDK_INT < 33) original.recreate();
            });
            MainActivity replacement = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5_000);
            assertNotNull("Language change must recreate the editor's Activity", replacement);
            activities.add(replacement);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals("Мой незавершённый профиль", editor(replacement, "editorName").getText().toString());
                assertEquals("draft.example.org", editor(replacement, "editorHostname").getText().toString());
                assertEquals(replacement.getString(R.string.main_profile_host_hint), editor(replacement, "editorHostname").getHint().toString());
                assertEquals("ru", replacement.getResources().getConfiguration().getLocales().get(0).getLanguage());
            });
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void providersSendSelectedLanguageToWidgetsEvenWhenHostUsesTheOtherLanguage() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
        if (Build.VERSION.SDK_INT < 29) return;
        bindWidgets();
        for (LanguageSettings.Mode language : new LanguageSettings.Mode[]{
                LanguageSettings.Mode.RUSSIAN, LanguageSettings.Mode.ENGLISH}) {
            setLanguage(language);
            Context expected = configured(language.tag, false);
            for (WidgetThemes.Layout kind : WidgetThemes.Layout.values()) {
                CapturingView capture = kind == WidgetThemes.Layout.MINI ? miniView : fullView;
                int widgetId = kind == WidgetThemes.Layout.MINI ? miniWidget : fullWidget;
                int expectedLayout = WidgetThemes.layout(context, widgetId, kind);
                instrumentation.runOnMainSync(() -> {
                    capture.remote.set(null);
                    if (kind == WidgetThemes.Layout.MINI) {
                        DnsMiniWidgetProvider.updateOne(context, AppWidgetManager.getInstance(context), widgetId);
                    } else {
                        Bundle options = new Bundle();
                        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, kind == WidgetThemes.Layout.COMPACT ? 180 : 320);
                        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 130);
                        AppWidgetManager.getInstance(context).updateAppWidgetOptions(widgetId, options);
                        DnsWidgetProvider.updateOne(context, AppWidgetManager.getInstance(context), widgetId);
                    }
                });
                RemoteViews remote = awaitRemote(capture, expectedLayout);
                for (boolean night : new boolean[]{false, true}) {
                    Context oppositeHost = configured(language == LanguageSettings.Mode.RUSSIAN ? "en-US" : "ru-RU", night);
                    instrumentation.runOnMainSync(() -> {
                        View inflated = remote.apply(oppositeHost, new FrameLayout(oppositeHost));
                        if (kind == WidgetThemes.Layout.MINI) {
                            assertEquals(expected.getString(R.string.widget_picker_description),
                                    inflated.findViewById(R.id.mini_picker_area).getContentDescription().toString());
                        } else {
                            assertEquals(expected.getString(R.string.widget_edit_description),
                                    inflated.findViewById(R.id.widget_settings).getContentDescription().toString());
                            assertEquals(expected.getString(R.string.widget_next_description),
                                    inflated.findViewById(R.id.widget_next).getContentDescription().toString());
                            if (kind == WidgetThemes.Layout.FULL) {
                                assertEquals(expected.getString(R.string.widget_heading),
                                        ((TextView) inflated.findViewById(R.id.widget_title)).getText().toString());
                                assertEquals(expected.getString(R.string.widget_previous),
                                        ((TextView) inflated.findViewById(R.id.widget_previous)).getText().toString());
                                int count = DnsProfiles.all(context).size();
                                assertEquals(expected.getResources().getQuantityString(
                                                R.plurals.widget_server_position, count,
                                                DnsProfiles.selectedIndex(context) + 1, count),
                                        ((TextView) inflated.findViewById(R.id.widget_position)).getText().toString());
                            }
                        }
                    });
                }
            }
        }
    }

    private void setLanguage(LanguageSettings.Mode mode) {
        instrumentation.runOnMainSync(() -> LanguageSettings.setMode(context, mode));
        assertEquals(mode, LanguageSettings.getMode(context));
        instrumentation.waitForIdleSync();
        awaitApplicationLanguage();
    }

    private void awaitApplicationLanguage() {
        if (Build.VERSION.SDK_INT < 33) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        LocaleList expected = manager.getApplicationLocales();
        if (expected.isEmpty()) expected = manager.getSystemLocales();
        assertFalse("Android must expose at least one configured locale", expected.isEmpty());
        String language = expected.get(0).getLanguage();
        long until = SystemClock.uptimeMillis() + 5_000;
        do {
            if (language.equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage())) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < until);
        assertEquals("Wait for the asynchronous framework locale update before launching an Activity",
                language, context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private List<AccessibilityNodeInfo> awaitLanguageChoice(String label) {
        long until = SystemClock.uptimeMillis() + 3_000;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> choices = new ArrayList<>();
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                    if (node.getText() != null && label.contentEquals(node.getText())) choices.add(node);
                }
                if (!choices.isEmpty()) return choices;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Language selector did not expose " + label);
    }

    private Context configured(String tags, boolean night) {
        Configuration configuration = new Configuration();
        configuration.setLocales(LocaleList.forLanguageTags(tags));
        configuration.uiMode = night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(configuration);
    }

    private MainActivity launch() {
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(activity);
        return activity;
    }

    private void bindWidgets() {
        instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        instrumentation.runOnMainSync(() -> {
            host = new CapturingHost(context, (int) (System.nanoTime() & 0x7fffffff));
            fullWidget = host.allocateAppWidgetId();
            miniWidget = host.allocateAppWidgetId();
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            assertTrue(manager.bindAppWidgetIdIfAllowed(fullWidget, new ComponentName(context, DnsWidgetProvider.class)));
            assertTrue(manager.bindAppWidgetIdIfAllowed(miniWidget, new ComponentName(context, DnsMiniWidgetProvider.class)));
            fullView = (CapturingView) host.createView(context, fullWidget, manager.getAppWidgetInfo(fullWidget));
            miniView = (CapturingView) host.createView(context, miniWidget, manager.getAppWidgetInfo(miniWidget));
            host.startListening();
        });
    }

    private RemoteViews awaitRemote(CapturingView view, int expectedLayout) {
        long until = SystemClock.uptimeMillis() + 5_000;
        RemoteViews remote;
        do {
            remote = view.remote.get();
            if (remote != null && remote.getLayoutId() == expectedLayout) return remote;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Widget host did not receive the requested layout " + expectedLayout);
    }

    private static EditText editor(MainActivity activity, String field) {
        return field(activity, field, EditText.class);
    }

    private static <T> T field(MainActivity activity, String name, Class<T> type) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            T value = type.cast(field.get(activity));
            assertNotNull("Expected an open editor", value);
            return value;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> saved) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
        }
        editor.commit();
    }

    private static final class CapturingHost extends AppWidgetHost {
        CapturingHost(Context context, int id) { super(context, id); }
        @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
            return new CapturingView(context);
        }
    }

    private static final class CapturingView extends AppWidgetHostView {
        final AtomicReference<RemoteViews> remote = new AtomicReference<>();
        CapturingView(Context context) { super(context); }
        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            if (views != null) remote.set(views);
        }
    }
}
