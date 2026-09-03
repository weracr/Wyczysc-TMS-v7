#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not MAIN.exists() or not SERV.exists():
    raise SystemExit('Uruchom w glownym katalogu repo, obok folderu app.')

m = MAIN.read_text(encoding='utf-8')
s = SERV.read_text(encoding='utf-8')

# 1. Po instalacji nie próbuj Device Owner. Wejdz najpierw do widoku admina,
# potem uruchom dokładnie tę samą metodę openTmsSettings(), która działa z przycisku admina.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
replacement = '''private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(
                this,
                "TMS zainstalowany. Otwieram ustawienia lokalizacji.",
                Toast.LENGTH_LONG
        ).show();
        openAdminRouteToTmsSettings();
    }'''

if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall() w MainActivity.java')
m = re.sub(pat, replacement, m, count=1, flags=re.S)

method = '''
    private void openAdminRouteToTmsSettings() {
        // buildAdminScreen() normalnie zeruje tryb, dlatego po zbudowaniu ekranu
        // ustawiamy właściwy tryb ponownie.
        buildAdminScreen();
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);

        handler.postDelayed(() -> {
            setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
            openTmsSettings();
        }, 1200);
    }

'''

if 'private void openAdminRouteToTmsSettings()' not in m:
    marker = '    private void openTmsSettings() {'
    if marker not in m:
        raise SystemExit('Nie znaleziono openTmsSettings() w MainActivity.java')
    m = m.replace(marker, method + marker, 1)

# 2. openTmsSettings nie może zmieniać trybu na DETAILS_ONLY podczas automatycznej naprawy.
old_open = '''    private void openTmsSettings() {
        setFlowMode(MODE_DETAILS_ONLY);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);'''
new_open = '''    private void openTmsSettings() {
        if (!MODE_GRANT_TMS_PERMISSIONS.equals(getFlowMode())) {
            setFlowMode(MODE_DETAILS_ONLY);
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);'''
if old_open in m:
    m = m.replace(old_open, new_open, 1)
else:
    # Wariant z innym formatowaniem: usuń bezwarunkowe DETAILS_ONLY tylko w tej metodzie.
    open_pat = r'(private void openTmsSettings\(\) \{)\s*setFlowMode\(MODE_DETAILS_ONLY\);'
    if re.search(open_pat, m):
        m = re.sub(open_pat,
                   r'\1\n        if (!MODE_GRANT_TMS_PERMISSIONS.equals(getFlowMode())) {\n            setFlowMode(MODE_DETAILS_ONLY);\n        }',
                   m, count=1)
    else:
        raise SystemExit('Nie znaleziono początku openTmsSettings() do poprawy trybu.')

# 3. Instalator ma klikać Gotowe, nie Otwórz. Usuń Otwórz/Open z listy instalatora,
# pozostaw Gotowe/Done na początku.
s = re.sub(r'\s*,?\s*"Otwórz"\s*,\s*"Otworz"\s*,\s*"Open"', '', s)
s = re.sub(r'\s*,?\s*"Open"', '', s)

# Upewnij się, że Gotowe/Done są przed instalacyjnymi wariantami.
list_pat = r'private final List<String> installerButtons = Arrays\.asList\(.*?\);'
list_new = '''private final List<String> installerButtons = Arrays.asList(
            "Gotowe", "Done", "Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj",
            "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue",
            "Zainstaluj mimo to", "Install anyway", "OK", "Ok"
    );'''
if re.search(list_pat, s, flags=re.S):
    s = re.sub(list_pat, list_new, s, count=1, flags=re.S)
else:
    raise SystemExit('Nie znaleziono installerButtons w serwisie.')

# 4. Daj przyciskowi Gotowe chwilę i wróć do MainActivity, aby onResume uruchomił trasę admina.
# Jeśli delay instalatora jest inny, ustaw go na 1400 ms.
s = re.sub(r'(Action\.text\("installer",\s*)\d+', r'\g<1>1400', s)
s = re.sub(r'(ScreenAction\.text\("installer",\s*)\d+', r'\g<1>1400', s)

# Walidacja.
for name, text in [('MainActivity.java', m), ('PermissionClickerAccessibilityService.java', s)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
        if token in text:
            raise SystemExit(f'{name}: znaleziono HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: niezgodne klamry {text.count("{")} / {text.count("}")}')

if m.count('private void openAdminRouteToTmsSettings()') != 1:
    raise SystemExit('Nieprawidlowa liczba openAdminRouteToTmsSettings().')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
print('OK: po Gotowe aplikacja wraca, otwiera widok admina i uruchamia dzialajace Szczegoly TMS w ustawieniach')
