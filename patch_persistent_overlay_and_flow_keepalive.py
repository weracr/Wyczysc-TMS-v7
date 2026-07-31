#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / "app/src/main/java/pl/zabka/wyczysctms/MainActivity.java"
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not MAIN.exists():
    raise SystemExit(f"Nie znaleziono pliku: {MAIN}")
if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

main = MAIN.read_text(encoding="utf-8")
serv = SERV.read_text(encoding="utf-8")

# ============================================================
# MAINACTIVITY: upewnij się, że Napraw TMS NIE wraca do IDLE
# ============================================================
if 'MODE_FULL_REPAIR' not in main:
    main = main.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )
    main = main.replace(
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";',
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )

# Jeśli showRepairDialog nadal ustawia REPAIR_TMS_FLOW, ustaw FULL_REPAIR_FLOW.
main = main.replace('setFlowMode(MODE_REPAIR_TMS);\n                    repairTms();', 'setFlowMode(MODE_FULL_REPAIR);\n                    grantPermissionsAfterInstall = true;\n                    showRepairInProgressScreen();\n                    repairTms();')
main = main.replace('setFlowMode(MODE_REPAIR_TMS);\n                    grantPermissionsAfterInstall = true;\n                    repairTms();', 'setFlowMode(MODE_FULL_REPAIR);\n                    grantPermissionsAfterInstall = true;\n                    showRepairInProgressScreen();\n                    repairTms();')

# Upewnij się, że repairTms startuje FULL_REPAIR_FLOW i ekran osłony.
main = main.replace('private void repairTms() {\n        setFlowMode(MODE_REPAIR_TMS);', 'private void repairTms() {\n        setFlowMode(MODE_FULL_REPAIR);\n        grantPermissionsAfterInstall = true;\n        showRepairInProgressScreen();')
main = main.replace('private void repairTms() {\n        File newestApk', 'private void repairTms() {\n        setFlowMode(MODE_FULL_REPAIR);\n        grantPermissionsAfterInstall = true;\n        showRepairInProgressScreen();\n\n        File newestApk')

# Upewnij się, że uninstall/install nie nadpisują FULL_REPAIR_FLOW.
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) setFlowMode(MODE_UNINSTALL_TMS);', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) setFlowMode(MODE_UNINSTALL_TMS);')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) setFlowMode(MODE_INSTALL_TMS);', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) setFlowMode(MODE_INSTALL_TMS);')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) {\n            setFlowMode(MODE_INSTALL_TMS);\n        }', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) {\n            setFlowMode(MODE_INSTALL_TMS);\n        }')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) {\n            setFlowMode(MODE_UNINSTALL_TMS);\n        }', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) {\n            setFlowMode(MODE_UNINSTALL_TMS);\n        }')

# Upewnij się, że po odinstalowaniu dalej zostaje full repair.
main = main.replace('setFlowMode(MODE_REPAIR_TMS);\n            installNewestTmsFromDownload();', 'setFlowMode(MODE_FULL_REPAIR);\n            grantPermissionsAfterInstall = true;\n            showRepairInProgressScreen();\n            installNewestTmsFromDownload();')

# ============================================================
# SERVICE: dodaj brakujące importy overlayu
# ============================================================
def add_import(after, imp):
    global serv
    if imp not in serv:
        serv = serv.replace(after, after + "\n" + imp)

if 'import android.accessibilityservice.GestureDescription;' not in serv:
    serv = serv.replace('import android.accessibilityservice.AccessibilityService;', 'import android.accessibilityservice.AccessibilityService;\nimport android.accessibilityservice.GestureDescription;')
if 'import android.graphics.Path;' not in serv:
    serv = serv.replace('import android.content.pm.PackageManager;', 'import android.content.pm.PackageManager;\nimport android.graphics.Path;')
if 'import android.graphics.Rect;' not in serv:
    serv = serv.replace('import android.graphics.Path;', 'import android.graphics.Path;\nimport android.graphics.Rect;')
add_import('import android.graphics.Rect;', 'import android.graphics.Color;')
add_import('import android.graphics.Color;', 'import android.graphics.PixelFormat;')
add_import('import android.os.Looper;', 'import android.view.Gravity;')
add_import('import android.view.Gravity;', 'import android.view.View;')
add_import('import android.view.View;', 'import android.view.WindowManager;')
add_import('import android.view.WindowManager;', 'import android.widget.FrameLayout;')
add_import('import android.widget.FrameLayout;', 'import android.widget.TextView;')

# Dodaj tryby, jeśli brak.
if 'MODE_FULL_REPAIR' not in serv:
    serv = serv.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )
    serv = serv.replace(
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";',
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )
if 'MODE_GRANT_TMS_PERMISSIONS' not in serv:
    serv = serv.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )

# Dodaj pola overlayu.
if 'private View automationOverlayView;' not in serv:
    serv = re.sub(
        r'(private final Handler handler = new Handler\(Looper\.getMainLooper\(\)\);)',
        r'''\1
    private WindowManager overlayWindowManager;
    private View automationOverlayView;''',
        serv,
        count=1
    )

# Dodaj updateOverlayVisibility na początku handleScreen.
if 'updateOverlayVisibility();' not in serv:
    serv = re.sub(
        r'(private void handleScreen\(AccessibilityEvent event\) \{\s*)',
        r'''\1
        updateOverlayVisibility();
''',
        serv,
        count=1,
        flags=re.S
    )

