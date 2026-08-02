#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not MAIN.exists():
    raise SystemExit(f'Nie znaleziono: {MAIN}')
if not SERV.exists():
    raise SystemExit(f'Nie znaleziono: {SERV}')

m = MAIN.read_text(encoding='utf-8')
s = SERV.read_text(encoding='utf-8')

# v64 PM95 final flow:
# install -> launch TMS -> native runtime dialogs -> TMS location message
# -> UPDATE SETTINGS -> Always allow -> BACK to TMS -> finish.

# ------------------------------------------------------------------
# MainActivity: after installation, launch TMS for native dialogs.
# ------------------------------------------------------------------
new_after = '''private void grantTmsPermissionsAfterInstall() {
        boolean grantedByPolicy = grantTmsPermissionsByPolicy();

        if (grantedByPolicy) {
            clearFlowMode();
            Toast.makeText(this, "Gotowe. Uprawnienia zostały nadane. Można uruchomić TMS.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Uruchamiam TMS i nadaję zgody po kolei.", Toast.LENGTH_LONG).show();
        launchTmsForRuntimePermissions();
    }

    private void launchTmsForRuntimePermissions() {
        setFlowMode(MODE_OPEN_TMS);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTmsPackage);
        if (launchIntent == null) {
            clearFlowMode();
            Toast.makeText(this, "Nie znaleziono aplikacji TMS po instalacji.", Toast.LENGTH_LONG).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launchIntent);
    }'''
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall()')
m = re.sub(pat, new_after, m, flags=re.S, count=1)

# Remove duplicate launch helper if patch is reapplied.
matches = list(re.finditer(r'private void launchTmsForRuntimePermissions\(\) \{.*?\n    \}', m, flags=re.S))
if len(matches) > 1:
    for item in reversed(matches[1:]):
        m = m[:item.start()] + m[item.end():]

# ------------------------------------------------------------------
# Service state.
# ------------------------------------------------------------------
if 'private boolean waitingForAlwaysLocation' not in s:
    anchor = 'private boolean finalToastShown = false;'
    s = s.replace(anchor, anchor + '''
    private boolean waitingForAlwaysLocation = false;
    private long lastRuntimePermissionActionTime = 0;
    private int runtimePermissionsClicked = 0;''')
else:
    # Add missing counters independently where necessary.
    if 'private long lastRuntimePermissionActionTime' not in s:
        s = s.replace('private boolean waitingForAlwaysLocation = false;',
                      'private boolean waitingForAlwaysLocation = false;\n    private long lastRuntimePermissionActionTime = 0;')
    if 'private int runtimePermissionsClicked' not in s:
        s = s.replace('private long lastRuntimePermissionActionTime = 0;',
                      'private long lastRuntimePermissionActionTime = 0;\n    private int runtimePermissionsClicked = 0;')

# ------------------------------------------------------------------
# Runtime dialogs shown by PermissionController.
# ------------------------------------------------------------------
new_is_runtime = '''private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        String text = normalize(screenText);
        boolean controller = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller");
        boolean dialogChoice = text.contains("podczas uzywania aplikacji")
                || text.contains("tylko tym razem")
                || text.contains("nie zezwalaj")
                || text.contains("while using the app")
                || text.contains("only this time")
                || text.contains("dont allow")
                || text.contains("don't allow")
                || text.contains("zezwol")
                || text.contains("allow");
        return controller && dialogChoice;
    }'''
pat = r'private boolean isRuntimePermissionDialog\(String packageName, String screenText\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono isRuntimePermissionDialog()')
s = re.sub(pat, new_is_runtime, s, flags=re.S, count=1)

new_handle_runtime = '''private void handleRuntimePermissionDialog(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        String text = normalize(screenText);
        boolean clicked;

        // Aparat i pierwsza zgoda lokalizacji: dokładnie "Podczas używania aplikacji".
        if (text.contains("aparat") || text.contains("camera")
                || text.contains("lokalizacja") || text.contains("location")) {
            clicked = clickByTextAllowDanger(root, "Podczas używania aplikacji")
                    || clickByTextAllowDanger(root, "Podczas uzywania aplikacji")
                    || clickByTextAllowDanger(root, "While using the app")
                    || clickAnyText(root, whileUsingButtons);
        } else {
            // Kontakty, urządzenia w pobliżu, telefon, zdjęcia/filmy/muzyka/dźwięk.
            clicked = clickByTextAllowDanger(root, "Zezwól")
                    || clickByTextAllowDanger(root, "Zezwol")
                    || clickByTextAllowDanger(root, "Zezwalaj")
                    || clickByTextAllowDanger(root, "Allow")
                    || clickAnyText(root, allowButtons);
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            runtimePermissionsClicked++;
            scheduleRuntimeFlowFinishCheck();
        }
    }'''
