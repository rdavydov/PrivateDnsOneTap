package dev.localswitch.privatedns;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** A small, native, dialog-style DNS chooser opened only when the widget label is tapped. */
public final class ProfilePickerActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.48f);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<DnsProfiles.Profile> profiles = DnsProfiles.all(this);
        int selected = DnsProfiles.selectedIndex(this);
        DnsSettings.State state = DnsSettings.getState(this);
        boolean permitted = DnsSettings.hasPermission(this);
        int widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        boolean ownedWidget = WidgetThemes.isOwned(this, widgetId);
        int primaryText = getColor(R.color.app_text_primary);
        int secondaryText = getColor(R.color.app_text_secondary);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackground(shape(getColor(R.color.picker_background), 20));

        TextView title = text(getString(R.string.picker_title), 20, primaryText, true);
        root.addView(title);
        String detail;
        if (!permitted) detail = getString(R.string.picker_permission_required);
        else if (state == DnsSettings.State.ON) detail = getString(R.string.picker_state_on, profiles.get(selected).name);
        else if (state == DnsSettings.State.OFF) detail = getString(R.string.picker_state_off, profiles.get(selected).name);
        else if (state == DnsSettings.State.OTHER) detail = getString(R.string.picker_state_other);
        else detail = getString(R.string.picker_state_auto, profiles.get(selected).name);
        TextView subtitle = text(detail, 12, secondaryText, false);
        subtitle.setPadding(0, dp(2), 0, dp(10));
        root.addView(subtitle);

        TextView label = text(getString(R.string.picker_choose_server), 11, secondaryText, true);
        label.setPadding(dp(3), 0, 0, dp(4));
        root.addView(label);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(profiles.size() > 5);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list);
        for (int i = 0; i < profiles.size(); i++) {
            final int index = i;
            DnsProfiles.Profile profile = profiles.get(i);
            boolean active = state == DnsSettings.State.ON && selected == i;
            boolean marked = selected == i;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(6), dp(10), dp(6));
            row.setMinimumHeight(dp(52));
            row.setBackground(shape(getColor(marked ? R.color.picker_row_selected
                    : R.color.picker_row), 11));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(5);
            list.addView(row, rowParams);

            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView name = text(profile.name, 15, primaryText, marked);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(name);
            TextView hostname = text(profile.hostname, 11, secondaryText, false);
            hostname.setSingleLine(true);
            hostname.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(hostname);

            TextView indicator = text(active ? "●" : marked ? "✓" : "›", 18,
                    active ? getColor(R.color.app_success) : marked ? primaryText : secondaryText, true);
            indicator.setGravity(Gravity.CENTER);
            row.addView(indicator, new LinearLayout.LayoutParams(dp(31), dp(38)));

            row.setContentDescription(getString(R.string.picker_activate_description,
                    profile.name, profile.hostname));
            row.setFocusable(true);
            row.setOnClickListener(v -> runOperation(() -> DnsSettings.selectAndApply(this, index)));
        }
        // Keep the action buttons visible even if Magic V2 is folded or rotated.
        int availableListHeight = Math.max(dp(64), getResources().getDisplayMetrics().heightPixels
                - dp(ownedWidget ? 274 : 230));
        int listHeight = Math.min(availableListHeight,
                Math.min(dp(330), Math.max(dp(56), profiles.size() * dp(57))));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, listHeight);
        root.addView(scroller, listParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(-1, dp(47));
        controlParams.topMargin = dp(5);
        root.addView(controls, controlParams);
        addAction(controls, getString(R.string.picker_previous), R.color.picker_action_secondary,
                () -> DnsSettings.cycleAndApply(this, -1));
        addAction(controls, getString(state == DnsSettings.State.ON
                        ? R.string.picker_disable : R.string.picker_enable), R.color.picker_action_primary,
                () -> {
                    if (state == DnsSettings.State.ON) DnsSettings.disable(this);
                    else DnsSettings.enable(this);
                });
        addAction(controls, getString(R.string.picker_next), R.color.picker_action_secondary,
                () -> DnsSettings.cycleAndApply(this, 1));

        if (ownedWidget) {
            TextView appearance = text(getString(R.string.picker_widget_theme,
                    WidgetThemes.mode(this, widgetId).label(this)), 13, secondaryText, true);
            appearance.setGravity(Gravity.CENTER);
            appearance.setPadding(dp(3), dp(8), dp(3), dp(3));
            appearance.setMinimumHeight(dp(44));
            appearance.setFocusable(true);
            appearance.setOnClickListener(v -> WidgetThemes.showDialog(this, widgetId, this::render));
            root.addView(appearance);
        }

        TextView manage = text(getString(permitted ? R.string.picker_manage : R.string.picker_setup_manage),
                13, secondaryText, true);
        manage.setGravity(Gravity.CENTER);
        manage.setPadding(dp(3), dp(8), dp(3), dp(3));
        manage.setMinimumHeight(dp(44));
        manage.setFocusable(true);
        manage.setContentDescription(getString(R.string.picker_manage_description));
        manage.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            if (ownedWidget) intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            startActivity(intent);
            finish();
        });
        root.addView(manage);

        setContentView(root);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(370), screenWidth - dp(28));
        getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void addAction(LinearLayout parent, String caption, int backgroundColor,
                           Runnable operation) {
        TextView button = text(caption, 12, getColor(R.color.app_text_primary), true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(getColor(backgroundColor), 10));
        button.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        parent.addView(button, params);
        button.setOnClickListener(v -> runOperation(operation));
    }

    private void runOperation(Runnable action) {
        try {
            action.run();
            DnsWidgetProvider.updateAll(this);
            finish(); // Return immediately to Nova instead of opening a full-screen app.
        } catch (RuntimeException error) {
            Toast.makeText(this, getString(R.string.picker_operation_failed,
                    Messages.message(this, error)), Toast.LENGTH_LONG).show();
            DnsWidgetProvider.updateAll(this);
            render(); // Keep the picker visible for a retry or to open Setup.
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable shape(int fill, float radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
