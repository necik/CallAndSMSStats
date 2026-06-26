# CallAndSMSStats

Android aplikace (Java) pro přehled o telefonování a SMS po kalendářních měsících.

## Funkce

### Hlavní obrazovka
Seznam kalendářních měsíců seřazený od aktuálního měsíce do minulosti. Každá
položka ukazuje za daný měsíc:

- celkový čas příchozích hovorů (HH:MM:SS),
- celkový čas odchozích hovorů (HH:MM:SS),
- počet zmeškaných hovorů,
- počet odmítnutých hovorů,
- počet příchozích SMS,
- počet odchozích SMS.

> Pozn.: Některé výrobní nadstavby Androidu ukládají odmítnuté hovory
> nekonzistentně (občas je systém zaznamená jako zmeškané), takže rozdělení na
> zmeškané a odmítnuté závisí na klasifikaci konkrétního zařízení.

### Detail měsíce
Klepnutím na měsíc se otevře chronologický seznam (od nejnovějšího) jednotlivých
hovorů a SMS, ze kterých souhrn vznikl — slouží ke kontrole čísel na kartě.
U každého záznamu je typ, kontakt/číslo, datum a čas a u uskutečněných hovorů
i délka.

- **Rychlé filtrování** podle typu události (chips): Vše, Příchozí hovory,
  Odchozí hovory, Zmeškané, Odmítnuté, Příchozí SMS, Odchozí SMS. Naposledy
  zvolený filtr se zapamatuje (i po restartu).
- **Jména u SMS**: pokud je odesílatel telefonní číslo uložené v kontaktech,
  zobrazí se jméno; textové ID odesílatele (např. „Vodafone") i čísla mimo
  adresář zůstanou tak, jak jsou. U hovorů se jméno bere z deníku hovorů
  (`CACHED_NAME`).

## Oprávnění

| Oprávnění | Účel | Povinné |
|---|---|---|
| `READ_CALL_LOG` | délky a typy hovorů | ano |
| `READ_SMS` | počty SMS | ano |
| `READ_CONTACTS` | jména u SMS podle adresáře | ne (bez něj se ukáže číslo) |

O oprávnění aplikace požádá při spuštění. Pozn.: `READ_CALL_LOG` a `READ_SMS`
Google Play silně omezuje — pro zveřejnění na Play by bylo nutné speciální
schválení. Pro vlastní instalaci (sideload) žádné omezení neplatí.

## Technické parametry

- Package / applicationId: `cz.jirnec.callandsmsstats`
- Jazyk: Java 17
- minSdk 29 (Android 10), targetSdk / compileSdk 35
- Android Gradle Plugin 8.6.0, Gradle 8.7+ (testováno s 8.14.5)
- Edge-to-edge (Android 15+) ošetřeno přes window insets

## Sestavení bez Android Studia (CLI / VSCode)

Stačí JDK 17 + Android SDK command-line tools + Gradle. Před buildem musí být
dostupné `java` (JDK 17) a cesta k Android SDK — buď přes proměnnou prostředí
`ANDROID_HOME`, nebo souborem `local.properties` v kořeni projektu:

```
sdk.dir=C:/Users/<jmeno>/AppData/Local/Android/Sdk
```

Potřebné SDK balíčky:

```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Debug build a instalace na připojený telefon (zapnuté Ladění USB):

```
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### VSCode

V projektu je `.vscode/tasks.json` s úlohami (Ctrl+Shift+P → **Run Task**):

- **Android: Build (assembleDebug)** — také pod Ctrl+Shift+B
- **Android: Install APK**
- **Android: Launch app**
- **Android: Build + Install + Launch**
- **Android: Devices (adb)**

Spouštět doporučeno na **fyzickém telefonu** (emulátor nemá reálné hovory ani SMS).

## Release build a podpis

Release APK se podepisuje vlastním klíčem. Údaje se čtou ze souboru
`keystore.properties` v kořeni projektu (**není v gitu**):

```
storeFile=release.jks
storePassword=…
keyAlias=callandsmsstats
keyPassword=…
```

Vygenerování klíče (jednorázově) a build:

```
keytool -genkeypair -v -keystore release.jks -alias callandsmsstats \
  -keyalg RSA -keysize 2048 -validity 10000

gradle assembleRelease
```

Podepsané APK: `app/build/outputs/apk/release/app-release.apk`.

> ⚠️ Soubor `release.jks` a jeho heslo pečlivě zazálohujte. Pro všechny budoucí
> aktualizace je nutné podepisovat **stejným klíčem**, jinak telefony odmítnou
> instalaci přes stávající verzi. Klíč ani `keystore.properties` se do gitu
> nekomitují (jsou v `.gitignore`).

## Distribuce

Vydané verze jsou na **GitHub Releases** repozitáře `necik/CallAndSMSStats`.
APK se stáhne a nainstaluje ručně (vyžaduje povolení instalace z neznámých
zdrojů).
