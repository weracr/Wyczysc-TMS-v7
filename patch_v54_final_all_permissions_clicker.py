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

main = MAIN.read_text(encoding='utf-8')
serv = SERV.read_text(encoding='utf-8')

# ============================================================
# FINAL v54
# Cel:
# - naprawa zawsze: odinstaluj -> instaluj -> uprawnienia
# - brak Device Owner = idziemy przez ustawienia/Accessibility
# - żadnych direct intentów do APP_PERMISSION_SETTINGS, bo otwierały Otwieraj domyślnie
# - klikamy Uprawnienia z App Info, a nie Otwieraj domyślnie
# - wszystkie uprawnienia po kolei
# - Potwierdź dla Zdjęcia/Filmy/Muzyka/Dźwięk
# - przełączniki powiadomień tylko włączamy, nie przełączamy w kółko
# - blokada/overlay zostaje wyłączona podczas testów
# ============================================================

# ---------- MainActivity: po instalacji DPM -> fallback ustawienia ----------
new_grant_after = '''private void grantTmsPermissionsAfterInstall() {
        boolean grantedByPolicy = grantTmsPermissionsByPolicy();

        if (grantedByPolicy) {
            clearFlowMode();
            Toast.makeText(this, "Gotowe. Uprawnienia zostały nadane. Można uruchomić TMS.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Brak Device Owner/Profile Owner. Nadaję uprawnienia przez ustawienia.", Toast.LENGTH_LONG).show();
        grantTmsPermissionsThenOpen();
    }'''
main = re.sub(r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}', new_grant_after, main, flags=re.S, count=1)

