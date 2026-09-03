#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')
s = p.read_text(encoding='utf-8')

# Wróć do działającego przebiegu: po instalacji uruchom TMS i obsłuż zgody runtime.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
new = '''private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(this,
                "Uruchamiam TMS i nadaję pozostałe zgody.",
                Toast.LENGTH_LONG).show();
        launchTmsForRuntimePermissions();
    }'''
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall()')
s = re.sub(pat, new, s, count=1, flags=re.S)

# Usuń eksperymentalną trasę przez panel administratora.
s = re.sub(r'\n\s*private void openAdminRouteToTmsSettings\(\) \{.*?\n    \}\n', '\n', s, count=1, flags=re.S)

# Tekst dialogu zgodny z finalnym przebiegiem.
s = s.replace(
    'Aplikacja odinstaluje TMS, zainstaluje najnowszą wersję APK z Download, spróbuje nadać uprawnienia programowo, a jeśli Android na to nie pozwoli, uruchomi flow przez ustawienia.',
    'Aplikacja odinstaluje TMS, zainstaluje najnowszą wersję i automatycznie nada większość zgód. Przy lokalizacji pojawi się czytelna instrukcja dla kierowcy.'
)

for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
    if token in s:
        raise SystemExit(f'W MainActivity.java znaleziono HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')

p.write_text(s, encoding='utf-8')
print('OK: przywrócono runtime flow; lokalizacja jest ręczna z komunikatem')
