#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not MAIN.exists():
    raise SystemExit(f'Nie znaleziono pliku: {MAIN}')
if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# Toast import for final message
if 'import android.widget.Toast;' not in s:
    s = s.replace('import android.widget.TextView;', 'import android.widget.TextView;\nimport android.widget.Toast;')

# Stronger, less transparent overlay, hidden from Accessibility tree
s = s.replace('Color.argb(210, 0, 0, 0)', 'Color.argb(235, 0, 0, 0)')
s = s.replace('Color.argb(205, 0, 0, 0)', 'Color.argb(235, 0, 0, 0)')
if 'root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);' not in s:
    s = s.replace('root.setFocusable(false);', 'root.setFocusable(false);\n            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);')
if 'message.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);' not in s:
    s = s.replace('message.setPadding(36, 36, 36, 36);', 'message.setPadding(36, 36, 36, 36);\n            message.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);')

# Add missing finish flag
if 'private boolean finalToastShown' not in s:
    s = s.replace('private boolean openedAppSettingsForMissingPermission = false;', 'private boolean openedAppSettingsForMissingPermission = false;\n    private boolean finalToastShown = false;')

# Clean permission order list
new_permission_rows = '''private final List<String> permissionRows = Arrays.asList(
            "Aparat", "Camera",
            "Kontakty", "Contacts",
            "Lokalizacja", "Location",
            "Muzyka i dźwięk", "Muzyka i dzwiek", "Music and audio",
            "Powiadomienia", "Notifications",
            "Telefon", "Phone",
            "Urządzenia w pobliżu", "Urzadzenia w poblizu", "Nearby devices",
            "Zdjęcia i filmy", "Zdjecia i filmy", "Photos and videos", "Zdjęcia", "Zdjecia", "Photos"
    );'''
s = re.sub(r'private final List<String> permissionRows = Arrays\.asList\(.*?\n    \);', new_permission_rows, s, flags=re.S, count=1)

# Replace handlePermissionsList with ordered logic and finish message instead of auto opening TMS
new_handle_permissions = '''private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)) {
                if (tapPermissionRowByText(root, permission)) {
                    markClicked();
                    return;
                }
            }
        }

        finishPermissionFlowWithMessage();
    }'''
s = re.sub(r'private void handlePermissionsList\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}', new_handle_permissions, s, flags=re.S, count=1)

# Add finish method
if 'private void finishPermissionFlowWithMessage()' not in s:
    finish_method = '''private void finishPermissionFlowWithMessage() {
        if (finalToastShown) return;
        finalToastShown = true;

        try {
            Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }

        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    '''
    if 'private void openTmsAppAndFinishPermissionFlow()' in s:
        s = s.replace('private void openTmsAppAndFinishPermissionFlow()', finish_method + 'private void openTmsAppAndFinishPermissionFlow()', 1)
    else:
        s = s.replace('private void openTmsApp()', finish_method + 'private void openTmsApp()', 1)

# More explicit field to reset on new automation start
s = s.replace('private void setFlowMode(String mode) {\n        getSharedPreferences', 'private void setFlowMode(String mode) {\n        if (!MODE_IDLE.equals(mode)) {\n            finalToastShown = false;\n        }\n        getSharedPreferences')

# Replace handlers to match requested labels
new_camera = '''private void handleCameraScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickAnyText(root, whileUsingButtons)) {
            markClicked();
            goBackToPermissionsListLater();
        }
    }'''
s = re.sub(r'private void handleCameraScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_camera, s, flags=re.S, count=1)

new_location = '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            goBackToPermissionsListLater();
            return;
        }
        if (clickAnyText(root, alwaysLocationButtons)) {
            markClicked();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) enablePreciseLocationIfVisible(r);
            }, 650);
            goBackToPermissionsListLater();
        }
    }'''
s = re.sub(r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_location, s, flags=re.S, count=1)

new_notification = '''private void handleNotificationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        boolean clicked = false;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia z aplikacji ZABKA TMSFalcon") || clicked;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia z aplikacji ZABKA TMSfalcon") || clicked;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "All notifications") || clicked;
        clicked = clickSwitchNearText(root, "Zezwalaj na kropkę powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "Zezwalaj na kropke powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "Allow notification dot") || clicked;
        clicked = clickAnyText(root, allowButtons) || clicked;
        if (clicked) {
            markClicked();
            handler.postDelayed(this::goBackToPermissionsListLater, 700);
        }
    }'''
s = re.sub(r'private void handleNotificationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_notification, s, flags=re.S, count=1)

new_generic = '''private void handleGenericPermissionScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickAnyText(root, allowButtons)) {
            markClicked();
            goBackToPermissionsListLater();
        }
    }'''
s = re.sub(r'private void handleGenericPermissionScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_generic, s, flags=re.S, count=1)

# App info click fallback: if exact row matching fails, tap around row area slightly higher than default-open row.
if 'private boolean tapAppInfoPermissionsRow(' in s:
    s = re.sub(r'private boolean tapAppInfoPermissionsRow\(AccessibilityNodeInfo root\) \{.*?\n    \}', '''private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> exactNodes = new ArrayList<>();
        collectExactNodes(root, "uprawnienia", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "permissions", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "zezwolenia", exactNodes);

        for (AccessibilityNodeInfo node : exactNodes) {
            Rect textRect = new Rect();
            node.getBoundsInScreen(textRect);
            if (textRect.isEmpty()) continue;
            AccessibilityNodeInfo row = findSmallClickableParent(node, textRect.centerY());
            if (row != null) {
                Rect rowRect = new Rect();
                row.getBoundsInScreen(rowRect);
                if (!rowRect.isEmpty()) return tapAt(rowRect.centerX(), rowRect.centerY());
            }
            return tapAt(textRect.centerX(), textRect.centerY());
        }

        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (!rootRect.isEmpty()) {
            return tapAt(rootRect.centerX(), rootRect.top + (int) (rootRect.height() * 0.34f));
        }
        return false;
    }''', s, flags=re.S, count=1)

# Sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: dodano uporządkowany flow uprawnień zgodnie z opisem')
