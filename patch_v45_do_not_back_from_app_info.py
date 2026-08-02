#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# Problem: ekran "Informacje o aplikacji" zawiera wiersz "Otwieraj domyślnie".
# Stara logika traktowała sam widoczny wiersz jako wejście w ekran "Otwieraj domyślnie"
# i robiła BACK, więc aplikacja wracała do Wyczyść TMS zanim kliknęła Uprawnienia.
# Naprawa: ekran "Otwieraj domyślnie" rozpoznajemy tylko wtedy, gdy NIE jesteśmy na ekranie App Info
# i jednocześnie nie widać wiersza "Uprawnienia".

new_default_open = '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        String text = normalize(screenText);

        boolean settingsScreen = packageName.contains("settings");
        boolean hasDefaultOpenText = text.contains("otwieraj domyslnie")
                || text.contains("open by default")
                || text.contains("obslugiwane linki")
                || text.contains("supported links");

        boolean looksLikeAppInfo = text.contains("informacje o aplikacji")
                || text.contains("o aplikacji")
                || text.contains("app info")
                || text.contains("uprawnienia")
                || text.contains("permissions")
                || text.contains("brak przyznanych uprawnien");

        return settingsScreen
                && containsTmsText(text)
                && hasDefaultOpenText
                && !looksLikeAppInfo;
    }'''

pat = r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_default_open, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody isDefaultOpenScreen().')

# Dodatkowo priorytet: jeśli jesteśmy na App Info, kliknij Uprawnienia zanim sprawdzisz default-open.
# Jeżeli w Twoim pliku default-open jest przed App Info, zamieniamy kolejność.
old_order = '''if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        if (isRuntimePermissionDialog(packageName, screenText)) {'''
new_order = '''if (isTmsAppInfoScreen(packageName, screenText)) {
            clickAppInfoPermissions(root);
            return;
        }

        if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        if (isRuntimePermissionDialog(packageName, screenText)) {'''
if old_order in s and new_order not in s:
    s = s.replace(old_order, new_order, 1)

# Usuń późniejszy duplikat App Info handlera, jeśli po zmianie występuje drugi raz.
pattern_app_info_block = '''if (isTmsAppInfoScreen(packageName, screenText)) {
            clickAppInfoPermissions(root);
            return;
        }

        if (isAppPermissionsListScreen(packageName, screenText)) {'''
first = s.find(pattern_app_info_block)
if first != -1:
    second = s.find(pattern_app_info_block, first + 1)
    if second != -1:
        s = s[:second] + '''if (isAppPermissionsListScreen(packageName, screenText)) {''' + s[second + len(pattern_app_info_block):]

bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: App Info nie będzie już traktowane jako Otwieraj domyślnie i nie cofnie do Wyczyść TMS')
