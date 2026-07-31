#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

s = SERV.read_text(encoding="utf-8")

# 1) Upewnij się, że import Rect/Path/GestureDescription istnieje, bo klikamy gestem w tekst.
if "import android.graphics.Rect;" not in s:
    s = s.replace("import android.graphics.Path;", "import android.graphics.Path;\nimport android.graphics.Rect;")
if "import android.accessibilityservice.GestureDescription;" not in s:
    s = s.replace("import android.accessibilityservice.AccessibilityService;", "import android.accessibilityservice.AccessibilityService;\nimport android.accessibilityservice.GestureDescription;")

# 2) Podmień metodę clickAppInfoPermissions, jeśli istnieje.
new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''

pattern_click_app = r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pattern_click_app, s, flags=re.S):
    s = re.sub(pattern_click_app, new_click_app_info, s, flags=re.S)

# 3) Podmień metodę handleTmsAppInfoScreen, jeśli zamiast clickAppInfoPermissions występuje starsza nazwa.
new_handle_app_info = '''private void handleTmsAppInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''
pattern_handle_app = r'private void handleTmsAppInfoScreen\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if re.search(pattern_handle_app, s, flags=re.S):
    s = re.sub(pattern_handle_app, new_handle_app_info, s, flags=re.S)

# 4) Dodaj metodę tapTextCenter, jeśli jej nie ma.
if "private boolean tapTextCenter(" not in s:
    insert_before = "private boolean tapPermissionRowByText"
    method = '''private boolean tapTextCenter(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(text);

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;

            String nodeText = normalize(getNodeVisibleText(node));
            if (!nodeText.equals(wanted) && !nodeText.contains(wanted)) continue;

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.isEmpty()) continue;

            return tapAt(rect.centerX(), rect.centerY());
        }

        return false;
    }

    '''
    if insert_before in s:
        s = s.replace(insert_before, method + insert_before)
    else:
        # fallback: wklej przed isPermissionInDeniedSection
        s = s.replace("private boolean isPermissionInDeniedSection", method + "private boolean isPermissionInDeniedSection")

# 5) Wzmocnij rozpoznawanie ekranu informacji o aplikacji, jeśli metoda istnieje.
# Dodaj frazy widoczne na screenie: "informacje o aplikacji", "brak przyznanych uprawnien".
s = s.replace(
    'screenText.contains("app info")',
    'screenText.contains("app info") || screenText.contains("informacje o aplikacji") || screenText.contains("brak przyznanych uprawnien")'
)

# Sanity check na przypadkowe gwiazdki z czatu.
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f"W pliku wykryto podejrzane znaki '*': {bad[:10]}")

SERV.write_text(s, encoding="utf-8")
print("OK: poprawiono klikanie Uprawnienia na ekranie Informacje o aplikacji")
