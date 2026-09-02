#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')
s = p.read_text(encoding='utf-8')

old = '''        Toast.makeText(this, "Uruchamiam TMS i nadaję zgody po kolei.", Toast.LENGTH_LONG).show();
        launchTmsForRuntimePermissions();'''
new = '''        Toast.makeText(this, "Najpierw ustawiam lokalizację TMS, potem uruchomię aplikację.", Toast.LENGTH_LONG).show();
        openTmsSettingsBeforeFirstLaunch();'''
if old not in s:
    raise SystemExit('Nie znaleziono końcówki grantTmsPermissionsAfterInstall().')
s = s.replace(old, new, 1)

method = '''
    private void openTmsSettingsBeforeFirstLaunch() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            clearFlowMode();
            Toast.makeText(this, "Nie można otworzyć ustawień TMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
'''
marker = '    private void launchTmsForRuntimePermissions() {'
if method.strip() not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono launchTmsForRuntimePermissions().')
    s = s.replace(marker, method + '\n' + marker, 1)

for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit('Niezgodna liczba klamer w MainActivity.java.')

p.write_text(s, encoding='utf-8')
print('OK: MainActivity po instalacji otwiera ustawienia lokalizacji przed pierwszym uruchomieniem TMS')
