#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')

s = p.read_text(encoding='utf-8')

# Ten patch jest przeznaczony na wersję po v74.
required = [
    'showManualInstructionBanner(',
    'isManualInitialLocationScreen(',
    'isManualAlwaysLocationScreen('
]
for item in required:
    if item not in s:
        raise SystemExit(f'Brakuje {item}. Najpierw zastosuj v74 na działającej wersji serwisu.')

# 1. Podmień blok instrukcji w handleScreen:
# - normalne automatyczne kroki: spokojny komunikat statusowy,
# - pierwszy ekran lokalizacji: konkretne polecenie,
# - końcowy ekran lokalizacji: konkretne polecenie,
# - poza aktywnym flow: usuń banner.
pattern = re.compile(
    r'\s*// HYBRYDA PM95:.*?hideManualInstructionBanner\(\);',
    re.S
)

replacement = '''

        // Pasek statusu nie przechwytuje dotyku i nie zasłania przycisków systemowych.
        if (isMode(MODE_OPEN_TMS) && isManualInitialLocationScreen(screenText)) {
            showManualInstructionBanner(
                    "Wymagane działanie: wybierz PODCZAS UŻYWANIA APLIKACJI");
            return;
        }

        if (isMode(MODE_OPEN_TMS) && isManualAlwaysLocationScreen(screenText)) {
            showManualInstructionBanner(
                    "Wymagane działanie: wybierz ZAWSZE ZEZWALAJ, a następnie naciśnij WSTECZ");
            return;
        }

        if (isAutomationRunning()) {
            showManualInstructionBanner(
                    "Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.");
        } else {
            hideManualInstructionBanner();
        }'''

if not pattern.search(s):
    raise SystemExit('Nie znaleziono bloku HYBRYDA PM95 z v74 w handleScreen().')
s = pattern.sub(replacement, s, count=1)

# 2. Popraw wygląd bannera: status granatowy, instrukcja ręczna czerwona.
# Dodaj dynamiczny kolor na podstawie treści, ale zachowaj FLAG_NOT_TOUCHABLE.
old = '''            if (manualInstructionText != null) {
                manualInstructionText.setText(message);
            }'''
new = '''            if (manualInstructionText != null) {
                manualInstructionText.setText(message);
                if (message.startsWith("Wymagane działanie")) {
                    manualInstructionText.setBackgroundColor(Color.rgb(180, 35, 24));
                } else {
                    manualInstructionText.setBackgroundColor(Color.rgb(37, 99, 235));
                }
            }'''
if old not in s:
    raise SystemExit('Nie znaleziono ustawiania tekstu bannera z v74.')
s = s.replace(old, new, 1)

# 3. Domyślny kolor przy tworzeniu bannera ustaw na granatowy.
s = s.replace(
    'banner.setBackgroundColor(Color.rgb(180, 35, 24));',
    'banner.setBackgroundColor(Color.rgb(37, 99, 235));',
    1
)

# 4. Nie pozwól, żeby stary own-app branch usuwał banner w aktywnym flow.
# Jeśli występuje wywołanie showAutomationOverlay, banner statusowy już je zastępuje.
s = s.replace('                showAutomationOverlay();',
              '                showManualInstructionBanner("Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.");')

# 5. Sanity.
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'W pliku pozostał HTML: {token}')
if 'WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE' not in s:
    raise SystemExit('Banner nie ma FLAG_NOT_TOUCHABLE, przerwano dla bezpieczeństwa.')
if s.count('Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.') < 1:
    raise SystemExit('Nie dodano tekstu statusowego.')

p.write_text(s, encoding='utf-8')
print('OK: dodano spójny banner naprawy oraz osobne instrukcje dla obu kroków lokalizacji')
