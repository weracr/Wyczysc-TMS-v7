#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

s = SERV.read_text(encoding="utf-8")

# ------------------------------------------------------------
# 1. Imports needed for accessibility overlay and gesture click
# ------------------------------------------------------------
def add_import(after, imp):
    global s
    if imp not in s:
        s = s.replace(after, after + "\n" + imp)

add_import('import android.accessibilityservice.GestureDescription;', 'import android.graphics.Color;')
add_import('import android.graphics.Rect;', 'import android.graphics.PixelFormat;')
add_import('import android.os.Looper;', 'import android.view.Gravity;')
add_import('import android.view.Gravity;', 'import android.view.View;')
add_import('import android.view.View;', 'import android.view.WindowManager;')
add_import('import android.view.WindowManager;', 'import android.widget.FrameLayout;')
add_import('import android.widget.FrameLayout;', 'import android.widget.TextView;')

# If GestureDescription or Rect imports are missing, add them near AccessibilityService/Path
if 'import android.accessibilityservice.GestureDescription;' not in s:
    s = s.replace('import android.accessibilityservice.AccessibilityService;', 'import android.accessibilityservice.AccessibilityService;\nimport android.accessibilityservice.GestureDescription;')
if 'import android.graphics.Rect;' not in s:
    s = s.replace('import android.graphics.Path;', 'import android.graphics.Path;\nimport android.graphics.Rect;')

# ------------------------------------------------------------
# 2. Add overlay fields inside class
# ------------------------------------------------------------
if 'private View automationOverlayView;' not in s:
    # put after Handler field if possible
    s = re.sub(
        r'(private final Handler handler = new Handler\(Looper\.getMainLooper\(\)\);)',
        r'''\1
    private WindowManager overlayWindowManager;
    private View automationOverlayView;''',
        s,
        count=1
    )

# ------------------------------------------------------------
# 3. Call updateOverlayVisibility at start of handleScreen
# ------------------------------------------------------------
if 'updateOverlayVisibility();' not in s:
    s = re.sub(
        r'(private void handleScreen\(AccessibilityEvent event\) \{\s*)',
        r'''\1
        updateOverlayVisibility();
''',
        s,
        count=1,
        flags=re.S
    )

# ------------------------------------------------------------
# 4. Make app-info -> permissions click use gesture, not dangerous parent
# ------------------------------------------------------------
new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''

pat_click = r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat_click, s, re.S):
    s = re.sub(pat_click, new_click_app_info, s, flags=re.S)

new_handle_app_info = '''private void handleTmsAppInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''
pat_handle = r'private void handleTmsAppInfoScreen\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if re.search(pat_handle, s, re.S):
    s = re.sub(pat_handle, new_handle_app_info, s, flags=re.S)

# ------------------------------------------------------------
# 5. Add tapTextCenter if missing
# ------------------------------------------------------------
if 'private boolean tapTextCenter(' not in s:
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
    if 'private boolean tapPermissionRowByText' in s:
        s = s.replace('private boolean tapPermissionRowByText', method + 'private boolean tapPermissionRowByText')
    elif 'private boolean isPermissionInDeniedSection' in s:
        s = s.replace('private boolean isPermissionInDeniedSection', method + 'private boolean isPermissionInDeniedSection')
    else:
        raise SystemExit('Nie znalazłam miejsca na metodę tapTextCenter().')

# ------------------------------------------------------------
# 6. Strengthen app-info detection text
# ------------------------------------------------------------
s = s.replace(
    'screenText.contains("app info")',
    'screenText.contains("app info") || screenText.contains("informacje o aplikacji") || screenText.contains("brak przyznanych uprawnien")'
)

# ------------------------------------------------------------
# 7. Overlay methods. Dark overlay is informational and NOT_TOUCHABLE.
# This means it covers/obscures, but does not block Accessibility gestures.
# Physical blocking must be done in PMDM/kiosk.
# ------------------------------------------------------------
if 'private void showAutomationOverlay()' not in s:
    overlay_methods = '''private void updateOverlayVisibility() {
        String mode = getFlowMode();

        if (MODE_IDLE.equals(mode) || MODE_DETAILS_ONLY.equals(mode)) {
            hideAutomationOverlay();
        } else {
            showAutomationOverlay();
        }
    }

    private void showAutomationOverlay() {
        if (automationOverlayView != null) return;

        try {
            overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (overlayWindowManager == null) return;

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.argb(205, 0, 0, 0));
            root.setClickable(false);
            root.setFocusable(false);

            TextView message = new TextView(this);
            message.setText("Naprawa TMS w toku\\nNie dotykaj ekranu\\nAplikacja automatycznie nadaje uprawnienia");
            message.setTextColor(Color.WHITE);
            message.setTextSize(20);
            message.setGravity(Gravity.CENTER);
            message.setPadding(36, 36, 36, 36);

            FrameLayout.LayoutParams msgParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            msgParams.gravity = Gravity.CENTER;
            root.addView(message, msgParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            overlayWindowManager.addView(root, params);
            automationOverlayView = root;
        } catch (Exception ignored) {
            automationOverlayView = null;
        }
    }

    private void hideAutomationOverlay() {
        try {
            if (overlayWindowManager != null && automationOverlayView != null) {
                overlayWindowManager.removeView(automationOverlayView);
            }
        } catch (Exception ignored) {
        }
        automationOverlayView = null;
    }

    '''
    # Insert before getFlowMode if possible
    if 'private String getFlowMode()' in s:
        s = s.replace('private String getFlowMode()', overlay_methods + 'private String getFlowMode()')
    else:
        s = s.replace('private String normalize', overlay_methods + 'private String normalize')

# ------------------------------------------------------------
# 8. Hide overlay when flow finishes
# ------------------------------------------------------------
s = s.replace('setFlowMode(MODE_IDLE);', 'setFlowMode(MODE_IDLE);\n                hideAutomationOverlay();')
# The replacement above may add hide calls inside unrelated blocks. That's okay for IDLE transitions.

# Clean accidental repeated hide lines if script is re-run.
s = s.replace('hideAutomationOverlay();\n                hideAutomationOverlay();', 'hideAutomationOverlay();')
s = s.replace('hideAutomationOverlay();\n        hideAutomationOverlay();', 'hideAutomationOverlay();')

# ------------------------------------------------------------
# Sanity check
# ------------------------------------------------------------
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f"Wykryto podejrzane znaki '*': {bad[:10]}")

SERV.write_text(s, encoding='utf-8')
print('OK: dodano ciemny overlay oraz poprawione klikanie Uprawnienia')