# ---------- MainActivity: NIE używaj direct permissions intent, tylko App Info ----------
new_grant_open = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram informacje o aplikacji TMS. Przejdę do uprawnień automatycznie.", Toast.LENGTH_LONG).show();

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Nie można otworzyć ustawień TMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }'''
main = re.sub(r'private void grantTmsPermissionsThenOpen\(\) \{.*?\n    \}', new_grant_open, main, flags=re.S, count=1)
main = re.sub(r'\n\s*private boolean openTmsPermissionSettingsDirect\(\) \{.*?\n    \}', '', main, flags=re.S)

# ---------- MainActivity: repairTms zawsze zaczyna świeżo ----------
new_repair = '''private void repairTms() {
        clearFlowMode();
        setFlowMode(MODE_FULL_REPAIR);
        grantPermissionsAfterInstall = true;
        repairAfterUninstall = false;
        waitingForInstallResult = false;
        showRepairInProgressScreen();

        File newestApk = findNewestTmsApkInDownload();
        if (newestApk == null) {
            Toast.makeText(this, "Nie znaleziono pliku TMS APK w folderze Download.", Toast.LENGTH_LONG).show();
            clearFlowMode();
            return;
        }

        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(newestApk.getAbsolutePath(), 0);
        if (packageInfo != null && packageInfo.packageName != null) {
            detectedTmsPackage = packageInfo.packageName;
            pendingInstallPackage = packageInfo.packageName;
        }

        if (isInstalled(detectedTmsPackage)) {
            repairAfterUninstall = true;
            Toast.makeText(this, "Odinstalowuję TMS. Po powrocie uruchomi się instalacja.", Toast.LENGTH_LONG).show();
            uninstallTms();
        } else {
            Toast.makeText(this, "TMS nie jest zainstalowany. Uruchamiam instalację.", Toast.LENGTH_LONG).show();
            installNewestTmsFromDownload();
        }
    }'''
main = re.sub(r'private void repairTms\(\) \{.*?\n    \}', new_repair, main, flags=re.S, count=1)

# ---------- STOP button ----------
stop_button = 'addAdminButton(root, "STOP automatyzacji", v -> { clearFlowMode(); Toast.makeText(this, "Automatyzacja zatrzymana.", Toast.LENGTH_LONG).show(); });'
if 'STOP automatyzacji' not in main:
    marker = 'addAdminButton(root, "Odśwież status", v -> refreshStatus());'
    if marker in main:
        main = main.replace(marker, stop_button + '\n\n        ' + marker, 1)

# ---------- Service imports ----------
if 'import android.widget.Toast;' not in serv:
    serv = serv.replace('import android.widget.TextView;', 'import android.widget.TextView;\nimport android.widget.Toast;')

# ---------- Service: no overlay ----------
serv = re.sub(r'private void updateOverlayVisibility\(\) \{.*?\n    \}', '''private void updateOverlayVisibility() {
        hideAutomationOverlay();
    }''', serv, flags=re.S, count=1)
serv = re.sub(r'private void showAutomationOverlay\(\) \{.*?\n    \}', '''private void showAutomationOverlay() {
        hideAutomationOverlay();
    }''', serv, flags=re.S, count=1)

# ---------- Service: full repair != permissions ----------
serv = re.sub(r'private boolean canHandleTmsPermissions\(\) \{.*?\n    \}', '''private boolean canHandleTmsPermissions() {
        return isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
    }''', serv, flags=re.S, count=1)
serv = re.sub(r'private boolean canHandleUninstall\(\) \{.*?\n    \}', '''private boolean canHandleUninstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_UNINSTALL_TMS);
    }''', serv, flags=re.S, count=1)
serv = re.sub(r'private boolean canHandleInstall\(\) \{.*?\n    \}', '''private boolean canHandleInstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_INSTALL_TMS);
    }''', serv, flags=re.S, count=1)

# ---------- Service: own app block - force settings only in GRANT ----------
serv = re.sub(
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{.*?\n        \}',
    '''if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {
                    forceOpenTmsSettingsIfNeeded();
                }
            } else {
                hideAutomationOverlay();
                setFlowMode(MODE_IDLE);
            }
            return;
        }''',
    serv,
    flags=re.S,
    count=1
)

# ---------- Service: force app details only, no direct permissions intent ----------
serv = re.sub(r'private void forceOpenTmsSettingsIfNeeded\(\) \{.*?\n    \}', '''private void forceOpenTmsSettingsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastForcedSettingsOpenTime < 1800) return;
        lastForcedSettingsOpenTime = now;

        String pkg = resolveTmsPackage(null);
        if (pkg == null) return;

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }''', serv, flags=re.S, count=1)
serv = re.sub(r'\n\s*private boolean openTmsPermissionSettingsDirect\(String pkg\) \{.*?\n    \}', '', serv, flags=re.S)

# ---------- Service: disable default-open handling entirely ----------
serv = re.sub(r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}', '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        return false;
    }''', serv, flags=re.S, count=1)
serv = re.sub(r'\n\s*if \(isDefaultOpenScreen\(packageName, screenText\)\) \{\s*goBackFromWrongScreen\(\);\s*return;\s*\}\n', '\n', serv, flags=re.S, count=1)

# ---------- Service: permission rows in requested order ----------
serv = re.sub(r'private final List<String> permissionRows = Arrays\.asList\(.*?\n    \);', '''private final List<String> permissionRows = Arrays.asList(
            "Aparat", "Camera",
            "Kontakty", "Contacts",
            "Lokalizacja", "Location",
            "Muzyka i dźwięk", "Muzyka i dzwiek", "Music and audio",
            "Powiadomienia", "Notifications",
            "Telefon", "Phone",
            "Urządzenia w pobliżu", "Urzadzenia w poblizu", "Nearby devices",
            "Zdjęcia i filmy", "Zdjecia i filmy", "Photos and videos", "Zdjęcia", "Zdjecia", "Photos"
    );''', serv, flags=re.S, count=1)

