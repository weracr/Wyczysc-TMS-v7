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

# -----------------------------
# MainActivity.java
# -----------------------------

if 'MODE_FULL_REPAIR' not in main:
    main = main.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )
    main = main.replace(
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";',
        'private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )

# Ensure Handler imports and field exist
if 'import android.os.Handler;' not in main:
    main = main.replace('import android.os.Environment;', 'import android.os.Environment;\nimport android.os.Handler;\nimport android.os.Looper;')
if 'new Handler(Looper.getMainLooper())' not in main:
    main = main.replace(
        'private boolean repairAfterUninstall = false;',
        'private boolean repairAfterUninstall = false;\n    private boolean grantPermissionsAfterInstall = false;\n    private final Handler handler = new Handler(Looper.getMainLooper());'
    )
elif 'grantPermissionsAfterInstall' not in main:
    main = main.replace('private boolean repairAfterUninstall = false;', 'private boolean repairAfterUninstall = false;\n    private boolean grantPermissionsAfterInstall = false;')

# Make admin screen kill autocklik state
main = main.replace(
    'private void buildAdminScreen() {\n        ScrollView scroll',
    'private void buildAdminScreen() {\n        clearFlowMode();\n\n        ScrollView scroll'
)

# Add in-app repair guard screen
if 'private void showRepairInProgressScreen()' not in main:
    anchor = 'private void showRepairDialog() {'
    method = '''private void showRepairInProgressScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(40), dp(24), dp(40));
        root.setBackgroundColor(Color.rgb(16, 24, 40));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Naprawa TMS w toku");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView msg = new TextView(this);
        msg.setText("Nie dotykaj ekranu. Aplikacja automatycznie odinstaluje, zainstaluje i nada uprawnienia TMS. Po zakończeniu TMS uruchomi się automatycznie.");
        msg.setTextSize(17);
        msg.setTextColor(Color.rgb(234, 236, 240));
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(18), 0, dp(18));
        root.addView(msg, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Jeśli telefon jest w kiosku PMDM, użytkownik nie powinien mieć możliwości wyjścia poza ten proces.");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(152, 162, 179));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    '''
    if anchor not in main:
        raise SystemExit('Nie znalazłam showRepairDialog(), nie mogę dodać ekranu naprawy.')
    main = main.replace(anchor, method + anchor)

# Make repair dialog positive button start full flow and show guard screen
main = re.sub(
    r'\.setPositiveButton\("Napraw TMS",\s*\(d,\s*w\)\s*->\s*\{[^}]*repairTms\(\);[^}]*\}\)',
    '.setPositiveButton("Napraw TMS", (d, w) -> {\n                    setFlowMode(MODE_FULL_REPAIR);\n                    grantPermissionsAfterInstall = true;\n                    showRepairInProgressScreen();\n                    repairTms();\n                })',
    main,
    flags=re.S
)

# Ensure repairTms starts full mode and guard screen
main = main.replace('private void repairTms() {\n        setFlowMode(MODE_REPAIR_TMS);', 'private void repairTms() {\n        setFlowMode(MODE_FULL_REPAIR);\n        grantPermissionsAfterInstall = true;\n        showRepairInProgressScreen();')
main = main.replace('private void repairTms() {\n        File newestApk', 'private void repairTms() {\n        setFlowMode(MODE_FULL_REPAIR);\n        grantPermissionsAfterInstall = true;\n        showRepairInProgressScreen();\n\n        File newestApk')

# Keep uninstall flow as full repair when called from repair
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) {', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) {')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) setFlowMode(MODE_UNINSTALL_TMS);', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) setFlowMode(MODE_UNINSTALL_TMS);')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) {\n            setFlowMode(MODE_INSTALL_TMS);\n        }', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) {\n            setFlowMode(MODE_INSTALL_TMS);\n        }')
main = main.replace('if (!MODE_REPAIR_TMS.equals(mode)) setFlowMode(MODE_INSTALL_TMS);', 'if (!MODE_REPAIR_TMS.equals(mode) && !MODE_FULL_REPAIR.equals(mode)) setFlowMode(MODE_INSTALL_TMS);')

