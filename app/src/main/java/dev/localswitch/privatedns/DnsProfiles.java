package dev.localswitch.privatedns;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simple on-device storage for user-editable DNS-over-TLS profiles. */
final class DnsProfiles {
    private static final String PREFS = "private_dns_widget_settings"; // Same as the original app.
    private static final String OLD_HOST = "hostname";
    private static final String PROFILES = "profiles_json";
    private static final String SELECTED = "selected_profile_index";
    private static final String PROVIDERS_MIGRATED = "geohide_comss_added";
    private static final String GEOHIDE_HOST = "eu.geohide.ru";
    private static final String COMSS_HOST = "dns.comss.one";
    static final int MAX_PROFILES = 30;

    static final class Profile {
        final String name;
        final String hostname;

        Profile(String name, String hostname) {
            this.name = normalizeName(name);
            this.hostname = DnsSettings.normalizeHostname(hostname);
        }
    }

    private DnsProfiles() { }

    static List<Profile> all(Context context) {
        SharedPreferences prefs = prefs(context);
        String raw = prefs.getString(PROFILES, null);
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                List<Profile> result = new ArrayList<>();
                int selectedRawIndex = prefs.getInt(SELECTED, 0);
                String selectedRawHost = null;
                for (int i = 0; i < array.length() && i < MAX_PROFILES; i++) {
                    JSONObject object = array.getJSONObject(i);
                    Profile p = new Profile(object.getString("name"), object.getString("host"));
                    if (i == selectedRawIndex) selectedRawHost = p.hostname;
                    if (!containsHost(result, p.hostname)) result.add(p);
                }
                if (!result.isEmpty()) {
                    if (!Boolean.TRUE.equals(prefs.getAll().get(PROVIDERS_MIGRATED))) {
                        migrateProviders(context, result, selectedRawHost);
                    }
                    return Collections.unmodifiableList(result);
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                // Malformed preferences should not crash the widget.
            }
        }
        List<Profile> starting = defaultProfiles(context, prefs);
        // Persist migration so existing OneTap users keep their previously saved hostname.
        save(context, starting, 0, true);
        return Collections.unmodifiableList(starting);
    }

    private static List<Profile> defaultProfiles(Context context, SharedPreferences prefs) {
        List<Profile> initial = new ArrayList<>();
        String previous = prefs.getString(OLD_HOST, DnsSettings.DEFAULT_HOST);
        try {
            initial.add(new Profile(DnsSettings.DEFAULT_HOST.equalsIgnoreCase(previous)
                    ? "DNS AI" : LanguageSettings.context(context).getString(R.string.saved_dns_name), previous));
        } catch (IllegalArgumentException e) {
            initial.add(new Profile("DNS AI", DnsSettings.DEFAULT_HOST));
        }
        addDefaultIfMissing(initial, "GeoHide", GEOHIDE_HOST);
        addDefaultIfMissing(initial, "Comss", COMSS_HOST);
        addDefaultIfMissing(initial, "Cloudflare", "one.one.one.one");
        addDefaultIfMissing(initial, "Google", "dns.google");
        return initial;
    }

    private static void addDefaultIfMissing(List<Profile> list, String label, String host) {
        if (!containsHost(list, host)) list.add(new Profile(label, host));
    }

    /** Add the two new defaults once, without replacing labels, selection or user entries. */
    private static void migrateProviders(Context context, List<Profile> list, String selectedRawHost) {
        String selectedHost = selectedRawHost != null ? selectedRawHost : list.get(0).hostname;
        // Only missing providers consume free slots; existing matching entries are always retained.
        int freeSlots = MAX_PROFILES - list.size();
        String[] hosts = {GEOHIDE_HOST, COMSS_HOST};
        String[] labels = {"GeoHide", "Comss"};
        List<Profile> providers = new ArrayList<>();
        for (int i = 0; i < hosts.length; i++) {
            int existing = indexOfHost(list, hosts[i]);
            if (existing >= 0) {
                providers.add(list.remove(existing));
            } else if (freeSlots > 0) {
                providers.add(new Profile(labels[i], hosts[i]));
                freeSlots--;
            }
        }
        int anchor = indexOfHost(list, DnsSettings.DEFAULT_HOST);
        list.addAll(anchor >= 0 ? anchor + 1 : 0, providers);
        // Reordering an index must not silently change the provider selected by the user.
        int selected = indexOfHost(list, selectedHost);
        save(context, list, selected, true);
    }

    private static int indexOfHost(List<Profile> list, String host) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).hostname.equalsIgnoreCase(host)) return i;
        }
        return -1;
    }

    private static boolean containsHost(List<Profile> list, String host) {
        for (Profile p : list) if (p.hostname.equalsIgnoreCase(host)) return true;
        return false;
    }

    static int selectedIndex(Context context) {
        List<Profile> list = all(context);
        int index = prefs(context).getInt(SELECTED, 0);
        return index >= 0 && index < list.size() ? index : 0;
    }

    static Profile selected(Context context) {
        List<Profile> list = all(context);
        return list.get(selectedIndex(context));
    }

    static void select(Context context, int index) {
        List<Profile> list = all(context);
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_unknown_profile));
        save(context, list, index);
    }

    static void add(Context context, String name, String hostname) {
        List<Profile> list = new ArrayList<>(all(context));
        if (list.size() >= MAX_PROFILES) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_profiles_limit, MAX_PROFILES));
        Profile p = new Profile(name, hostname);
        if (containsHost(list, p.hostname)) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_duplicate_host));
        list.add(p);
        save(context, list, selectedIndex(context));
    }

    static void update(Context context, int index, String name, String hostname) {
        List<Profile> list = new ArrayList<>(all(context));
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_unknown_profile));
        Profile changed = new Profile(name, hostname);
        for (int i = 0; i < list.size(); i++) {
            if (i != index && list.get(i).hostname.equalsIgnoreCase(changed.hostname)) {
                throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_duplicate_host));
            }
        }
        list.set(index, changed);
        save(context, list, selectedIndex(context));
    }

    static void remove(Context context, int index) {
        List<Profile> list = new ArrayList<>(all(context));
        if (list.size() <= 1) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_keep_profile));
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_unknown_profile));
        int selected = selectedIndex(context);
        list.remove(index);
        if (index < selected) selected--;
        else if (index == selected) selected = Math.min(selected, list.size() - 1);
        save(context, list, selected);
    }

    private static String normalizeName(String name) {
        if (name == null) throw Messages.invalid(R.string.error_name_required);
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            throw Messages.invalid(R.string.error_name_length);
        }
        return clean;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void save(Context context, List<Profile> list, int selected) {
        save(context, list, selected, false);
    }

    private static void save(Context context, List<Profile> list, int selected,
                             boolean markProvidersMigrated) {
        if (list.isEmpty() || selected < 0 || selected >= list.size()) {
            throw new IllegalArgumentException(LanguageSettings.context(context).getString(R.string.error_profiles_invalid));
        }
        JSONArray json = new JSONArray();
        for (Profile p : list) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", p.name);
                item.put("host", p.hostname);
            } catch (JSONException e) {
                throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_profiles_serialize), e);
            }
            json.put(item);
        }
        // Keep old preference synchronized, making in-place upgrades easier to reverse.
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(PROFILES, json.toString())
                .putInt(SELECTED, selected)
                .putString(OLD_HOST, list.get(selected).hostname);
        if (markProvidersMigrated) editor.putBoolean(PROVIDERS_MIGRATED, true);
        boolean result = editor.commit();
        if (!result) throw new IllegalStateException(LanguageSettings.context(context).getString(R.string.error_profiles_save));
    }
}
