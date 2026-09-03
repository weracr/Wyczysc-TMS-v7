#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
XML = ROOT / 'app/src/main/res/xml/accessibility_service_config.xml'

for p in (MAIN, SERV, XML):
    if not p.exists():
        raise SystemExit(f'Nie znaleziono: {p}')

m = MAIN.read_text(encoding='utf-8')
s = SERV.read_text(encoding='utf-8')

# MAIN: po instalacji użyj tej samej bezpośredniej metody, która działa z panelu admina.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
new = '''private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(this,
                "TMS zainstalowany. Otwieram ustawienia uprawnień.",
                Toast.LENGTH_LONG).show();
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        handler.postDelayed(() -> {
            setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
            openTmsSettings();
        }, 1400);
    }'''
if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall()')
m = re.sub(pat, new, m, count=1, flags=re.S)

# Usuń niepotrzebne przejście przez buildAdminScreen, ale zostaw metodę panelu ręcznego.
pat_admin = r'\n\s*private void openAdminRouteToTmsSettings\(\) \{.*?\n    \}\n'
m = re.sub(pat_admin, '\n', m, count=1, flags=re.S)

# Komunikat procesu nie wspomina o Device Owner.
m = m.replace(
    'Aplikacja odinstaluje TMS, zainstaluje najnowszą wersję APK z Download, spróbuje nadać uprawnienia programowo, a jeśli Android na to nie pozwoli, uruchomi flow przez ustawienia.',
    'Aplikacja odinstaluje TMS, zainstaluje najnowszą wersję APK z Download, ustawi lokalizację w Ustawieniach i uruchomi TMS, aby nadać pozostałe zgody.'
)

# SERVICE: końcowy ekran instalacji rozpoznawaj po treści, niezależnie od packageName.
pat_inst = r'private boolean isInstallerScreen\(String pkg, String text\) \{.*?\n    \}'
new_inst = '''private boolean isInstallerScreen(String pkg, String text) {
        boolean installing = text.contains("zainstaluj")
                || text.contains("instaluj")
                || text.contains("install")
                || text.contains("aktualizuj")
                || text.contains("update");
        boolean finished = text.contains("gotowe")
                || text.contains("done");
        return (installing || finished)
                && !text.contains("odinstaluj")
                && !text.contains("uninstall");
    }'''
if not re.search(pat_inst, s, flags=re.S):
    raise SystemExit('Nie znaleziono isInstallerScreen()')
s = re.sub(pat_inst, new_inst, s, count=1, flags=re.S)

# Ekrany Ustawień obsługuj najpierw po tekście elementu. Współrzędne są fallbackiem.
pat_coords = r'private boolean handlePm95SettingsCoordinates\(String rawText\) \{.*?\n    \}'
new_coords = '''private boolean handlePm95SettingsCoordinates(String rawText) {
        String text = normalize(rawText);

        if ((text.contains("informacje o aplikacji") || text.contains("app info"))
                && text.contains("uprawnienia")
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"))) {
            scheduleSettingsTextOrCoordinate(
                    "app_info_permissions",
                    Arrays.asList("Uprawnienia", "Permissions", "Brak przyznanych uprawnień", "Brak przyznanych uprawnien"),
                    185, 1465, 1600, 0);
            return true;
        }

        if ((text.contains("uprawnienia aplikacji") || text.contains("app permissions"))
                && text.contains("lokalizacja")) {
            scheduleSettingsTextOrCoordinate(
                    "permissions_location",
                    Arrays.asList("Lokalizacja", "Location"),
                    154, 1749, 1600, 0);
            return true;
        }

        if ((text.contains("lokalizacja - dostep") || text.contains("location access"))
                && text.contains("zawsze zezwalaj")) {
            scheduleSettingsTextOrCoordinate(
                    "always_allow",
                    Arrays.asList("Zawsze zezwalaj", "Allow all the time", "Always allow"),
                    112, 1145, 1700, 2);
            return true;
        }

        return false;
    }'''
if not re.search(pat_coords, s, flags=re.S):
    raise SystemExit('Nie znaleziono handlePm95SettingsCoordinates()')
s = re.sub(pat_coords, new_coords, s, count=1, flags=re.S)

helper = '''    private void scheduleSettingsTextOrCoordinate(String stage, List<String> labels,
                                                  int x, int y, long delayMs, int backsAfter) {
        if (settingsCoordinatePending || stage.equals(lastSettingsStage)) return;
        settingsCoordinatePending = true;
        lastSettingsStage = stage;

        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                boolean clicked = current != null && clickVisibleText(current, labels);
                if (!clicked) {
                    clicked = tapPhysicalPointPm95(x, y);
                }

                if (clicked) {
                    markClicked();
                    if (backsAfter > 0) {
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            handler.postDelayed(() -> {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                handler.postDelayed(this::launchTmsFromService, 1000);
                            }, 900);
                        }, 1500);
                    }
                }
            } finally {
                settingsCoordinatePending = false;
                handler.postDelayed(() -> lastSettingsStage = "", 900);
            }
        }, delayMs);
    }

'''
marker = '    private void scheduleSettingsCoordinate('
if 'private void scheduleSettingsTextOrCoordinate(' not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na scheduleSettingsTextOrCoordinate()')
    s = s.replace(marker, helper + marker, 1)

# Gotowe: po kliknięciu sam ekran zniknie i MainActivity.onResume poprowadzi dalej.
# Skróć blokadę, aby klik tekstowy w Ustawieniach nie był odrzucony po poprzednim etapie.
s = re.sub(r'private static final long CLICK_GUARD_MS = \d+;',
           'private static final long CLICK_GUARD_MS = 800;', s, count=1)

# XML zapisujemy od zera, bez ryzyka HTML.
xml = '''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews" />
'''

for name, text in [('MainActivity.java', m), ('PermissionClickerAccessibilityService.java', s)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
        if token in text:
            raise SystemExit(f'{name}: znaleziono HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: niezgodne klamry {text.count("{")} / {text.count("}")}')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
XML.write_text(xml, encoding='utf-8')
print('OK: poprawiono Gotowe oraz przejście Uprawnienia -> Lokalizacja -> Zawsze zezwalaj')