# ---------- Service: click app info permissions text/subtext only ----------
serv = re.sub(r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}', '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 900) return;
        lastAppInfoTapTime = now;

        if (tapExactVisibleText(root, "Uprawnienia")
                || tapExactVisibleText(root, "Permissions")
                || tapExactVisibleText(root, "Zezwolenia")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnień")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnien")
                || tapContainsVisibleText(root, "No permissions granted")) {
            markClicked();
        }
    }''', serv, flags=re.S, count=1)

if 'private boolean tapExactVisibleText(' not in serv:
    helpers = '''private boolean tapExactVisibleText(AccessibilityNodeInfo root, String wantedText) {
        if (root == null || wantedText == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectExactNodes(root, normalize(wantedText), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapContainsVisibleText(AccessibilityNodeInfo root, String wantedPart) {
        if (root == null || wantedPart == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectContainsNodes(root, normalize(wantedPart), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    '''
    marker = 'private boolean tapAppInfoPermissionsRow'
    if marker in serv:
        serv = serv.replace(marker, helpers + marker, 1)
    else:
        marker = 'private boolean isAppPermissionsListScreen'
        if marker in serv:
            serv = serv.replace(marker, helpers + marker, 1)

if 'private void collectContainsNodes(' not in serv:
    contains_helper = '''private void collectContainsNodes(AccessibilityNodeInfo node, String wantedPart, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && normalize(text.toString()).contains(wantedPart)) out.add(node);
        CharSequence desc = node.getContentDescription();
        if (desc != null && normalize(desc.toString()).contains(wantedPart)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectContainsNodes(node.getChild(i), wantedPart, out);
    }

    '''
    marker = 'private AccessibilityNodeInfo findBestPermissionsRowParent'
    if marker in serv:
        serv = serv.replace(marker, contains_helper + marker, 1)
    else:
        marker = 'private boolean isAppPermissionsListScreen'
        if marker in serv:
            serv = serv.replace(marker, contains_helper + marker, 1)

# ---------- Service: legacy confirm dialog ----------
if 'private boolean isLegacyPermissionWarningDialog(' in serv:
    serv = re.sub(r'private boolean isLegacyPermissionWarningDialog\(String packageName, String screenText\) \{.*?\n    \}', '''private boolean isLegacyPermissionWarningDialog(String packageName, String screenText) {
        boolean systemDialog = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");
        boolean warningText = screenText.contains("starszej wersji androida")
                || screenText.contains("older version of android")
                || screenText.contains("dostep do zdjec")
                || screenText.contains("dostęp do zdjęć")
                || screenText.contains("dostep do muzyki")
                || screenText.contains("dostęp do muzyki")
                || screenText.contains("rowniez bedzie mozliwy")
                || screenText.contains("również będzie możliwy");
        boolean confirmButton = screenText.contains("potwierdz")
                || screenText.contains("potwierdź")
                || screenText.contains("confirm")
                || screenText.contains("ok");
        return systemDialog && warningText && confirmButton;
    }''', serv, flags=re.S, count=1)

# ---------- Service: location always only ----------
serv = re.sub(r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            goBackToPermissionsListLater();
            return;
        }
        if (clickAnyText(root, alwaysLocationButtons)
                || tapTextCenter(root, "Zawsze zezwalaj")
                || tapTextCenter(root, "Zezwalaj cały czas")
                || tapTextCenter(root, "Zezwalaj caly czas")
                || tapTextCenter(root, "Allow all the time")
                || tapTextCenter(root, "Always allow")) {
            markClicked();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) enablePreciseLocationIfVisible(r);
            }, 650);
            goBackToPermissionsListLater();
        }
    }''', serv, flags=re.S, count=1)

# ---------- Service: notification switches only if unchecked ----------
# Existing clickFirstSwitchOrClickableChild already checks !node.isChecked(); this handler tries each relevant switch.
serv = re.sub(r'private void handleNotificationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}', '''private void handleNotificationScreen(AccessibilityNodeInfo root) {
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
    }''', serv, flags=re.S, count=1)

# ---------- Service: final behavior ----------
serv = re.sub(r'private void finishPermissionFlowAndCloseSettings\(\) \{.*?\n    \}', '''private void finishPermissionFlowAndCloseSettings() {
        if (finalToastShown) return;
        finalToastShown = true;
        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();
        try {
            Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 900);
    }''', serv, flags=re.S, count=1)

# ---------- Sanity ----------
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f'Podejrzane znaki w kodzie: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: finalny clicker uprawnień bez direct intentów, z potwierdzeniami i bez przełączania suwaków w kółko')
