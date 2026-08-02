#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# v61: aplikacja wchodzi już do listy Uprawnienia aplikacji, ale nie otwiera pozycji.
# Faktyczne poprawki:
# 1. Szczegóły konkretnego uprawnienia są obsługiwane PRZED listą uprawnień.
# 2. Klikamy klikalny rodzic całego wiersza, nie środek samego TextView.
# 3. Rozpoznajemy więcej nazw sekcji odmówionych: Nie zezwolono, Niedozwolone, Brak dostępu.
# 4. Jeśli lista nie ma nagłówka sekcji, klikamy pierwsze wymagane uprawnienie, które nie jest zaznaczone.

# ---- Reorder handlers in handleScreen ----
start = s.find('        if (isDefaultOpenScreen(packageName, screenText)) {')
end = s.find('        if (isTmsPermissionInfoScreen(screenText)) {', start)
if start == -1 or end == -1:
    raise SystemExit('Nie znaleziono bloku handlerów w handleScreen().')

handlers = '''        if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        if (isTmsAppInfoScreen(packageName, screenText)) {
            clickAppInfoPermissions(root);
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

        // Szczegóły konkretnego uprawnienia muszą być przed listą Uprawnienia aplikacji.
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

        if (isAppPermissionsListScreen(packageName, screenText)) {
            handlePermissionsList(root, screenText);
            return;
        }

        if (isTmsLocationPopup(screenText)) {
            clickTmsPermissionInfo(root);
            return;
        }

'''
s = s[:start] + handlers + s[end:]

# ---- Stronger permission list detection ----
s = re.sub(
    r'private boolean isAppPermissionsListScreen\(String packageName, String screenText\) \{.*?\n    \}',
    '''private boolean isAppPermissionsListScreen(String packageName, String screenText) {
        String text = normalize(screenText);
        return packageName.contains("settings")
                && containsTmsText(text)
                && (text.contains("uprawnienia aplikacji")
                || text.contains("app permissions")
                || text.contains("maja dostep")
                || text.contains("nie maja dostepu")
                || text.contains("dozwolone")
                || text.contains("niedozwolone")
                || text.contains("nie zezwolono")
                || text.contains("brak dostepu")
                || text.contains("allowed")
                || text.contains("not allowed"));
    }''',
    s, flags=re.S, count=1
)

# ---- Replace denied section helper ----
s = re.sub(
    r'private boolean isPermissionInDeniedSection\(String screenText, String permissionName\) \{.*?\n    \}',
    '''private boolean isPermissionInDeniedSection(String screenText, String permissionName) {
        String text = normalize(screenText);
        String permission = normalize(permissionName);

        String[] deniedHeaders = new String[] {
                "nie maja dostepu",
                "not allowed",
                "niedozwolone",
                "nie zezwolono",
                "brak dostepu"
        };

        int deniedIndex = -1;
        for (String header : deniedHeaders) {
            int idx = text.indexOf(header);
            if (idx >= 0 && (deniedIndex < 0 || idx < deniedIndex)) {
                deniedIndex = idx;
            }
        }

        if (deniedIndex < 0) return false;
        return text.indexOf(permission, deniedIndex) > deniedIndex;
    }''',
    s, flags=re.S, count=1
)

# ---- Replace handlePermissionsList ----
new_handle = '''private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        // Najpierw klikamy pozycje wykryte pod nagłówkiem sekcji odmówionych.
        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)
                    && tapPermissionRowByText(root, permission)) {
                markClicked();
                return;
            }
        }

        // Fallback dla wersji Ustawień bez czytelnego nagłówka sekcji.
        // Klikamy pierwszy wymagany wiersz, który jest widoczny i nie wygląda na już zaznaczony.
        for (String permission : permissionRows) {
            if (tapVisibleUncheckedPermissionRow(root, permission)) {
                markClicked();
                return;
            }
        }

        String text = normalize(screenText);
        boolean definitelyPermissionList = text.contains("uprawnienia aplikacji")
                || text.contains("app permissions")
                || text.contains("maja dostep")
                || text.contains("dozwolone")
                || text.contains("allowed");

        boolean stillHasDeniedSection = text.contains("nie maja dostepu")
                || text.contains("not allowed")
                || text.contains("niedozwolone")
                || text.contains("nie zezwolono")
                || text.contains("brak dostepu");

        if (definitelyPermissionList && !stillHasDeniedSection) {
            finishPermissionFlowAndCloseSettings();
        }
    }'''
s = re.sub(r'private void handlePermissionsList\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}', new_handle, s, flags=re.S, count=1)

# ---- Replace tapPermissionRowByText with clickable row parent ----
s = re.sub(
    r'private boolean tapPermissionRowByText\(AccessibilityNodeInfo root, String text\) \{.*?\n    \}',
    '''private boolean tapPermissionRowByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo row = findPermissionClickableParent(node);
            if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty() && tapAt(rect.centerX(), rect.centerY())) {
                return true;
            }
        }
        return false;
    }''',
    s, flags=re.S, count=1
)

# ---- Add helpers ----
if 'private AccessibilityNodeInfo findPermissionClickableParent(' not in s:
    helpers = '''private AccessibilityNodeInfo findPermissionClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 7 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            String text = normalize(collectText(current));

            boolean wrongRow = text.contains("otwieraj domyslnie")
                    || text.contains("open by default")
                    || text.contains("wyczysc dane")
                    || text.contains("odinstaluj");

            if (!wrongRow
                    && !rect.isEmpty()
                    && rect.height() >= 35
                    && rect.height() <= 300
                    && current.isEnabled()
                    && current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean tapVisibleUncheckedPermissionRow(AccessibilityNodeInfo root, String permission) {
        if (root == null || permission == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(permission);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(permission);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo row = findPermissionClickableParent(node);
            if (row == null) continue;

            String rowText = normalize(collectText(row));
            boolean alreadyAllowed = rowText.contains("dozwolone")
                    || rowText.contains("zezwolono")
                    || rowText.contains("allowed")
                    || rowText.contains("zawsze zezwalaj")
                    || rowText.contains("podczas uzywania");

            if (!alreadyAllowed && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        return false;
    }

    '''
    marker = 'private boolean tapTextCenter'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na helpery klikające wiersze.')
    s = s.replace(marker, helpers + marker, 1)

# Sanity
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'W pliku pozostał HTML: {token}')
if s.count('private void handlePermissionsList(') != 1:
    raise SystemExit('Nieprawidłowa liczba handlePermissionsList().')
if s.count('private boolean tapPermissionRowByText(') != 1:
    raise SystemExit('Nieprawidłowa liczba tapPermissionRowByText().')

SERV.write_text(s, encoding='utf-8')
print('OK: lista uprawnień klika teraz cały wiersz, a ekrany szczegółowe mają priorytet')
