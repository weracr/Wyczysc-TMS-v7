#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')

s = p.read_text(encoding='utf-8')

# Naprawia tekst rozbity na dwie fizyczne linie Java:
# message.setText("Potwierdź naprawę TMS
#
# Wybierz jasny przycisk OK");
pattern = re.compile(
    r'message\.setText\(\s*"Potwierdź naprawę TMS\s*\n(?:\s*\n)?\s*Wybierz jasny przycisk OK"\s*\);',
    re.MULTILINE
)
replacement = 'message.setText("Potwierdź naprawę TMS\\n\\nWybierz jasny przycisk OK");'

if pattern.search(s):
    s = pattern.sub(replacement, s, count=1)
else:
    # Bardziej elastyczny wariant dla spacji/znaków po kopiowaniu.
    start = s.find('message.setText("Potwierdź naprawę TMS')
    if start < 0:
        raise SystemExit('Nie znaleziono uszkodzonego message.setText przy linii około 484.')
    end = s.find('");', start)
    if end < 0:
        raise SystemExit('Nie znaleziono końca uszkodzonego message.setText.')
    s = s[:start] + replacement + s[end + 3:]

for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
    if token in s:
        raise SystemExit(f'W pliku pozostał znacznik HTML: {token}')

if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodna liczba klamer: {s.count("{")} / {s.count("}")}')

if 'message.setText("Potwierdź naprawę TMS\\n\\nWybierz jasny przycisk OK");' not in s:
    raise SystemExit('Tekst nie został zapisany z sekwencjami \\n.')

p.write_text(s, encoding='utf-8')
print('OK: naprawiono unclosed string literal w komunikacie blokady odinstalowania')