pat = r'private void handleRuntimePermissionDialog\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono handleRuntimePermissionDialog()')
s = re.sub(pat, new_handle_runtime, s, flags=re.S, count=1)

# ------------------------------------------------------------------
# TMS message: "Dostęp do lokalizacji" -> "ZAKTUALIZUJ USTAWIENIA".
# ------------------------------------------------------------------
new_location_popup = '''private boolean isTmsLocationPopup(String text) {
        String value = normalize(text);
        return containsTmsText(value)
                && (value.contains("dostep do lokalizacji")
                || value.contains("zaktualizuj ustawienia")
                || value.contains("aktualizuj ustawienia")
                || value.contains("location access")
                || value.contains("update settings"));
    }'''
pat = r'private boolean isTmsLocationPopup\(String text\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_location_popup, s, flags=re.S, count=1)
else:
    marker = 'private boolean clickTmsPermissionInfo'
    s = s.replace(marker, new_location_popup + '\n\n    ' + marker, 1)

new_click_location_popup = '''private boolean clickTmsPermissionInfo(AccessibilityNodeInfo root) {
        if (!canClickNow()) return false;

        boolean clicked = clickByTextAllowDanger(root, "ZAKTUALIZUJ USTAWIENIA")
                || clickByTextAllowDanger(root, "Zaktualizuj ustawienia")
                || clickByTextAllowDanger(root, "AKTUALIZUJ USTAWIENIA")
                || clickByTextAllowDanger(root, "Aktualizuj ustawienia")
                || clickByTextAllowDanger(root, "UPDATE SETTINGS")
                || clickByTextAllowDanger(root, "Update settings");

        if (clicked) {
            waitingForAlwaysLocation = true;
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
        }
        return clicked;
    }'''
pat = r'private boolean clickTmsPermissionInfo\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono clickTmsPermissionInfo()')
s = re.sub(pat, new_click_location_popup, s, flags=re.S, count=1)

# ------------------------------------------------------------------
# Settings location detail: choose Always allow, keep precise enabled, BACK to TMS.
# ------------------------------------------------------------------
new_is_location = '''private boolean isLocationPermissionScreen(String packageName, String screenText) {
        String text = normalize(screenText);
        return packageName.contains("settings")
                && (text.contains("lokalizacja - dostep")
                || text.contains("location access")
                || text.contains("zawsze zezwalaj")
                || text.contains("allow all the time"));
    }'''
pat = r'private boolean isLocationPermissionScreen\(String packageName, String screenText\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono isLocationPermissionScreen()')
s = re.sub(pat, new_is_location, s, flags=re.S, count=1)

