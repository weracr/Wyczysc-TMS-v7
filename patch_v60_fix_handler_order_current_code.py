#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not SERV.exists():
    raise SystemExit(f'Nie znaleziono: {SERV}')

s = SERV.read_text(encoding='utf-8')

# 1. Napraw rozpoznawanie aktywnego ekranu Otwieraj domyślnie.
s = re.sub(
    r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}',
    '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        String text = normalize(screenText);
        return packageName.contains("settings")
                && containsTmsText(text)
                && (text.contains("otwieraj obslugiwane linki")
                || text.contains("open supported links")
                || text.contains("linki otwierane w tej aplikacji")
                || text.contains("0 zweryfikowanych linkow"));
    }''',
    s, flags=re.S, count=1
)

# 2. Zwęż runtime dialog. com.android.settings zawiera słowo android,
# więc dotychczas App Info było błędnie traktowane jako runtime permission dialog.
s = re.sub(
    r'private boolean isRuntimePermissionDialog\(String packageName, String screenText\) \{.*?\n    \}',
    '''private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        boolean permissionController = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller");

        boolean actualDialogChoice = screenText.contains("podczas uzywania")
                || screenText.contains("while using")
                || screenText.contains("zezwol tylko")
                || screenText.contains("allow only")
                || screenText.contains("nie zezwalaj")
                || screenText.contains("dont allow")
                || screenText.contains("don't allow");

        return permissionController && actualDialogChoice && containsTmsText(screenText);
    }''',
    s, flags=re.S, count=1
)

# 3. App Info: klikaj cały prawidłowy wiersz przez istniejącą metodę,
# dopiero potem sam tekst. Bez stałych współrzędnych.
s = re.sub(
    r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}',
    '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 900) return;
        lastAppInfoTapTime = now;

        if (tapAppInfoPermissionsRow(root)
                || tapExactVisibleText(root, "Uprawnienia")
                || tapExactVisibleText(root, "Permissions")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnień")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnien")) {
            markClicked();
        }
    }''',
    s, flags=re.S, count=1
)

# 4. Przestaw kolejność w handleScreen. Najpierw rozpoznawaj konkretne ekrany Settings,
# dopiero później popupy/runtime dialogs.
start = s.find('        if (isLegacyPermissionWarningDialog(packageName, screenText)) {')
end = s.find('        if (isTmsPermissionInfoScreen(screenText)) {', start)
if start == -1 or end == -1:
    raise SystemExit('Nie znaleziono bloku handlerów do uporządkowania.')

new_handlers = '''        if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        if (isTmsAppInfoScreen(packageName, screenText)) {
            clickAppInfoPermissions(root);
            return;
        }

        if (isAppPermissionsListScreen(packageName, screenText)) {
            handlePermissionsList(root, screenText);
            return;
        }

        if (isLegacyPermissionWarningDialog(packageName, screenText)) {
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

        if (isCameraPermissionScreen(packageName, screenText)) {
            handleCameraScreen(root);
            return;
        }

        if (isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
            return;
        }

        if (isNotificationPermissionScreen(packageName, screenText)) {
            handleNotificationScreen(root);
            return;
        }

        if (isGenericPermissionScreen(packageName, screenText)) {
            handleGenericPermissionScreen(root);
            return;
        }

'''
s = s[:start] + new_handlers + s[end:]

# 5. BACK ma działać szybko, ale bez wielokrotnych kliknięć.
s = re.sub(
    r'private void goBackFromWrongScreen\(\) \{.*?\n    \}',
    '''private void goBackFromWrongScreen() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 700) return;
        lastBackTime = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
    }''',
    s, flags=re.S, count=1
)

# Sanity checks
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'W pliku pozostał token HTML: {token}')
if s.count('private boolean isRuntimePermissionDialog(') != 1:
    raise SystemExit('Nieprawidłowa liczba isRuntimePermissionDialog().')
if s.count('private void clickAppInfoPermissions(') != 1:
    raise SystemExit('Nieprawidłowa liczba clickAppInfoPermissions().')

SERV.write_text(s, encoding='utf-8')
print('OK: poprawiono rzeczywisty błąd kolejności: App Info nie jest już runtime dialogiem')
