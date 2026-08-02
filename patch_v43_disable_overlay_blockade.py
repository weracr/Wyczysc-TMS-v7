#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# 1. Całkowicie wyłącz overlay/blokadę z AccessibilityService.
#    Automatyzacja zostaje aktywna, ale nic nie przysłania ekranu.
new_update = '''private void updateOverlayVisibility() {
        // Overlay/blokada tymczasowo wyłączone podczas testów automatyzacji.
        hideAutomationOverlay();
    }'''
s = re.sub(r'private void updateOverlayVisibility\(\) \{.*?\n    \}', new_update, s, flags=re.S, count=1)

new_show = '''private void showAutomationOverlay() {
        // Overlay/blokada tymczasowo wyłączone podczas testów automatyzacji.
        hideAutomationOverlay();
    }'''
s = re.sub(r'private void showAutomationOverlay\(\) \{.*?\n    \}', new_show, s, flags=re.S, count=1)

# 2. Jeżeli gdzieś w logice własnej apki wywołuje showAutomationOverlay(), to metoda jest no-op,
# więc nie trzeba ruszać reszty flow.

# 3. Opcjonalnie wyczyść finalne wywołania hideAutomationOverlay bez zmian - są bezpieczne.

# 4. MainActivity: zostaw tylko krótki Toast, bez ekranu blokady, jeśli metoda istnieje.
if MAIN.exists():
    m = MAIN.read_text(encoding='utf-8')
    new_screen = '''private void showRepairInProgressScreen() {
        Toast.makeText(this, "Naprawa TMS w toku.", Toast.LENGTH_LONG).show();
    }'''
    if 'private void showRepairInProgressScreen()' in m:
        m = re.sub(r'private void showRepairInProgressScreen\(\) \{.*?\n    \}', new_screen, m, flags=re.S, count=1)
    MAIN.write_text(m, encoding='utf-8')

# sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: blokada/overlay została wyłączona, automatyzacja zostaje aktywna')
