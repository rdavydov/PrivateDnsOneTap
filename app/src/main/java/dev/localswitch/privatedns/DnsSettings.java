package dev.localswitch.privatedns;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.net.IDN;
import java.util.List;
import java.util.Locale;

/** Reads and writes Android's actual Private DNS mode and manual hostname. */
final class DnsSettings {
    static final String MODE_KEY = "private_dns_mode";
    static final String HOST_KEY = "private_dns_specifier";
    static final String DEFAULT_HOST = "dns.dns-ai.ru";

    enum State { ON, OFF, AUTO, OTHER }

    private DnsSettings() { }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Converts DNS-over-TLS names to a safe canonical hostname (no URL, port, or raw IP). */
    static String normalizeHostname(String input) {
        if (input == null) throw Messages.invalid(R.string.error_hostname_required);
        String name;
        try {
            name = IDN.toASCII(input.trim(), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw Messages.invalid(R.string.error_hostname_format);
        }
        if (name.isEmpty() || name.length() > 253 || name.endsWith(".") || !name.contains(".")) {
            throw Messages.invalid(R.string.error_hostname_example);
        }
        // A numeric IPv4 literal is not a DNS-over-TLS provider hostname.
        if (name.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw Messages.invalid(R.string.error_hostname_ip);
        }
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                throw Messages.invalid(R.string.error_hostname_label);
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') {
                    throw Messages.invalid(R.string.error_hostname_characters);
                }
            }
        }
        return name;
    }

    static String getSystemMode(Context context) {
        return Settings.Global.getString(context.getContentResolver(), MODE_KEY);
    }

    static String getSystemHost(Context context) {
        return Settings.Global.getString(context.getContentResolver(), HOST_KEY);
    }

    static State getState(Context context) {
        String mode = getSystemMode(context);
        if ("off".equals(mode)) return State.OFF;
        if ("hostname".equals(mode)) {
            String actual = getSystemHost(context);
            return DnsProfiles.selected(context).hostname.equalsIgnoreCase(actual)
                    ? State.ON : State.OTHER;
        }
        return State.AUTO;
    }

    private static void requirePermission(Context context) {
        if (!hasPermission(context)) {
            throw new SecurityException(LanguageSettings.context(context).getString(R.string.error_permission_required));
        }
    }

    /** Write specifier first, then switch Android into the manual-hostname mode. */
    static void applyHost(Context context, String hostname) {
        requirePermission(context);
        String host = normalizeHostname(hostname);
        if (!Settings.Global.putString(context.getContentResolver(), HOST_KEY, host)) {
            throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_dns_write_host));
        }
        if (!Settings.Global.putString(context.getContentResolver(), MODE_KEY, "hostname")) {
            throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_dns_enable));
        }
        if (!"hostname".equals(getSystemMode(context))
                || !host.equalsIgnoreCase(getSystemHost(context))) {
            throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_dns_rejected));
        }
    }

    static void enable(Context context) {
        applyHost(context, DnsProfiles.selected(context).hostname);
    }

    static void disable(Context context) {
        requirePermission(context);
        if (!Settings.Global.putString(context.getContentResolver(), MODE_KEY, "off")) {
            throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_dns_disable));
        }
        // Do not clear the specifier: Honor's Manual input will still retain the last host.
        if (!"off".equals(getSystemMode(context))) {
            throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_dns_disable_rejected));
        }
    }

    /** Power control: Manual selected hostname <-> Off. */
    static void toggle(Context context) {
        if (getState(context) == State.ON) disable(context);
        else enable(context);
    }

    /** Switch the server AND enable it, even when the previous mode was Off. */
    static void cycleAndApply(Context context, int direction) {
        List<DnsProfiles.Profile> list = DnsProfiles.all(context);
        int current = DnsProfiles.selectedIndex(context);
        int next = (current + (direction > 0 ? 1 : -1) + list.size()) % list.size();
        // Only change saved selection after Android has accepted the requested server.
        applyHost(context, list.get(next).hostname);
        DnsProfiles.select(context, next);
    }

    /** Apply a specific saved profile from the management screen. */
    static void selectAndApply(Context context, int index) {
        List<DnsProfiles.Profile> profiles = DnsProfiles.all(context);
        if (index < 0 || index >= profiles.size()) {
            throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_unknown_profile));
        }
        applyHost(context, profiles.get(index).hostname);
        DnsProfiles.select(context, index);
    }
}