# After uninstall, install and then grant permissions
main = main.replace('setFlowMode(MODE_REPAIR_TMS);\n            installNewestTmsFromDownload();', 'setFlowMode(MODE_FULL_REPAIR);\n            grantPermissionsAfterInstall = true;\n            showRepairInProgressScreen();\n            installNewestTmsFromDownload();')

# After install success, open settings and grant permissions
if 'grantPermissionsAfterInstall || MODE_FULL_REPAIR.equals(getFlowMode())' not in main:
    main = main.replace(
        'if (MODE_INSTALL_TMS.equals(getFlowMode())) {\n                    clearFlowMode();\n                }',
        'if (grantPermissionsAfterInstall || MODE_FULL_REPAIR.equals(getFlowMode())) {\n                    grantPermissionsAfterInstall = false;\n                    handler.postDelayed(this::grantTmsPermissionsThenOpen, 1200);\n                } else if (MODE_INSTALL_TMS.equals(getFlowMode())) {\n                    clearFlowMode();\n                }'
    )
    main = main.replace(
        'if (MODE_INSTALL_TMS.equals(getFlowMode())) clearFlowMode();',
        'if (grantPermissionsAfterInstall || MODE_FULL_REPAIR.equals(getFlowMode())) { grantPermissionsAfterInstall = false; handler.postDelayed(this::grantTmsPermissionsThenOpen, 1200); } else if (MODE_INSTALL_TMS.equals(getFlowMode())) clearFlowMode();'
    )

# Add grant permissions method if missing
if 'private void grantTmsPermissionsThenOpen()' not in main:
    marker = 'private void setFlowMode(String mode)'
    method = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram ustawienia TMS. Uprawnienia zostaną nadane automatycznie.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    '''
    if marker not in main:
        raise SystemExit('Nie znalazłam setFlowMode(), nie mam gdzie dodać grantTmsPermissionsThenOpen().')
    main = main.replace(marker, method + marker)

# Add admin button if missing
button = 'addAdminButton(root, "Nadaj uprawnienia TMS i uruchom", v -> grantTmsPermissionsThenOpen());'
if button not in main:
    old = 'addAdminButton(root, "3. Otwórz TMS", v -> openTms());'
    if old in main:
        main = main.replace(old, button + '\n        ' + old)

# -----------------------------
# PermissionClickerAccessibilityService.java
# -----------------------------
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

# Broaden uninstall/install modes
serv = serv.replace('return isMode(MODE_UNINSTALL_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_UNINSTALL_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_INSTALL_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_INSTALL_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS);', 'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR);')
serv = serv.replace('return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS);', 'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR);')

# Ensure uninstall dialog does not require TMS text, because Android dialog often omits app name
serv = re.sub(
    r'return \(packageName\.contains\("packageinstaller"\).*?&& containsTmsText\(screenText\);',
    'return (packageName.contains("packageinstaller") || packageName.contains("android") || packageName.contains("settings"))\n                && (screenText.contains("odinstalowac") || screenText.contains("odinstaluj") || screenText.contains("uninstall"));',
    serv,
    flags=re.S
)

# Installer buttons list, if present
serv = serv.replace('"Zainstaluj", "Aktualizuj", "Zaktualizuj", "Install", "Update", "Gotowe", "Done"', '"Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj", "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue", "Zainstaluj mimo to", "Install anyway", "Gotowe", "Done"')

# When permission list has no denied permissions, start TMS and finish flow
serv = serv.replace('handler.postDelayed(this::openTmsApp, 1200);', 'handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 1200);')
if 'private void openTmsAppAndFinishPermissionFlow()' not in serv and 'private void openTmsApp()' in serv:
    method = '''private void openTmsAppAndFinishPermissionFlow() {
        openTmsApp();
        handler.postDelayed(() -> {
            if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS) || isMode(MODE_FULL_REPAIR)) {
                setFlowMode(MODE_IDLE);
            }
        }, 2500);
    }

    '''
    serv = serv.replace('private void openTmsApp() {', method + 'private void openTmsApp() {')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: dodano pełny flow automatycznej naprawy z autoklikiem odinstalowania, instalacji i uprawnień')
