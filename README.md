# CallAndSMSStats

Android app (Java) giving an overview of your calls and SMS by calendar month.

## Features

### Main screen
A list of calendar months, sorted from the current month back into the past.
Each item shows, for that month:

- total time and count of incoming calls (HH:MM:SS · count),
- total time and count of outgoing calls (HH:MM:SS · count),
- number of missed calls,
- number of rejected calls,
- number of incoming SMS,
- number of outgoing SMS.

> Note: Some manufacturer Android skins store rejected calls inconsistently
> (they are sometimes recorded as missed), so the split between missed and
> rejected depends on how the specific device classifies them.

### Month detail
Tapping a month opens a chronological list (newest first) of the individual
calls and SMS the summary was built from — handy for verifying the numbers on
the card. Each record shows the type, contact/number, date and time, and for
completed calls also the duration.

- **Quick filtering** by event type (chips): All, Incoming calls, Outgoing
  calls, Missed, Rejected, Incoming SMS, Outgoing SMS. The last selected filter
  is remembered (even after a restart).
- **Names for SMS**: if the sender is a phone number stored in contacts, the
  name is shown; an alphanumeric sender ID (e.g. "Vodafone") and numbers not in
  the address book are kept as-is. For calls the name comes from the call log
  (`CACHED_NAME`).

### Data export
The **Export** action in the top bar exports all data (summaries and details for
all months) into a single file and opens the system **share sheet** — the file
can be saved to Google Drive, sent by e-mail, saved to Files, etc.

- Choice of format: **CSV** (two sections `# SUMMARY` and `# DETAILS`, opens in
  Sheets/Excel) or **JSON** (hierarchical per month, for machine processing).
- Date/time in the export uses the stable, locale-independent form
  `yyyy-MM-dd HH:mm:ss` so the file is portable.
- **Requires no extra permission** — the file is written to the app cache and
  shared via `FileProvider`.

## Permissions

| Permission | Purpose | Required |
|---|---|---|
| `READ_CALL_LOG` | call durations and types | yes |
| `READ_SMS` | SMS counts | yes |
| `READ_CONTACTS` | names for SMS from the address book | no (number is shown without it) |

The app requests permissions on launch. Note: Google Play heavily restricts
`READ_CALL_LOG` and `READ_SMS` — publishing on Play would require special
approval. There is no such restriction for private (sideload) installation.

## Privacy

**The app has no internet access, so it cannot leak or misuse your sensitive
data.** It does not declare the `INTERNET` permission, which means the operating
system blocks any network connection — the app is technically incapable of
sending call and SMS data anywhere.

- All processing happens **locally on the device**; nothing is uploaded to any
  server and there is no analytics or tracking.
- Data leaves the device **only when you explicitly export it** and choose a
  destination yourself in the system share sheet (see [Data export](#data-export)).

## Technical details

- Package / applicationId: `cz.jirnec.callandsmsstats`
- Language: Java 17
- minSdk 29 (Android 10), targetSdk / compileSdk 35
- Android Gradle Plugin 8.6.0, Gradle 8.7+ (tested with 8.14.5)
- Edge-to-edge (Android 15+) handled via window insets
- Default language **English**; Czech is shown on devices with a Czech locale
  (`values/` = EN, `values-cs/` = CS). Month names and formats follow the device
  language.
- Modern look with sharp corners (Material 3, shape appearance 0 dp, outlined
  cards)

## Building without Android Studio (CLI / VSCode)

You only need JDK 17 + Android SDK command-line tools + Gradle. Before building,
`java` (JDK 17) must be available and the Android SDK path must be set — either
via the `ANDROID_HOME` environment variable or a `local.properties` file in the
project root:

```
sdk.dir=C:/Users/<name>/AppData/Local/Android/Sdk
```

Required SDK packages:

```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Debug build and install onto a connected phone (USB debugging enabled):

```
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### VSCode

The project includes `.vscode/tasks.json` with tasks (Ctrl+Shift+P → **Run Task**):

- **Android: Build (assembleDebug)** — also on Ctrl+Shift+B
- **Android: Build Release (assembleRelease)**
- **Android: Install APK**
- **Android: Launch app**
- **Android: Build + Install + Launch**
- **Android: Devices (adb)**

Running on a **physical phone** is recommended (an emulator has no real calls or SMS).

## Release build and signing

The release APK is signed with your own key. The credentials are read from a
`keystore.properties` file in the project root (**not committed to git**):

```
storeFile=release.jks
storePassword=…
keyAlias=callandsmsstats
keyPassword=…
```

Generate the key (once) and build:

```
keytool -genkeypair -v -keystore release.jks -alias callandsmsstats \
  -keyalg RSA -keysize 2048 -validity 10000

gradle assembleRelease
```

Signed APK: `app/build/outputs/apk/release/app-release.apk`.

> ⚠️ Back up `release.jks` and its password carefully. Every future update must
> be signed with the **same key**, otherwise devices will refuse to install it
> over the existing version. Neither the key nor `keystore.properties` are
> committed to git (they are in `.gitignore`).

## Distribution

Releases are published on **GitHub Releases** of the `necik/CallAndSMSStats`
repository. The APK is downloaded and installed manually (requires allowing
installation from unknown sources).

## License

This project is licensed under the **GNU General Public License v3.0** — see the
[LICENSE](LICENSE) file.

Copyright (C) 2026 Jiří Nečas

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU GPL v3 as published by the Free Software Foundation. This
program is distributed WITHOUT ANY WARRANTY. See the license text for details.
