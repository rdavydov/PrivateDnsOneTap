# Private DNS Switcher v3 — widgets, Shizuku setup and themes

A lightweight Java / Android Studio project intended for **Honor Magic V2, MagicOS 10, Android 16, Nova Launcher Prime**. It changes the **actual Android Private DNS Manual hostname**. No root, VPN, ongoing background service, tracker, ads, or internet permission is required. Grant the required system permission once using **ADB** or optional **Shizuku**.

The app supports light and dark themes. The app and every placed widget default independently to **System default**, and each can be set to stay light or dark.

## New in v3: a REAL 1x1 widget

This version adds a separate Android widget provider named **Private DNS · Mini (1x1)**. It is designed to fit **one Nova Launcher grid cell**. The previous 2x1 and 4x2 layouts are still provided as **Private DNS Switcher**.

The mini widget has **two click zones** to keep them reasonably usable at 1x1:

```text
+------------+
|            |
|     ON     |   Tap main area: Manual  <-> Off
|            |
+------------+
| DNS AI  v   |   Tap bottom label: open compact DNS picker
+------------+
```

The mini picker appears as a dialog-sized window. It offers:

- **Tap a saved DNS profile:** immediately write that hostname to the Manual input and enable Private DNS, then close the picker.
- **Prev / Next:** cycle saved providers (and enable them) from the popup.
- **On / Off:** toggle the selected Manual DNS server.
- **Manage DNS profiles:** open the existing editor to add, rename, edit, delete, or apply providers.
- **Widget theme:** choose System default, Light or Dark for the particular mini widget that opened the picker.
- At most **30 profiles**. Fresh installations start with **DNS AI** (`dns.dns-ai.ru`), **GeoHide** (`eu.geohide.ru`), **Comss** (`dns.comss.one`), **Cloudflare** (`one.one.one.one`), and **Google** (`dns.google`), in that order.

The widget shows **ON**, **OFF**, **AUTO**, **OTHER**, or **SETUP** (missing system permission) and displays the selected profile name. If an external app changes the system DNS to an unselected Manual hostname, the mini widget displays **OTHER** rather than falsely saying that your selected hostname is active.

Why a popup? One 1x1 cell can be as narrow as 57dp on Android launchers. Putting four independent small buttons on it would make accidental taps common. Standard Android RemoteViews widgets also do not offer reliable custom long-press or swipe gestures: the launcher normally owns long-press. The two-zone tile keeps immediate On/Off available while retaining all profile controls one tap away.

## Build with Android Studio on Windows 11

1. Place the source project in a folder such as `C:\AndroidProjects\PrivateDnsOneTap`.
2. If `gradle\wrapper\gradle-wrapper.jar` is missing, run `prepare-wrapper.bat` (downloads the official Gradle wrapper JAR; internet required).
3. In Android Studio select **File > Open** and choose the **PrivateDnsOneTap** folder.
4. Sync Gradle and install SDK Platform 36 when prompted. Use JDK 17 or 21; the local project configuration uses JDK 21.
5. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. Install `app\build\outputs\apk\debug\app-debug.apk` onto the phone (either via Android Studio or ADB):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

