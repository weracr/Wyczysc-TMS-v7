#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

s = SERV.read_text(encoding="utf-8")

# ------------------------------------------------------------
# 1. Jeśli przypadkiem weszło w "Otwieraj domyślnie", cofnij i spróbuj ponownie.
# ------------------------------------------------------------
if 'private boolean isDefaultOpenScreen(' not in s:
    method = '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        return packageName.contains("settings")
                && containsTmsText(screenText)
                && (screenText.contains("otwieraj domyslnie")
                || screenText.contains("otwieraj domyślnie")
                || screenText.contains("open by default")
                || screenText.contains("ustaw jako domyslne")
                || screenText.contains("ustaw jako domyślne"));
    }

    private void goBackFromWrongDefaultOpenScreen() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 1200) return;
        lastBackTime = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    '''
    marker = 'private boolean isTmsAppInfoScreen'
    if marker not in s:
        raise SystemExit('Nie znalazłam metody isTmsAppInfoScreen.')
    s = s.replace(marker, method + marker, 1)

# Wstaw obsługę złego ekranu PRZED isTmsAppInfoScreen.
wrong_block = '''if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongDefaultOpenScreen();
            return;
        }

        '''
if 'goBackFromWrongDefaultOpenScreen();' in s and 'if (isDefaultOpenScreen(packageName, screenText))' not in s:
    s = s.replace('if (isTmsAppInfoScreen(packageName, screenText)) {', wrong_block + 'if (isTmsAppInfoScreen(packageName, screenText)) {', 1)

# ------------------------------------------------------------
# 2. Podmień clickAppInfoPermissions na metodę, która wybiera dokładny wiersz "Uprawnienia".
#    Nie klika "Brak przyznanych uprawnień" i nie łapie "Otwieraj domyślnie".
# ------------------------------------------------------------
new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (tapAppInfoPermissionsRow(root)) {
            markClicked();
        }
    }'''

pattern = r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pattern, s, flags=re.S):
    s = re.sub(pattern, new_click_app_info, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody clickAppInfoPermissions().')

# ------------------------------------------------------------
# 3. Dodaj helper tapAppInfoPermissionsRow.
# ------------------------------------------------------------
if 'private boolean tapAppInfoPermissionsRow(' not in s:
    helper = '''private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Najpierw szukamy dokładnego tekstu głównego wiersza: "Uprawnienia".
        // Nie bierzemy tekstu pomocniczego typu "Brak przyznanych uprawnień".
        if (tapExactTextRow(root, "Uprawnienia")) return true;
        if (tapExactTextRow(root, "Permissions")) return true;
        if (tapExactTextRow(root, "Zezwolenia")) return true;

        return false;
    }

    private boolean tapExactTextRow(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(text);

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;

            String nodeText = normalize(getNodeVisibleText(node));

            // Dokładne dopasowanie. Dzięki temu nie klikamy np. "Brak przyznanych uprawnień".
            if (!nodeText.equals(wanted)) continue;

            Rect textRect = new Rect();
            node.getBoundsInScreen(textRect);
            if (textRect.isEmpty()) continue;

            // Szukamy klikalnego rodzica wiersza, ale tylko blisko tekstu.
            AccessibilityNodeInfo row = findSmallClickableParent(node, textRect.centerY());
            if (row != null) {
                Rect rowRect = new Rect();
                row.getBoundsInScreen(rowRect);
                if (!rowRect.isEmpty()) {
                    return tapAt(rowRect.centerX(), rowRect.centerY());
                }
            }

            // Fallback: klik w lewą połowę ekranu na wysokości tekstu.
            // To trafia w wiersz "Uprawnienia", a nie w niższy wiersz "Otwieraj domyślnie".
            Rect rootRect = new Rect();
            root.getBoundsInScreen(rootRect);
            int x = rootRect.isEmpty() ? textRect.centerX() : Math.max(textRect.centerX(), rootRect.left + (rootRect.width() / 3));
            return tapAt(x, textRect.centerY());
        }

        return false;
    }

    private AccessibilityNodeInfo findSmallClickableParent(AccessibilityNodeInfo node, int expectedY) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            if (!rect.isEmpty()) {
                int height = rect.height();
                boolean looksLikeRow = height > 36 && height < 220 && expectedY >= rect.top && expectedY <= rect.bottom;
                if (looksLikeRow && current.isClickable() && current.isEnabled()) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    '''
    marker = 'private boolean tapTextCenter'
    if marker in s:
        s = s.replace(marker, helper + marker, 1)
    else:
        marker = 'private boolean tapPermissionRowByText'
        if marker not in s:
            raise SystemExit('Nie znalazłam miejsca na helper tapAppInfoPermissionsRow().')
        s = s.replace(marker, helper + marker, 1)

# ------------------------------------------------------------
# 4. Po zakończeniu flow ukryj overlay i ustaw IDLE także dla FULL_REPAIR.
# ------------------------------------------------------------
s = s.replace(
    'if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS)) setFlowMode(MODE_IDLE);',
    'if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS) || isMode(MODE_FULL_REPAIR)) {\n                setFlowMode(MODE_IDLE);\n                hideAutomationOverlay();\n            }'
)
s = s.replace(
    'if (isMode(MODE_OPEN_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS)) setFlowMode(MODE_IDLE);',
    'if (isMode(MODE_OPEN_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR)) {\n                setFlowMode(MODE_IDLE);\n                hideAutomationOverlay();\n            }'
)

# ------------------------------------------------------------
# 5. sanity check
# ------------------------------------------------------------
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f"Podejrzane gwiazdki w kodzie: {bad[:10]}")

SERV.write_text(s, encoding='utf-8')
print('OK: wymuszono dokładne kliknięcie w wiersz Uprawnienia i cofanie z Otwieraj domyślnie')
