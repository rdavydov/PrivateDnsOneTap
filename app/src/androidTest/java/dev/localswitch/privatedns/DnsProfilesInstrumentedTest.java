package dev.localswitch.privatedns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests upgrade behavior against real Android preferences without touching saved user profiles. */
@RunWith(AndroidJUnit4.class)
public final class DnsProfilesInstrumentedTest {
    private static final String PROFILE_PREFS = "private_dns_widget_settings";
    private static final String TEST_PREFS = "profile_migration_instrumentation";
    private Context context;
    private SharedPreferences preferences;

    @Before public void setUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(PROFILE_PREFS.equals(name) ? TEST_PREFS : name, mode);
            }
        };
        preferences = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
    }

    @After public void tearDown() {
        if (preferences != null) preferences.edit().clear().commit();
    }

    @Test public void freshInstallHasRequestedDefaultOrderAndSelection() {
        assertEquals(Arrays.asList("dns.dns-ai.ru", "eu.geohide.ru", "dns.comss.one",
                "one.one.one.one", "dns.google"), hosts());
        assertEquals(Arrays.asList("DNS AI", "GeoHide", "Comss", "Cloudflare", "Google"), names());
        assertEquals("dns.dns-ai.ru", DnsProfiles.selected(context).hostname);
        assertTrue(preferences.getBoolean("geohide_comss_added", false));
    }

    @Test public void migrationPreservesSelectionRemainingOrderAndActualSystemDns() throws Exception {
        seed(2, profile("Personal", "personal.example.org"), profile("DNS AI", "dns.dns-ai.ru"),
                profile("Google custom label", "dns.google"), profile("Cloudflare", "one.one.one.one"));
        String systemMode = DnsSettings.getSystemMode(context);
        String systemHost = DnsSettings.getSystemHost(context);
        assertEquals(Arrays.asList("personal.example.org", "dns.dns-ai.ru", "eu.geohide.ru",
                "dns.comss.one", "dns.google", "one.one.one.one"), hosts());
        assertEquals("dns.google", DnsProfiles.selected(context).hostname);
        assertEquals("Google custom label", DnsProfiles.selected(context).name);
        assertEquals(4, DnsProfiles.selectedIndex(context));
        assertEquals(systemMode, DnsSettings.getSystemMode(context));
        assertEquals(systemHost, DnsSettings.getSystemHost(context));
    }

    @Test public void existingProvidersKeepLabelsAndAreReorderedWithoutDuplicates() throws Exception {
        seed(0, profile("My Comss", "DNS.COMSS.ONE"), profile("Personal", "personal.example.org"),
                profile("My GeoHide", "EU.GEOHIDE.RU"), profile("DNS AI", "dns.dns-ai.ru"),
                profile("Google", "dns.google"), profile("Duplicate GeoHide", "eu.geohide.ru"));
        assertEquals(Arrays.asList("personal.example.org", "dns.dns-ai.ru", "eu.geohide.ru",
                "dns.comss.one", "dns.google"), hosts());
        assertEquals(Arrays.asList("Personal", "DNS AI", "My GeoHide", "My Comss", "Google"), names());
        assertEquals("dns.comss.one", DnsProfiles.selected(context).hostname);
        assertEquals("My Comss", DnsProfiles.selected(context).name);
    }

    @Test public void removedDnsAiIsNotReintroducedAndAddedProvidersStayRemovedLater() throws Exception {
        seed(1, profile("Personal", "personal.example.org"), profile("Google", "dns.google"));
        assertEquals(Arrays.asList("eu.geohide.ru", "dns.comss.one", "personal.example.org",
                "dns.google"), hosts());
        assertEquals("dns.google", DnsProfiles.selected(context).hostname);
        DnsProfiles.remove(context, 0);
        DnsProfiles.remove(context, 0);
        assertEquals(Arrays.asList("personal.example.org", "dns.google"), hosts());
        assertEquals("dns.google", DnsProfiles.selected(context).hostname);
        assertFalse(hosts().contains("dns.dns-ai.ru"));
    }

    @Test public void duplicateBeforeSelectionDoesNotChangeSelectedHostnameDuringMigration() throws Exception {
        seed(3, profile("DNS AI", "dns.dns-ai.ru"), profile("Original GeoHide", "eu.geohide.ru"),
                profile("Duplicate GeoHide", "eu.geohide.ru"), profile("My Google", "dns.google"));
        assertEquals(Arrays.asList("dns.dns-ai.ru", "eu.geohide.ru", "dns.comss.one", "dns.google"), hosts());
        assertEquals("dns.google", DnsProfiles.selected(context).hostname);
        assertEquals("Original GeoHide", names().get(1));
    }

    @Test public void capacityDoesNotDeleteProfilesAndMigrationDoesNotRetryLater() throws Exception {
        List<JSONObject> profiles = new ArrayList<>();
        profiles.add(profile("DNS AI", "dns.dns-ai.ru"));
        for (int i = 1; i < DnsProfiles.MAX_PROFILES; i++) {
            profiles.add(profile("Custom " + i, "provider" + i + ".example.org"));
        }
        seed(15, profiles.toArray(new JSONObject[0]));
        assertEquals(DnsProfiles.MAX_PROFILES, hosts().size());
        assertFalse(hosts().contains("eu.geohide.ru"));
        assertFalse(hosts().contains("dns.comss.one"));
        assertEquals("provider15.example.org", DnsProfiles.selected(context).hostname);
        assertTrue(preferences.getBoolean("geohide_comss_added", false));
        DnsProfiles.remove(context, 29);
        assertEquals(29, hosts().size());
        assertFalse(hosts().contains("eu.geohide.ru"));
    }

    @Test public void singleFreeSlotDoesNotDisplaceAnExistingSecondProvider() throws Exception {
        List<JSONObject> profiles = new ArrayList<>();
        profiles.add(profile("Existing Comss", "dns.comss.one"));
        profiles.add(profile("DNS AI", "dns.dns-ai.ru"));
        for (int i = 2; i < DnsProfiles.MAX_PROFILES - 1; i++) {
            profiles.add(profile("Custom " + i, "provider" + i + ".example.org"));
        }
        seed(0, profiles.toArray(new JSONObject[0]));
        assertEquals(DnsProfiles.MAX_PROFILES, hosts().size());
        assertEquals(Arrays.asList("dns.dns-ai.ru", "eu.geohide.ru", "dns.comss.one"),
                hosts().subList(0, 3));
        assertEquals("Existing Comss", DnsProfiles.selected(context).name);
        assertEquals("dns.comss.one", DnsProfiles.selected(context).hostname);
    }

    @Test public void legacySavedHostnameAndMalformedJsonKeepPreviousFallbackBehavior() {
        assertTrue(preferences.edit().putString("hostname", "legacy.example.org")
                .putString("profiles_json", "invalid-json").commit());
        assertEquals(Arrays.asList("legacy.example.org", "eu.geohide.ru", "dns.comss.one",
                "one.one.one.one", "dns.google"), hosts());
        assertEquals("legacy.example.org", DnsProfiles.selected(context).hostname);
        assertEquals(LanguageSettings.context(context).getString(R.string.saved_dns_name),
                DnsProfiles.selected(context).name);
    }

    private void seed(int selected, JSONObject... profiles) {
        JSONArray array = new JSONArray();
        for (JSONObject profile : profiles) array.put(profile);
        assertTrue(preferences.edit().putString("profiles_json", array.toString())
                .putInt("selected_profile_index", selected).commit());
    }

    private static JSONObject profile(String name, String host) throws Exception {
        return new JSONObject().put("name", name).put("host", host);
    }

    private List<String> hosts() {
        List<String> result = new ArrayList<>();
        for (DnsProfiles.Profile profile : DnsProfiles.all(context)) result.add(profile.hostname);
        return result;
    }

    private List<String> names() {
        List<String> result = new ArrayList<>();
        for (DnsProfiles.Profile profile : DnsProfiles.all(context)) result.add(profile.name);
        return result;
    }
}