The project uses Android Gradle Plugin 8.13.2, Gradle 8.13, `compileSdk=36`, `targetSdk=36`, `minSdk=28`, and Java 17 source compatibility. It includes the [Shizuku API and provider](https://github.com/RikkaApps/Shizuku-API) libraries, both pinned to `13.1.5`, with AndroidX enabled. Shizuku itself is an optional app, needed only if you choose that setup method. AIDL is enabled for the short-lived permission service.

## One-time system permission: Shizuku or ADB

Android requires `WRITE_SECURE_SETTINGS` to change Private DNS. It has no ordinary Android runtime permission prompt. Either method below grants the same permission; if it is already granted, no further setup is needed.

### Shizuku

1. Install [official Shizuku](https://shizuku.rikka.app/download/) version 13 or newer, then start it using its [setup guide](https://shizuku.rikka.app/guide/setup/). On supported devices, wireless debugging lets you start it without a computer.
2. Open Private DNS Switcher and tap **Grant through Shizuku** under **One-time system permission**.
3. Approve Shizuku's access request. The app then grants its own `WRITE_SECURE_SETTINGS` permission and verifies Android actually granted it.
4. Once **System permission: GRANTED** appears, the app and widgets work directly. Shizuku can be stopped; it is not needed for subsequent DNS switches or after a reboot while the system permission remains granted.

The setup screen distinguishes missing/stopped Shizuku, pending approval, denied access and command errors. If access was permanently denied, allow Private DNS Switcher in Shizuku's authorized apps, then return and retry. Requests occur only after tapping the button. If device policy or OEM restrictions reject the grant, the ADB option remains available. Removing Shizuku authorization does not revoke the separately granted Android system permission; use the revoke command below for that.

### ADB

Enable **Developer options / USB debugging**, connect to the Windows computer and authorize ADB. Execute:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm grant dev.localswitch.privatedns android.permission.WRITE_SECURE_SETTINGS
```

The package ID is still **`dev.localswitch.privatedns`**. When installing an update **over** a previously signed v1/v2/v3 build using the same signing key, Android should preserve saved profiles and the system permission. If you compiled debug builds on different Windows PCs, the keys can differ; Android may reject an in-place update. Use the old keystore/signing key to keep the existing app data. **Do not uninstall the old version just to update unless you've recorded your profiles**: uninstalling discards them and the system permission.

On the first run of this update, GeoHide and Comss are inserted immediately after DNS AI in an existing profile list, or at the beginning if DNS AI was removed. Existing copies keep their custom display names and are moved into that order without duplicates. Other profiles retain their relative order and the selected hostname remains selected. No user profiles are removed to make room at the 30-profile limit. This migration runs once; deleting either added provider afterward does not bring it back. A legacy v1 saved hostname is still retained as the initial selection.

After installation in **Nova Launcher Prime**: long-press on home screen > **Widgets** > **Private DNS · Mini (1x1)**. If Nova cached the old widget list, reopen Nova's widget picker, or restart Nova Launcher. The previously placed bigger widgets should continue working.

## App and widget themes

- **App:** under **Appearance**, tap **App theme** and choose **System default**, **Light**, or **Dark**. The choice is saved. System default follows the current Android theme; Light and Dark stay fixed. The main screen, profile editor, confirmation dialogs and DNS picker all follow this app setting.
- **Large/compact widget:** tap that widget's gear button, then **This widget theme** on the opened app screen.
- **Mini widget:** tap that widget's bottom label, then **Widget theme** in the popup.

Every placed widget has its own saved choice. For example, one widget can always be light while another stays dark and a third follows Android. Changing a widget's theme updates that instance immediately and does not change the app theme or other widgets. The DNS picker itself follows the app theme, even when opened from a widget with a different theme.

New and existing widgets without a saved preference use **System default**. Resizing preserves the instance's choice. Removing a widget deletes its preference; when Android delivers a widget-ID restoration, the preference is transferred to the replacement ID. Theme changes do not change DNS settings or saved profiles.

## Verify the real system state

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell settings get global private_dns_mode
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell settings get global private_dns_specifier
```

Expected examples:

| Operation | `private_dns_mode` | `private_dns_specifier` |
|---|---|---|
| Apply DNS AI | `hostname` | `dns.dns-ai.ru` |
| Apply Cloudflare | `hostname` | `one.one.one.one` |
| Apply Google | `hostname` | `dns.google` |
| Tap OFF | `off` | Last applied hostname remains stored |

The app writes `private_dns_specifier` first, then switches `private_dns_mode` to `hostname`. Turning off only writes `private_dns_mode=off`. This matches the **Off / Manual** UI in your screenshots; it does not substitute a local VPN.

## Project structure

- `DnsMiniWidgetProvider.java` — dedicated 1x1 widget, two click zones.
- `ProfilePickerActivity.java` — tiny native dialog popup and profile picker.
- `DnsWidgetProvider.java` — existing larger widget(s), now also refreshes mini widgets.
- `DnsProfiles.java` — existing local profile storage and migration.
- `DnsSettings.java` — reads/writes the actual Android Private DNS global settings.
- `MainActivity.java` — advanced profile editor, permission setup and appearance settings.
- `ShizukuPermissionController.java` / `PermissionGrantService.java` — optional one-time Shizuku setup.
- `ThemeSettings.java` / `ThemedActivity.java` — app-only appearance selection.
- `WidgetThemes.java` — independent per-widget preferences, rendering and restoration.
- `ToggleReceiver.java` — privileged one-tap On/Off action.
- `res/layout/widget_private_dns_mini.xml` — small tile.
- `res/xml/dns_mini_widget_info.xml` — declares 1x1 target size.

## Limitations

- Device owners / policies, work profiles, VPNs, and Honor's OEM modifications may block or override DNS changes even if the system permission is granted. Test on your own device.
- DNS-over-TLS port 853 must be reachable. This app does not benchmark or probe providers.
- The chooser is a small dialog window **centered on screen**, not a context menu geometrically attached to the 1x1 widget.
- When DNS is changed by another app, widget updates can lag until the regular system widget refresh (Android may limit it to roughly 30 minutes). Tapping the widget always reads the current settings first.
- System-following widget colors update on system-theme changes while the app process is running. When it is not running, theme refresh depends on the launcher's resource/layout refresh behavior. Immediate refresh on every Android version and launcher is not guaranteed; fixed Light/Dark widgets retain their chosen palette.
- `WRITE_SECURE_SETTINGS` is a broad privileged permission. Review and compile the source yourself; don't grant it to untrusted APKs.

## Validation

Build and lint with `gradlew.bat :app:assembleDebug :app:lintDebug`; run the Android instrumentation tests with `gradlew.bat :app:connectedDebugAndroidTest` when an emulator/device is connected. Instrumentation and emulator results are distinct from a physical-device check. Honor Magic V2 / MagicOS and Nova Launcher behavior still require verification on the actual device; no physical-device validation is implied by the emulator checks.

Validated on 2026-11-13 with JDK 21 and an Android API 37 emulator:

- Debug APK build succeeded; Android Lint reported **0 errors** (84 warnings about hard-coded text, RTL and resource suggestions).
- **24 instrumentation tests passed**: app/widget palettes and independence, widget ownership/deletion/ID restoration, editor recovery, DNS-list migration, and isolated Shizuku denial, disconnection, timeout and permission-verification failures.
- Official Shizuku 13.6.0 successfully granted the real system permission. Turning DNS off/on still worked after stopping Shizuku, and the temporary permission process exited.
- Emulator launcher checks covered simultaneously placed widgets with different themes, preserving a forced theme when resizing to the compact layout, and system-theme changes after the app process was killed normally. Force-stopping an app is different: Android can mask its widgets until the app is opened again.

DNS provider availability and DNS-over-TLS connectivity were not probed by these checks.

## Revoke privileged permission

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm revoke dev.localswitch.privatedns android.permission.WRITE_SECURE_SETTINGS
```
