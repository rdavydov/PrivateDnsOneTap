package dev.localswitch.privatedns;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Add/edit/remove named DNS servers. All switches also work directly from the widget. */
public final class MainActivity extends ThemedActivity {
    private static final String ADB_COMMAND = "adb shell pm grant "
            + "dev.localswitch.privatedns android.permission.WRITE_SECURE_SETTINGS";

    private LinearLayout profileList;
    private TextView statusView;
    private TextView permissionView;
    private TextView shizukuStatus;
    private Button shizukuButton;
    private Button openShizukuButton;
    private Button appThemeButton;
    private Button languageButton;
    private Button widgetThemeButton;
    private ShizukuPermissionController shizuku;
    private ContentObserver dnsObserver;
    private AlertDialog profileEditor;
    private EditText editorName;
    private EditText editorHostname;
    private int editorIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        profileList = findViewById(R.id.profiles_container);
        statusView = findViewById(R.id.dns_status);
        permissionView = findViewById(R.id.permission_status);
        shizukuStatus = findViewById(R.id.shizuku_status);
        shizukuButton = findViewById(R.id.shizuku_grant);
        openShizukuButton = findViewById(R.id.shizuku_open);
        appThemeButton = findViewById(R.id.app_theme);
        languageButton = findViewById(R.id.app_language);
        widgetThemeButton = findViewById(R.id.widget_theme);
        appThemeButton.setOnClickListener(view -> ThemeSettings.showAppThemeDialog(this));
        languageButton.setOnClickListener(view -> LanguageSettings.showDialog(this,
                this::refreshAppearance));
        widgetThemeButton.setOnClickListener(view -> WidgetThemes.showDialog(this,
                currentWidgetId(), this::refreshAppearance));