# Najważniejsza poprawka: ekran własnej aplikacji NIE może ubijać FULL_REPAIR_FLOW.
# Zamień typowy blok, który robi setFlowMode(IDLE) przy własnej aplikacji.
serv = re.sub(
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{\s*setFlowMode\(MODE_IDLE\);\s*return;\s*\}',
    '''if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                return;
            }
            setFlowMode(MODE_IDLE);
            hideAutomationOverlay();
            return;
        }''',
    serv,
    flags=re.S
)
serv = re.sub(
    r'if \(isOwnAppScreen\(packageName, screenText\)\) \{\s*return;\s*\}',
    '''if (isOwnAppScreen(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                return;
            }
            return;
        }''',
    serv,
    flags=re.S
)
serv = re.sub(
    r'if \(isAdminPanelText\(screenText\)\) \{\s*setFlowMode\(MODE_IDLE\);\s*return;\s*\}',
    '''if (isAdminPanelText(screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                return;
            }
            setFlowMode(MODE_IDLE);
            hideAutomationOverlay();
            return;
        }''',
    serv,
    flags=re.S
)

# Dopuść FULL_REPAIR do uninstall/install/permissions.
serv = serv.replace('return isMode(MODE_UNINSTALL_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_UNINSTALL_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_INSTALL_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_INSTALL_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS);', 'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR);')

# Odinstalowanie nie zawsze pokazuje TMS w dialogu.
serv = re.sub(
    r'return \(packageName\.contains\("packageinstaller"\).*?&& containsTmsText\(screenText\);',
    'return (packageName.contains("packageinstaller") || packageName.contains("android") || packageName.contains("settings"))\n                && (screenText.contains("odinstalowac") || screenText.contains("odinstaluj") || screenText.contains("uninstall"));',
    serv,
    flags=re.S
)

# Instalator - więcej przycisków.
serv = serv.replace('"Zainstaluj", "Aktualizuj", "Zaktualizuj", "Install", "Update", "Gotowe", "Done"', '"Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj", "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue", "Zainstaluj mimo to", "Install anyway", "Gotowe", "Done"')

# Klik Uprawnienia przez gest.
new_app_info_method = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''
serv = re.sub(r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_app_info_method, serv, flags=re.S)

new_handle_method = '''private void handleTmsAppInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        if (tapTextCenter(root, "Uprawnienia")
                || tapTextCenter(root, "Permissions")
                || tapTextCenter(root, "Zezwolenia")) {
            markClicked();
        }
    }'''
serv = re.sub(r'private void handleTmsAppInfoScreen\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}', new_handle_method, serv, flags=re.S)

if 'private boolean tapTextCenter(' not in serv:
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
    if 'private boolean tapPermissionRowByText' in serv:
        serv = serv.replace('private boolean tapPermissionRowByText', method + 'private boolean tapPermissionRowByText')
    else:
        serv = serv.replace('private boolean isPermissionInDeniedSection', method + 'private boolean isPermissionInDeniedSection')

# Zakończenie flow po permissions.
serv = serv.replace('handler.postDelayed(this::openTmsApp, 1200);', 'handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 1200);')
if 'private void openTmsAppAndFinishPermissionFlow()' not in serv and 'private void openTmsApp()' in serv:
    method = '''private void openTmsAppAndFinishPermissionFlow() {
        openTmsApp();
        handler.postDelayed(() -> {
            if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS) || isMode(MODE_FULL_REPAIR)) {
                setFlowMode(MODE_IDLE);
                hideAutomationOverlay();
            }
        }, 2500);
    }

    '''
    serv = serv.replace('private void openTmsApp() {', method + 'private void openTmsApp() {')

# Dodaj overlay i helper isAutomationRunning.
if 'private boolean isAutomationRunning()' not in serv:
    helper = '''private boolean isAutomationRunning() {
        return isMode(MODE_FULL_REPAIR)
                || isMode(MODE_REPAIR_TMS)
                || isMode(MODE_UNINSTALL_TMS)
                || isMode(MODE_INSTALL_TMS)
                || isMode(MODE_GRANT_TMS_PERMISSIONS)
                || isMode(MODE_OPEN_TMS);
    }

    '''
    if 'private String getFlowMode()' in serv:
        serv = serv.replace('private String getFlowMode()', helper + 'private String getFlowMode()')
    else:
        serv = serv.replace('private String normalize', helper + 'private String normalize')

if 'private void showAutomationOverlay()' not in serv:
    overlay_methods = '''private void updateOverlayVisibility() {
        if (isAutomationRunning()) {
            showAutomationOverlay();
        } else {
            hideAutomationOverlay();
        }
    }

    private void showAutomationOverlay() {
        if (automationOverlayView != null) return;
        try {
            overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (overlayWindowManager == null) return;

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.argb(210, 0, 0, 0));
            root.setClickable(false);
            root.setFocusable(false);

            TextView message = new TextView(this);
            message.setText("Naprawa TMS w toku\\nNie dotykaj ekranu\\nAplikacja automatycznie odinstaluje, zainstaluje i nada uprawnienia");
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
    if 'private boolean isAutomationRunning()' in serv:
        serv = serv.replace('private boolean isAutomationRunning()', overlay_methods + 'private boolean isAutomationRunning()')
    elif 'private String getFlowMode()' in serv:
        serv = serv.replace('private String getFlowMode()', overlay_methods + 'private String getFlowMode()')

# Powiększ detekcję app info.
serv = serv.replace('screenText.contains("app info")', 'screenText.contains("app info") || screenText.contains("informacje o aplikacji") || screenText.contains("brak przyznanych uprawnien")')

# Niech setFlowMode(IDLE) czyści overlay.
serv = serv.replace('setFlowMode(MODE_IDLE);\n                hideAutomationOverlay();\n                hideAutomationOverlay();', 'setFlowMode(MODE_IDLE);\n                hideAutomationOverlay();')

# sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', serv + main)
if bad:
    raise SystemExit(f"Podejrzane gwiazdki w kodzie: {bad[:10]}")

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: naprawiono utrzymanie flow, overlay i klik uprawnień')
