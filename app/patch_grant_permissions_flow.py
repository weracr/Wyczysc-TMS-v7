#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()
MAIN = ROOT / "app/src/main/java/pl/zabka/wyczysctms/MainActivity.java"
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not MAIN.exists():
    raise SystemExit(f"Nie znaleziono {MAIN}")
if not SERV.exists():
    raise SystemExit(f"Nie znaleziono {SERV}")

main = MAIN.read_text(encoding="utf-8")
serv = SERV.read_text(encoding="utf-8")

if 'MODE_GRANT_TMS_PERMISSIONS' not in main:
    main = main.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";'
    )

main = main.replace(
    'private void buildAdminScreen() {\n        ScrollView scroll',
    'private void buildAdminScreen() {\n        clearFlowMode();\n\n        ScrollView scroll'
)

button = 'addAdminButton(root, "Nadaj uprawnienia TMS i uruchom", v -> grantTmsPermissionsThenOpen());'
if button not in main:
    old1 = 'addAdminButton(root, "3. Otwórz TMS", v -> openTms());'
    old2 = '''addAdminButton(root, "3. Otwórz TMS", v -> {
            setFlowMode(MODE_OPEN_TMS);
            openTms();
        });'''
    if old1 in main:
        main = main.replace(old1, button + '\n\n        ' + old1)
    elif old2 in main:
        main = main.replace(old2, button + '\n\n        addAdminButton(root, "3. Otwórz TMS", v -> openTms());')
    else:
        raise SystemExit('Nie znalazłam miejsca na przycisk 3. Otwórz TMS. Dodaj przycisk ręcznie.')

if 'private void grantTmsPermissionsThenOpen()' not in main:
    marker = 'private int dp(int v) {'
    method = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);

        Toast.makeText(
                this,
                "Otwieram ustawienia TMS. Uprawnienia zostaną nadane automatycznie.",
                Toast.LENGTH_LONG
        ).show();

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    '''
    if marker not in main:
        raise SystemExit('Nie znalazłam metody dp(int v), nie mam gdzie wkleić metody.')
    main = main.replace(marker, method + marker)

if 'MODE_GRANT_TMS_PERMISSIONS' not in serv:
    serv = serv.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";'
    )

serv = serv.replace(
    'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS);',
    'return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS);'
)
serv = serv.replace(
    'return isMode(MODE_OPEN_TMS)\n            || isMode(MODE_REPAIR_TMS);',
    'return isMode(MODE_OPEN_TMS)\n            || isMode(MODE_REPAIR_TMS)\n            || isMode(MODE_GRANT_TMS_PERMISSIONS);'
)

serv = serv.replace(
    'handler.postDelayed(this::openTmsApp, 1200);',
    'handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 1200);'
)

if 'private void openTmsAppAndFinishPermissionFlow()' not in serv:
    insert_before = 'private void openTmsApp() {'
    method = '''private void openTmsAppAndFinishPermissionFlow() {
        openTmsApp();

        handler.postDelayed(() -> {
            if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS)) {
                setFlowMode(MODE_IDLE);
            }
        }, 2500);
    }

    '''
    if insert_before not in serv:
        raise SystemExit('Nie znalazłam metody openTmsApp(), nie mam gdzie wkleić metody.')
    serv = serv.replace(insert_before, method + insert_before)

MAIN.write_text(main, encoding="utf-8")
SERV.write_text(serv, encoding="utf-8")
print("OK: dodano flow: Nadaj uprawnienia TMS i uruchom")
