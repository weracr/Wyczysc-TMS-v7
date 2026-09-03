#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
if not SERV.exists() or not MAIN.exists():
    raise SystemExit('Uruchom w glownym katalogu repo, obok folderu app.')

s = SERV.read_text(encoding='utf-8')
m = MAIN.read_text(encoding='utf-8')

# MainActivity: po instalacji od razu otworz App Info, bez Device Owner/Profile Owner.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
new = '''private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(this,
                "Ustawiam lokalizację TMS przed pierwszym uruchomieniem.",
                Toast.LENGTH_LONG).show();
        openTmsSettingsBeforeFirstLaunch();
    }'''
if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall()')
m = re.sub(pat, new, m, count=1, flags=re.S)

if 'private void openTmsSettingsBeforeFirstLaunch()' not in m:
    method = '''
    private void openTmsSettingsBeforeFirstLaunch() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

'''
    marker = '    private void launchTmsForRuntimePermissions() {'
    if marker not in m:
        raise SystemExit('Nie znaleziono miejsca na metode ustawien')
    m = m.replace(marker, method + marker, 1)

# W serwisie dodaj pola etapow.
field_anchor = 'private long lastClickTime = 0;'
if field_anchor not in s:
    field_anchor = 'private long lastClickTime;'
if field_anchor not in s:
    raise SystemExit('Nie znaleziono lastClickTime')
if 'private boolean settingsCoordinatePending' not in s:
    s = s.replace(field_anchor, field_anchor + '''
    private boolean settingsCoordinatePending = false;
    private String lastSettingsStage = "";''', 1)

# Handler koordynatow wywoluj przed zwyklym detectAction/handlerami.
needles = [
    'String text = normalize(collectText(root));',
    'String screenText = normalize(collectText(root) + " " + collectEventText(event));'
]
needle = next((n for n in needles if n in s), None)
if not needle:
    raise SystemExit('Nie znaleziono miejsca po zebraniu tekstu ekranu')
var = 'text' if needle.startswith('String text') else 'screenText'
insert = needle + f'''\n\n        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)\n                && handlePm95SettingsCoordinates({var})) {{\n            return;\n        }}'''
if 'handlePm95SettingsCoordinates(' not in s:
    s = s.replace(needle, insert, 1)
elif '&& handlePm95SettingsCoordinates(' not in s:
    s = s.replace(needle, insert, 1)

methods = '''    private boolean handlePm95SettingsCoordinates(String rawText) {
        String text = normalize(rawText);

        // 1. Informacje o aplikacji -> Uprawnienia. Punkt ze screena: X=185 Y=1465.
        if ((text.contains("informacje o aplikacji") || text.contains("app info"))
                && text.contains("uprawnienia")
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"))) {
            scheduleSettingsCoordinate("app_info_permissions", 185, 1465, 1600, 0);
            return true;
        }

        // 2. Uprawnienia aplikacji -> Lokalizacja. Punkt ze screena: X=154 Y=1749.
        if ((text.contains("uprawnienia aplikacji") || text.contains("app permissions"))
                && text.contains("lokalizacja")) {
            scheduleSettingsCoordinate("permissions_location", 154, 1749, 1600, 0);
            return true;
        }

        // 3. Lokalizacja - dostep -> Zawsze zezwalaj. Punkt ze screena: X=112 Y=1145.
        if ((text.contains("lokalizacja - dostep") || text.contains("location access"))
                && text.contains("zawsze zezwalaj")) {
            scheduleSettingsCoordinate("always_allow", 112, 1145, 1700, 2);
            return true;
        }

        return false;
    }

    private void scheduleSettingsCoordinate(String stage, int x, int y,
                                            long delayMs, int backsAfter) {
        if (settingsCoordinatePending || stage.equals(lastSettingsStage)) return;
        settingsCoordinatePending = true;
        lastSettingsStage = stage;

        handler.postDelayed(() -> {
            try {
                tapPhysicalPointPm95(x, y);
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
            } finally {
                settingsCoordinatePending = false;
                handler.postDelayed(() -> lastSettingsStage = "", 1200);
            }
        }, delayMs);
    }

    private boolean tapPhysicalPointPm95(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 80, 180);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private void launchTmsFromService() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("pl.optidata.tms_android_2017");
        if (launch == null) {
            setFlowMode(MODE_IDLE);
            Toast.makeText(this, "Nie znaleziono aplikacji TMS.", Toast.LENGTH_LONG).show();
            return;
        }
        setFlowMode(MODE_OPEN_TMS);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
    }

'''
if 'private boolean handlePm95SettingsCoordinates(' not in s:
    # this can't happen after call insertion; marker fallback
    pass
if 'private void scheduleSettingsCoordinate(' not in s:
    markers = ['    private boolean clickVisibleText(', '    private boolean tapAt(', '    private boolean isAppInfoScreen(']
    marker = next((x for x in markers if x in s), None)
    if not marker:
        raise SystemExit('Nie znaleziono miejsca na metody koordynatow')
    s = s.replace(marker, methods + marker, 1)

# Dialog odinstalowania: dokładny punkt OK ze screena X=861 Y=1169 jako fallback.
# Dodaj osobna obsluge przed detectAction.
uninstall_call = f'''\n        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))\n                && (text.contains("odinstalowac te aplikacje") || text.contains("odinstaluj"))) {{\n            scheduleSettingsCoordinate("uninstall_ok", 861, 1169, 1500, 0);\n            return;\n        }}'''
if needle + uninstall_call not in s and '"uninstall_ok", 861, 1169' not in s:
    s = s.replace(needle, needle + uninstall_call, 1)

# Accessibility XML bez packageNames i z pelnym dostepem.
for xp in (ROOT / 'app/src/main/res').rglob('*.xml'):
    xs = xp.read_text(encoding='utf-8', errors='ignore')
    if '<accessibility-service' not in xs:
        continue
    xs = re.sub(r'\s+android:packageNames="[^"]*"', '', xs)
    for attr, value in [
        ('android:canPerformGestures', 'true'),
        ('android:canRetrieveWindowContent', 'true'),
        ('android:accessibilityEventTypes', 'typeAllMask'),
        ('android:notificationTimeout', '100')
    ]:
        if re.search(rf'{re.escape(attr)}="[^"]*"', xs):
            xs = re.sub(rf'{re.escape(attr)}="[^"]*"', f'{attr}="{value}"', xs)
        else:
            xs = xs.replace('<accessibility-service', f'<accessibility-service\n    {attr}="{value}"', 1)
    xp.write_text(xs, encoding='utf-8')

for name, text in [('MainActivity.java', m), ('Service.java', s)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
        if token in text:
            raise SystemExit(f'{name}: HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: niezgodne klamry {text.count("{")} / {text.count("}")}')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
print('OK: dodano dokładną ścieżkę PM95 App Info -> Uprawnienia -> Lokalizacja -> Zawsze zezwalaj -> TMS')