new_handle_location = '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            finishAlwaysLocationAndReturnToTms();
            return;
        }

        boolean clicked = clickByTextAllowDanger(root, "Zawsze zezwalaj")
                || clickByTextAllowDanger(root, "Zezwalaj cały czas")
                || clickByTextAllowDanger(root, "Zezwalaj caly czas")
                || clickByTextAllowDanger(root, "Allow all the time")
                || clickByTextAllowDanger(root, "Always allow")
                || tapTextCenter(root, "Zawsze zezwalaj")
                || tapTextCenter(root, "Allow all the time");

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current != null) enablePreciseLocationIfVisible(current);
                finishAlwaysLocationAndReturnToTms();
            }, 900);
        }
    }'''
pat = r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono handleLocationScreen()')
s = re.sub(pat, new_handle_location, s, flags=re.S, count=1)

if 'private void finishAlwaysLocationAndReturnToTms()' not in s:
    helper = '''private void finishAlwaysLocationAndReturnToTms() {
        if (!waitingForAlwaysLocation) return;
        waitingForAlwaysLocation = false;

        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            scheduleRuntimeFlowFinishCheck();
        }, 650);
    }

    '''
    marker = 'private boolean isNotificationPermissionScreen'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na finishAlwaysLocationAndReturnToTms()')
    s = s.replace(marker, helper + marker, 1)

# ------------------------------------------------------------------
# Legacy media warning: Confirm/OK, then continue.
# ------------------------------------------------------------------
new_confirm = '''private void clickLegacyPermissionConfirm(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickByTextAllowDanger(root, "Potwierdź")
                || clickByTextAllowDanger(root, "Potwierdz")
                || clickByTextAllowDanger(root, "Confirm")
                || clickByTextAllowDanger(root, "OK")
                || clickByTextAllowDanger(root, "Ok")) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            runtimePermissionsClicked++;
            scheduleRuntimeFlowFinishCheck();
        }
    }'''
pat = r'private void clickLegacyPermissionConfirm\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_confirm, s, flags=re.S, count=1)

# ------------------------------------------------------------------
# Handler order: native dialogs -> TMS location popup -> location settings.
# Avoid App Info/list automation in MODE_OPEN_TMS.
# ------------------------------------------------------------------
start = s.find('        if (isDefaultOpenScreen(packageName, screenText)) {')
end = s.find('        if (isTmsPermissionInfoScreen(screenText)) {', start)
if start == -1:
    start = s.find('        if (isLegacyPermissionWarningDialog(packageName, screenText)) {')
if start == -1 or end == -1:
    raise SystemExit('Nie znaleziono bloku handlerów w handleScreen()')

new_handlers = '''        if (isLegacyPermissionWarningDialog(packageName, screenText)) {
            clickLegacyPermissionConfirm(root);
            return;
        }

        if (isRuntimePermissionDialog(packageName, screenText)) {
            handleRuntimePermissionDialog(root, screenText);
            return;
        }

        if (isTmsLocationPopup(screenText)) {
            clickTmsPermissionInfo(root);
            return;
        }

        if (waitingForAlwaysLocation && isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
            return;
        }

'''
s = s[:start] + new_handlers + s[end:]

# ------------------------------------------------------------------
# Finish after quiet period. Must not finish while waiting for Always location.
# ------------------------------------------------------------------
new_finish_check = '''private void scheduleRuntimeFlowFinishCheck() {
        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            if (waitingForAlwaysLocation) return;

            long quietFor = System.currentTimeMillis() - lastRuntimePermissionActionTime;
            if (runtimePermissionsClicked > 0 && quietFor >= 4500) {
                setFlowMode(MODE_IDLE);
                hideAutomationOverlay();
                Toast.makeText(this, "Gotowe. Uprawnienia TMS zostały nadane.", Toast.LENGTH_LONG).show();
            }
        }, 5000);
    }'''
pat = r'private void scheduleRuntimeFlowFinishCheck\(\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_finish_check, s, flags=re.S, count=1)
else:
    marker = 'private boolean isTmsLocationPopup'
    s = s.replace(marker, new_finish_check + '\n\n    ' + marker, 1)

# Reset state when opening TMS.
new_set_mode = '''private void setFlowMode(String mode) {
        if (!MODE_IDLE.equals(mode)) {
            finalToastShown = false;
        }
        if (MODE_OPEN_TMS.equals(mode)) {
            runtimePermissionsClicked = 0;
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            waitingForAlwaysLocation = false;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FLOW_MODE, mode).apply();
    }'''
pat = r'private void setFlowMode\(String mode\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono setFlowMode()')
s = re.sub(pat, new_set_mode, s, flags=re.S, count=1)

# Safe pacing.
s = s.replace('private static final long CLICK_DELAY_MS = 850;', 'private static final long CLICK_DELAY_MS = 700;')

# Remove HTML artifacts and validate duplicates.
for token in ['&gt;', '&lt;', '<br>']:
    if token in m + s:
        raise SystemExit(f'Pozostał HTML: {token}')
for signature in [
    'private void launchTmsForRuntimePermissions()',
    'private void handleRuntimePermissionDialog(AccessibilityNodeInfo root, String screenText)',
    'private void handleLocationScreen(AccessibilityNodeInfo root)',
    'private void scheduleRuntimeFlowFinishCheck()'
]:
    if (m + s).count(signature) != 1:
        raise SystemExit(f'Nieprawidłowa liczba metody: {signature}')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
print('OK: PM95 native dialogs + TMS Update Settings + Always allow + BACK to TMS')