        shizuku = new ShizukuPermissionController(this, (state, message) -> {
            shizukuStatus.setText(message);
            boolean granted = state == ShizukuPermissionController.State.GRANTED;
            boolean error = state == ShizukuPermissionController.State.ERROR
                    || state == ShizukuPermissionController.State.DENIED;
            shizukuStatus.setTextColor(getColor(granted ? R.color.app_success
                    : error ? R.color.app_error : R.color.app_text_secondary));
            shizukuButton.setEnabled(shizuku.canRequest());
            shizukuButton.setText(state == ShizukuPermissionController.State.GRANTING
                    ? R.string.shizuku_granting : R.string.shizuku_grant);
            openShizukuButton.setVisibility(shizuku.canOpenManager() && !granted
                    ? View.VISIBLE : View.GONE);
            refreshStatus();
        });
        shizukuButton.setOnClickListener(view -> shizuku.request());
        openShizukuButton.setOnClickListener(view -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(
                    ShizukuPermissionController.MANAGER_PACKAGE);
            if (intent == null) {
                toast(getString(R.string.shizuku_cannot_open));
                return;
            }
            try {
                startActivity(intent);
            } catch (RuntimeException exception) {
                toast(getString(R.string.shizuku_cannot_open));
            }
        });
        shizuku.attach();

        TextView commandText = findViewById(R.id.adb_command);
        commandText.setText(ADB_COMMAND);
        findViewById(R.id.add_profile).setOnClickListener(view -> showProfileEditor(-1));
        findViewById(R.id.enable_button).setOnClickListener(view -> runChange(() -> {
            DnsSettings.enable(this);
            toast(getString(R.string.main_dns_enabled, DnsProfiles.selected(this).hostname));
        }));
        findViewById(R.id.disable_button).setOnClickListener(view -> runChange(() -> {
            DnsSettings.disable(this);
            toast(getString(R.string.main_dns_disabled));
        }));
        findViewById(R.id.copy_button).setOnClickListener(view -> {
            ClipboardManager manager = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.main_adb_clip_label), ADB_COMMAND));
            toast(getString(R.string.main_adb_copied));
        });

        dnsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                refreshStatus();
                DnsWidgetProvider.updateAll(MainActivity.this);
            }
        };
        refreshAll();
        if (savedInstanceState != null && savedInstanceState.getBoolean("profile_editor_open")) {
            showProfileEditor(savedInstanceState.getInt("profile_editor_index", -1));
            if (profileEditor != null) {
                editorName.setText(savedInstanceState.getString("profile_editor_name", ""));
                editorHostname.setText(savedInstanceState.getString("profile_editor_host", ""));
            }
        }
    }

    private void renderProfiles() {
        profileList.removeAllViews();
        List<DnsProfiles.Profile> profiles = DnsProfiles.all(this);
        int selected = DnsProfiles.selectedIndex(this);
        DnsSettings.State currentState = DnsSettings.getState(this);
        for (int i = 0; i < profiles.size(); i++) {
            final int index = i;
            DnsProfiles.Profile profile = profiles.get(i);
            boolean isSelected = i == selected;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundResource(R.drawable.card_background);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            profileList.addView(row, rowParams);

            TextView title = new TextView(this);
            String titleText = isSelected ? getString(currentState == DnsSettings.State.ON
                    ? R.string.main_profile_active : R.string.main_profile_selected, profile.name)
                    : profile.name;
            title.setText(titleText);
            title.setTextSize(17);
            title.setTextColor(getColor(R.color.app_text_primary));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(title);

            TextView host = new TextView(this);
            host.setText(profile.hostname);
            host.setTextColor(getColor(R.color.app_text_secondary));
            host.setTextSize(14);
            host.setSingleLine(true);
            host.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(host);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(buttons);

            Button apply = new Button(this);
            apply.setText(isSelected ? R.string.main_apply : R.string.main_use);
            apply.setAllCaps(false);
            apply.setTextSize(12);
            apply.setMinWidth(0);
            apply.setMinimumWidth(0);
            apply.setPadding(dp(4), 0, dp(4), 0);
            buttons.addView(apply, new LinearLayout.LayoutParams(0, dp(48), 1.3f));
            apply.setOnClickListener(view -> runChange(() -> {
                DnsSettings.selectAndApply(this, index);
                toast(getString(R.string.main_profile_applied, DnsProfiles.selected(this).name));
            }));

            Button edit = new Button(this);
            edit.setText(R.string.main_edit);
            edit.setAllCaps(false);
            edit.setTextSize(12);
            edit.setMinWidth(0);
            edit.setMinimumWidth(0);
            edit.setPadding(dp(4), 0, dp(4), 0);
            buttons.addView(edit, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
            edit.setOnClickListener(view -> showProfileEditor(index));

            Button delete = new Button(this);
            delete.setText(R.string.main_delete);
            delete.setAllCaps(false);
            delete.setTextSize(12);
            delete.setMinWidth(0);
            delete.setMinimumWidth(0);
            delete.setPadding(dp(4), 0, dp(4), 0);
            delete.setEnabled(profiles.size() > 1);
            buttons.addView(delete, new LinearLayout.LayoutParams(0, dp(48), 1.1f));
            delete.setOnClickListener(view -> confirmDelete(index));
        }
    }

    private void showProfileEditor(int index) {
        List<DnsProfiles.Profile> profiles = DnsProfiles.all(this);
        if (index >= profiles.size()) return;
        DnsProfiles.Profile editing = index >= 0 ? profiles.get(index) : null;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), dp(8), dp(22), 0);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.main_profile_name_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(editing == null ? "" : editing.name);
        fields.addView(name, new LinearLayout.LayoutParams(-1, dp(55)));

        EditText hostname = new EditText(this);
        hostname.setSingleLine(true);
        hostname.setHint(R.string.main_profile_host_hint);
        hostname.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostname.setText(editing == null ? "" : editing.hostname);
        fields.addView(hostname, new LinearLayout.LayoutParams(-1, dp(55)));

        TextView hint = new TextView(this);
        hint.setText(R.string.main_profile_host_help);
        hint.setTextColor(getColor(R.color.app_text_secondary));
        hint.setTextSize(12);
        fields.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing == null ? R.string.main_add_title : R.string.main_edit_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, (d, which) -> { })
                .setPositiveButton(R.string.main_save, null)
                .create();
        profileEditor = dialog;
        editorName = name;
        editorHostname = hostname;
        editorIndex = index;
        dialog.setOnDismissListener(ignored -> {
            if (profileEditor == dialog) {
                profileEditor = null;
                editorName = null;
                editorHostname = null;
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        if (editing == null) DnsProfiles.add(this,
                                name.getText().toString(), hostname.getText().toString());
                        else DnsProfiles.update(this, index,
                                name.getText().toString(), hostname.getText().toString());
                        dialog.dismiss();
                        refreshAll();
                        DnsWidgetProvider.updateAll(this);
                        toast(getString(R.string.main_profile_saved));
                    } catch (IllegalArgumentException exception) {
                        // Invalid entry must NOT silently close the editor.
                        hostname.setError(Messages.message(this, exception));
                        toast(Messages.message(this, exception));
                    } catch (RuntimeException exception) {
                        toast(getString(R.string.main_save_failed, Messages.message(this, exception)));
                    }
                }));
        dialog.show();
    }

    private void confirmDelete(int index) {
        List<DnsProfiles.Profile> list = DnsProfiles.all(this);
        if (index < 0 || index >= list.size()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_delete_title, list.get(index).name))
                .setMessage(R.string.main_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.main_delete, (dialog, which) -> {
                    try {
                        DnsProfiles.remove(this, index);
                        refreshAll();
                        DnsWidgetProvider.updateAll(this);
                    } catch (RuntimeException exception) {
                        toast(Messages.message(this, exception));
                    }
                }).show();
    }

    private void runChange(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            toast(getString(R.string.main_dns_change_failed, Messages.message(this, exception)));
        } finally {
            refreshAll();
            DnsWidgetProvider.updateAll(this);
        }
    }

    private void refreshAll() {
        refreshStatus();
        renderProfiles();
        refreshAppearance();
    }

    private int currentWidgetId() {
        return getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    private void refreshAppearance() {
        languageButton.setText(getString(R.string.language_current,
                LanguageSettings.getMode(this).label(this)));
        appThemeButton.setText(getString(R.string.app_theme_current,
                ThemeSettings.getAppMode(this).label(this)));
        int id = currentWidgetId();
        boolean owned = WidgetThemes.isOwned(this, id);
        widgetThemeButton.setVisibility(owned ? View.VISIBLE : View.GONE);
        if (owned) widgetThemeButton.setText(getString(R.string.this_widget_theme_current,
                WidgetThemes.mode(this, id).label(this)));
        findViewById(R.id.widget_theme_hint).setVisibility(owned ? View.GONE : View.VISIBLE);
    }

    private void refreshStatus() {
        boolean allowed = DnsSettings.hasPermission(this);
        permissionView.setText(allowed ? R.string.permission_granted : R.string.permission_missing);
        permissionView.setTextColor(getColor(allowed ? R.color.app_success : R.color.app_error));

        DnsSettings.State state = DnsSettings.getState(this);
        String description;
        int color;
        switch (state) {
            case ON:
                DnsProfiles.Profile profile = DnsProfiles.selected(this);
                description = getString(R.string.main_status_manual, profile.name, profile.hostname);
                color = getColor(R.color.app_success);
                break;
            case OFF:
                description = getString(R.string.main_status_off, DnsProfiles.selected(this).name);
                color = getColor(R.color.app_error);
                break;
            case OTHER:
                description = getString(R.string.main_status_other, DnsSettings.getSystemHost(this));
                color = getColor(R.color.app_warning);
                break;
            default:
                description = getString(R.string.main_status_auto);
                color = getColor(R.color.app_warning);
                break;
        }
        statusView.setText(description);
        statusView.setTextColor(color);
    }

    @Override protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(DnsSettings.MODE_KEY), false, dnsObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(DnsSettings.HOST_KEY), false, dnsObserver);
        refreshAll();
        shizuku.refresh();
        DnsWidgetProvider.updateAll(this);
    }

    @Override protected void onPause() {
        getContentResolver().unregisterContentObserver(dnsObserver);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshAppearance();
    }

    @Override protected void onDestroy() {
        if (shizuku != null) shizuku.close();
        if (profileEditor != null) profileEditor.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (profileEditor != null && profileEditor.isShowing()) {
            outState.putBoolean("profile_editor_open", true);
            outState.putInt("profile_editor_index", editorIndex);
            outState.putString("profile_editor_name", editorName.getText().toString());
            outState.putString("profile_editor_host", editorHostname.getText().toString());
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
