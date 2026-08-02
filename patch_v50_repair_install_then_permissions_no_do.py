#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not MAIN.exists():
    raise SystemExit(f'Nie znaleziono pliku: {MAIN}')

main = MAIN.read_text(encoding='utf-8')

# Cel:
# Napraw TMS ma ZAWSZE isc w kolejnosci:
# 1. odinstalowanie TMS
# 2. instalacja TMS
# 3. proba DPM
# 4. jezeli brak Device Owner/Profile Owner albo DPM nie nada wszystkiego -> wejscie w nadawanie uprawnien przez ustawienia/Accessibility
#
# NIE konczymy flow od razu po nieudanym DPM, bo na tym urzadzeniu Device Owner/Profile Owner = NIE.

new_grant_after_install = '''private void grantTmsPermissionsAfterInstall() {
        setFlowMode(MODE_FULL_REPAIR);

        boolean grantedByPolicy = grantTmsPermissionsByPolicy();

        if (grantedByPolicy) {
            clearFlowMode();
            Toast.makeText(this, "Gotowe. Uprawnienia zostały nadane. Można uruchomić TMS.", Toast.LENGTH_LONG).show();
            return;
        }

        // Brak Device Owner/Profile Owner albo Android nie pozwolił nadać wszystkich zgód.
        // Kontynuujemy więc flow przez ustawienia i Accessibility.
        Toast.makeText(this, "Brak Device Owner/Profile Owner. Nadaję uprawnienia przez ustawienia.", Toast.LENGTH_LONG).show();
        grantTmsPermissionsThenOpen();
    }'''

pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
if re.search(pat, main, flags=re.S):
    main = re.sub(pat, new_grant_after_install, main, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam grantTmsPermissionsAfterInstall() w MainActivity.java')

# Upewnij sie, ze Napraw TMS startuje od odinstalowania, gdy TMS jest zainstalowany.
# Tego nie zmieniamy, tylko defensywnie ustawiamy flagi.
main = main.replace(
    'if (isInstalled(detectedTmsPackage)) {\n            repairAfterUninstall = true;',
    'if (isInstalled(detectedTmsPackage)) {\n            setFlowMode(MODE_FULL_REPAIR);\n            grantPermissionsAfterInstall = true;\n            repairAfterUninstall = true;',
    1
)

# Upewnij sie, ze po odinstalowaniu instalacja dalej jest w FULL_REPAIR_FLOW.
main = main.replace(
    'if (repairAfterUninstall && !isInstalled(detectedTmsPackage)) {\n            repairAfterUninstall = false;',
    'if (repairAfterUninstall && !isInstalled(detectedTmsPackage)) {\n            setFlowMode(MODE_FULL_REPAIR);\n            repairAfterUninstall = false;',
    1
)

# Dodaj/utrzymaj przycisk awaryjny STOP w panelu admina.
stop_button = 'addAdminButton(root, "STOP automatyzacji", v -> { clearFlowMode(); Toast.makeText(this, "Automatyzacja zatrzymana.", Toast.LENGTH_LONG).show(); });'
if 'STOP automatyzacji' not in main:
    marker = 'addAdminButton(root, "Odśwież status", v -> refreshStatus());'
    if marker in main:
        main = main.replace(marker, stop_button + '\n\n        ' + marker, 1)

# Jeśli w serwisie patch v49 wyciął overlay, zostawiamy bez blokady zgodnie z aktualnym testowaniem.
# Upewniamy sie tylko, ze w IDLE serwis nic nie robi.
if SERV.exists():
    serv = SERV.read_text(encoding='utf-8')
    if 'if (isIdleMode()) { hideAutomationOverlay(); return; }' not in serv:
        serv = serv.replace(
            'if (isDetailsOnlyMode() || isIdleMode()) return;',
            'if (isIdleMode()) { hideAutomationOverlay(); return; }\n        if (isDetailsOnlyMode()) return;'
        )
    SERV.write_text(serv, encoding='utf-8')

# sanity
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main)
if bad:
    raise SystemExit(f'Podejrzane znaki w MainActivity.java: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
print('OK: Napraw TMS najpierw odinstaluje i zainstaluje TMS, a potem przy braku Device Owner przejdzie do nadawania uprawnień przez ustawienia')
