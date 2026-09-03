#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not MAIN.exists() or not SERV.exists():
    raise SystemExit('Uruchom w glownym katalogu repo, obok folderu app.')

m = MAIN.read_text(encoding='utf-8')
s = SERV.read_text(encoding='utf-8')

# 1. Najważniejsza poprawka: budowanie widoku nie może zerować trwającego flow.
m = m.replace('''    private void buildDriverScreen() {
        clearFlowMode();
''', '''    private void buildDriverScreen() {
''', 1)
m = m.replace('''    private void buildAdminScreen() {
        clearFlowMode();
''', '''    private void buildAdminScreen() {
''', 1)

# 2. Po instalacji bezpośrednio otwórz działającą ścieżkę App Info.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
new = '''private void grantTmsPermissionsAfterInstall() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this,
                "TMS zainstalowany. Otwieram ustawienia uprawnień.",
                Toast.LENGTH_LONG).show();
        handler.postDelayed(() -> {
            setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
            openTmsSettings();
        }, 1400);
    }'''
if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall()')
m = re.sub(pat, new, m, count=1, flags=re.S)

# 3. Dodaj wymuszone etapy przed dotychczasowym detectAction.
needle = '''        String text = normalize(collectText(root));'''
if needle not in s:
    raise SystemExit('Nie znaleziono tekstu ekranu w handleCurrentScreen()')
block = needle + '''

        // PM95: wymuszone, pojedyncze etapy po treści ekranu.
        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && text.contains("aplikacja zostala zainstalowana")
                && text.contains("gotowe")) {
            forceStageTap("installer_done", 650, 1180, 1300, 0);
            return;
        }

        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)
                && (text.contains("informacje o aplikacji") || text.contains("app info"))
                && text.contains("uprawnienia")) {
            forceStageTap("app_info_permissions", 185, 1465, 1400, 0);
            return;
        }

        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)
                && (text.contains("uprawnienia aplikacji") || text.contains("app permissions"))
                && text.contains("lokalizacja")) {
            forceStageTap("permissions_location", 154, 1749, 1400, 0);
            return;
        }

        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)
                && text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")) {
            forceStageTap("always_allow", 112, 1145, 1500, 2);
            return;
        }'''
s = s.replace(needle, block, 1)

# 4. Niezależna kolejka gestów. Nie używa canClickNow ani starych flag.
helper = '''    private boolean forcedStagePending = false;
    private String forcedStageKey = "";

    private void forceStageTap(String key, int x, int y, long delayMs, int backsAfter) {
        if (forcedStagePending || key.equals(forcedStageKey)) return;
        forcedStagePending = true;
        forcedStageKey = key;

        handler.postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 80, 220);
            GestureDescription gesture =
                    new GestureDescription.Builder().addStroke(stroke).build();

            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    forcedStagePending = false;
                    lastClickTime = System.currentTimeMillis();
                    if (backsAfter > 0) {
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            handler.postDelayed(() -> {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                handler.postDelayed(thisService()::launchTmsFromService, 1000);
                            }, 900);
                        }, 1400);
                    }
                    handler.postDelayed(() -> forcedStageKey = "", 900);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    forcedStagePending = false;
                    handler.postDelayed(() -> forcedStageKey = "", 500);
                }
            }, null);
        }, delayMs);
    }

    private PermissionClickerAccessibilityService thisService() {
        return this;
    }

'''
marker = '    private Action detectAction('
if 'private void forceStageTap(' not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na forceStageTap()')
    s = s.replace(marker, helper + marker, 1)

# 5. Dialog odinstalowania też wymuszony bez zależności od pakietu.
uninstall = '''        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && (text.contains("odinstalowac te aplikacje") || text.contains("odinstaluj"))) {
            scheduleSettingsCoordinate("uninstall_ok", 861, 1169, 1500, 0);
            return;
        }'''
uninstall_new = '''        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && (text.contains("odinstalowac te aplikacje") || text.contains("odinstaluj"))) {
            forceStageTap("uninstall_ok", 861, 1169, 1400, 0);
            return;
        }'''
if uninstall in s:
    s = s.replace(uninstall, uninstall_new, 1)

# 6. Kontrole.
for name, text in [('MainActivity.java', m), ('Service.java', s)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
        if token in text:
            raise SystemExit(f'{name}: HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: klamry {text.count("{")} / {text.count("}")}')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
print('OK: zachowano flow i wymuszono Gotowe, Uprawnienia, Lokalizacja oraz Zawsze zezwalaj')
