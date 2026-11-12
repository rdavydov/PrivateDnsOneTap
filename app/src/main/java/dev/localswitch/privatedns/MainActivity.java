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
        widgetThemeButton = findViewById(R.id.widget_theme);
        appThemeButton.setOnClickListener(view -> ThemeSettings.showAppThemeDialog(this));
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
            toast("Manual DNS enabled: " + DnsProfiles.selected(this).hostname);
        }));
        findViewById(R.id.disable_button).setOnClickListener(view -> runChange(() -> {
            DnsSettings.disable(this);
            toast("Private DNS switched off.");
        }));
        findViewById(R.id.copy_button).setOnClickListener(view -> {
            ClipboardManager manager = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("Private DNS ADB command", ADB_COMMAND));
            toast("ADB command copied.");
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
            String suffix = isSelected ? (currentState == DnsSettings.State.ON ? "  • ACTIVE" : "  • SELECTED") : "";
            title.setText(profile.name + suffix);
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
            apply.setText(isSelected ? "APPLY" : "USE");
            apply.setAllCaps(false);
            buttons.addView(apply, new LinearLayout.LayoutParams(0, dp(48), 1.3f));
            apply.setOnClickListener(view -> runChange(() -> {
                DnsSettings.selectAndApply(this, index);
                toast("Using " + DnsProfiles.selected(this).name);
            }));

            Button edit = new Button(this);
            edit.setText("Edit");
            edit.setAllCaps(false);
            buttons.addView(edit, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
            edit.setOnClickListener(view -> showProfileEditor(index));

            Button delete = new Button(this);
            delete.setText("Delete");
            delete.setAllCaps(false);
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
        name.setHint("Name (e.g. NextDNS)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(editing == null ? "" : editing.name);
        fields.addView(name, new LinearLayout.LayoutParams(-1, dp(55)));

        EditText hostname = new EditText(this);
        hostname.setSingleLine(true);
        hostname.setHint("DNS-over-TLS hostname");
        hostname.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostname.setText(editing == null ? "" : editing.hostname);
        fields.addView(hostname, new LinearLayout.LayoutParams(-1, dp(55)));

        TextView hint = new TextView(this);
        hint.setText("Hostname only, for example dns.google. No https://, IP address, or port.");
        hint.setTextColor(getColor(R.color.app_text_secondary));
        hint.setTextSize(12);
        fields.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing == null ? "Add DNS server" : "Edit DNS server")
                .setView(fields)
                .setNegativeButton("Cancel", (d, which) -> { })
                .setPositiveButton("Save", null)
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
                        toast("DNS profile saved. Tap Apply or use the widget to activate.");
                    } catch (IllegalArgumentException exception) {
                        // Invalid entry must NOT silently close the editor.
                        hostname.setError(exception.getMessage());
                        toast(exception.getMessage());
                    } catch (RuntimeException exception) {
                        toast("Could not save: " + exception.getMessage());
                    }
                }));
        dialog.show();
    }

    private void confirmDelete(int index) {
        List<DnsProfiles.Profile> list = DnsProfiles.all(this);
        if (index < 0 || index >= list.size()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + list.get(index).name + "?")
                .setMessage("This removes the saved profile; it does not change the active Android DNS setting.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        DnsProfiles.remove(this, index);
                        refreshAll();
                        DnsWidgetProvider.updateAll(this);
                    } catch (RuntimeException exception) {
                        toast(exception.getMessage());
                    }
                }).show();
    }

    private void runChange(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            toast("DNS change failed: " + exception.getMessage());
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
                description = "MANUAL: " + profile.name + " (" + profile.hostname + ")";
                color = getColor(R.color.app_success);
                break;
            case OFF:
                description = "OFF (saved selection: " + DnsProfiles.selected(this).name + ")";
                color = getColor(R.color.app_error);
                break;
            case OTHER:
                description = "MANUAL: " + DnsSettings.getSystemHost(this) + " (not the selected profile)";
                color = getColor(R.color.app_warning);
                break;
            default:
                description = "AUTOMATIC / system default";
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
