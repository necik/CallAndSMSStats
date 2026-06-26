# CallAndSMSStats

Android aplikace (Java), která na jediné obrazovce zobrazí seznam kalendářních
měsíců seřazený od aktuálního měsíce do minulosti. Každá položka ukazuje za daný
měsíc:

- celkový čas příchozích hovorů (HH:MM:SS),
- celkový čas odchozích hovorů (HH:MM:SS),
- počet zmeškaných hovorů,
- počet odmítnutých hovorů,
- počet příchozích SMS,
- počet odchozích SMS.

Pozn.: Některé výrobní nadstavby Androidu ukládají odmítnuté hovory
nekonzistentně (občas je systém zaznamená jako zmeškané), takže rozdělení na
zmeškané a odmítnuté závisí na klasifikaci konkrétního zařízení.

## Parametry projektu

- Package / applicationId: `cz.jirnec.callandsmsstats`
- Jazyk: Java
- minSdk 29 (Android 10), targetSdk / compileSdk 35
- Gradle 8.9, Android Gradle Plugin 8.6.0, Java 17

## Oprávnění

Aplikace vyžaduje runtime oprávnění `READ_CALL_LOG` a `READ_SMS`. O obě požádá
při prvním spuštění. Pozn.: tato dvě oprávnění Google Play silně omezuje — pro
zveřejnění na Play by bylo nutné speciální schválení. Pro vlastní instalaci
(sideload) žádné omezení neplatí.

## Sestavení a spuštění

1. Otevřete složku projektu v **Android Studiu** (Open → vyberte `CallAndSMSStats`).
2. Android Studio při synchronizaci doplní Gradle wrapper a `local.properties`
   (cesta k Android SDK).
3. Spusťte na fyzickém zařízení (emulátor obvykle nemá reálné hovory ani SMS).

Sestavení z příkazové řádky (po vygenerování wrapperu Android Studiem):

```
./gradlew assembleDebug
```
