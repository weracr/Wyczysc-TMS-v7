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
# GŁÓWNA NAPRAWA:
# FULL_REPAIR_FLOW ma służyć TYLKO do odinstalowania i instalowania.
# Nadawanie uprawnień może ruszyć dopiero po instalacji, gdy MainActivity ustawi:
# MODE_GRANT_TMS_PERMISSIONS.
#
# To naprawia problem:
# Napraw TMS -> od razu Informacje o aplikacji, zamiast odinstaluj/zainstaluj.
# ============================================================

# 1. Service: canHandleTmsPermissions nie może obejmować MODE_FULL_REPAIR.
pat = r'private boolean canHandleTmsPermissions\(\) \{.*?\n    \}'
new_can_permissions = '''private boolean canHandleTmsPermissions() {
        return isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
    }'''
if re.search(pat, serv, flags=re.S):
    serv = re.sub(pat, new_can_permissions, serv, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam canHandleTmsPermissions() w PermissionClickerAccessibilityService.java')

# 2. Service: jeśli jest nasza aplikacja i trwa FULL_REPAIR, NIE wymuszaj otwierania ustawień.
# Wymuszenie settings ma działać tylko w MODE_GRANT_TMS_PERMISSIONS.
serv = re.sub(
    r'if \(isMode\(MODE_GRANT_TMS_PERMISSIONS\) \|\| isMode\(MODE_FULL_REPAIR\)\) \{\s*forceOpenTmsSettingsIfNeeded\(\);\s*\}',
    'if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {\n                    forceOpenTmsSettingsIfNeeded();\n                }',
    serv,
    flags=re.S
)
serv = re.sub(
    r'if \(isMode\(MODE_FULL_REPAIR\) \|\| isMode\(MODE_GRANT_TMS_PERMISSIONS\)\) \{\s*forceOpenTmsSettingsIfNeeded\(\);\s*\}',
    'if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {\n                    forceOpenTmsSettingsIfNeeded();\n                }',
    serv,
    flags=re.S
)

# 3. Service: install/uninstall nadal mają obsługiwać FULL_REPAIR.
pat_uninstall = r'private boolean canHandleUninstall\(\) \{.*?\n    \}'
new_uninstall = '''private boolean canHandleUninstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_UNINSTALL_TMS);
    }'''
serv = re.sub(pat_uninstall, new_uninstall, serv, flags=re.S, count=1)

pat_install = r'private boolean canHandleInstall\(\) \{.*?\n    \}'
new_install = '''private boolean canHandleInstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_INSTALL_TMS);
    }'''
serv = re.sub(pat_install, new_install, serv, flags=re.S, count=1)

# 4. Main: repairTms ma świeżo startować FULL_REPAIR i ustawiać flagę, że po instalacji mają iść uprawnienia.
# Usuwamy ryzyko, że zostanie stary tryb GRANT z poprzedniego testu.
pat_repair = r'private void repairTms\(\) \{.*?\n    \}'
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
if re.search(pat_repair, main, flags=re.S):
    main = re.sub(pat_repair, new_repair, main, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam repairTms() w MainActivity.java')

# 5. Main: po odinstalowaniu ma zostać FULL_REPAIR i dopiero instalacja.
# defensywnie poprawiamy onResume fragment, jeżeli był patchowany wielokrotnie.
main = re.sub(
    r'if \(repairAfterUninstall && !isInstalled\(detectedTmsPackage\)\) \{.*?return;\s*\}',
    '''if (repairAfterUninstall && !isInstalled(detectedTmsPackage)) {
            repairAfterUninstall = false;
            grantPermissionsAfterInstall = true;
            setFlowMode(MODE_FULL_REPAIR);
            showRepairInProgressScreen();
            Toast.makeText(this, "TMS odinstalowany. Uruchamiam instalację.", Toast.LENGTH_LONG).show();
            installNewestTmsFromDownload();
            return;
        }''',
    main,
    flags=re.S,
    count=1
)

# 6. Main: po instalacji dopiero wtedy uprawnienia. Jeśli brak DO, ma przejść do ustawień/Accessibility.
# Nie wolno uruchamiać uprawnień przed instalacją.
# Zostawiamy istniejący grantTmsPermissionsAfterInstall, ale upewniamy się, że nie kończy flow od razu po braku DO.
pat_grant_after = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
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
if re.search(pat_grant_after, main, flags=re.S):
    main = re.sub(pat_grant_after, new_grant_after, main, flags=re.S, count=1)

# 7. Dodaj STOP automatyzacji, jeśli go nie ma.
stop_button = 'addAdminButton(root, "STOP automatyzacji", v -> { clearFlowMode(); Toast.makeText(this, "Automatyzacja zatrzymana.", Toast.LENGTH_LONG).show(); });'
if 'STOP automatyzacji' not in main:
    marker = 'addAdminButton(root, "Odśwież status", v -> refreshStatus());'
    if marker in main:
        main = main.replace(marker, stop_button + '\n\n        ' + marker, 1)

# 8. Sanity check.
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f'Podejrzane znaki w kodzie: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: rozdzielono fazę repair od fazy permissions. FULL_REPAIR już nie otwiera Informacji o aplikacji przed instalacją.')
