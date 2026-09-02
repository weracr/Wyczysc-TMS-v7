#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')
s = p.read_text(encoding='utf-8')

old = '''    private boolean tapReferencePoint(int referenceX, int referenceY) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int x = Math.round(width * (referenceX / 1024f));
        int y = Math.round(height * (referenceY / 2048f));
        return tapAt(x, y);
    }'''

new = '''    private boolean tapReferencePoint(int referenceX, int referenceY) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        // PM95 raportuje aplikacji wysokość obszaru roboczego bez części pasków systemowych.
        // Lokalizacja wskaźnika pokazuje natomiast współrzędne całego ekranu 1024x2048.
        // Dlatego na PM95 używamy surowych punktów z Lokalizacji wskaźnika bez skalowania.
        if (width >= 1000 && width <= 1050) {
            return tapAt(referenceX, referenceY);
        }

        int x = Math.round(width * (referenceX / 1024f));
        int y = Math.round(height * (referenceY / 2048f));
        return tapAt(x, y);
    }'''

if old not in s:
    raise SystemExit('Nie znaleziono metody tapReferencePoint z czystego serwisu v82-v85.')
s = s.replace(old, new, 1)

# Dla pierwszej lokalizacji wymuś punkt bez próby ACTION_CLICK/bounds.
patterns = [
    r'return ScreenAction\.locationGesture\("location_initial",\s*1900\);',
    r'return ScreenAction\.textWithFallback\(\s*"location_initial".*?\);',
    r'return ScreenAction\.point\("location_initial",\s*\d+,\s*\d+,\s*\d+,\s*false\);'
]
replacement = 'return ScreenAction.point("location_initial", 2200, 488, 1329, false);'
changed = False
for pat in patterns:
    if re.search(pat, s, flags=re.S):
        s = re.sub(pat, replacement, s, count=1, flags=re.S)
        changed = True
        break
if not changed:
    raise SystemExit('Nie znaleziono akcji location_initial.')

# Dłuższy gest dotknięcia.
s = s.replace('new GestureDescription.StrokeDescription(path, 0, 140)',
              'new GestureDescription.StrokeDescription(path, 100, 300)')
s = s.replace('new GestureDescription.StrokeDescription(path, 120, 260)',
              'new GestureDescription.StrokeDescription(path, 100, 300)')

for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')

p.write_text(s, encoding='utf-8')
print('OK: PM95 używa surowego punktu X=488 Y=1329 bez skalowania przez heightPixels')
