#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')

s = p.read_text(encoding='utf-8')

old = '''        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            return ScreenAction.point("location_initial", 1500, 528, 1331, false);
        }'''

new = '''        if ((text.contains("lokalizacji urzadzenia") || text.contains("dostep do lokalizacji"))
                && (text.contains("podczas uzywania aplikacji")
                || text.contains("podczas korzystania z aplikacji"))
                && text.contains("tylko tym razem")) {
            return ScreenAction.textWithFallback(
                    "location_initial",
                    1800,
                    Arrays.asList(
                            "Podczas używania aplikacji",
                            "Podczas uzywania aplikacji",
                            "Podczas korzystania z aplikacji",
                            "While using the app"
                    ),
                    528,
                    1331
            );
        }'''

if old not in s:
    raise SystemExit('Nie znaleziono aktualnego bloku location_initial z v82.')
s = s.replace(old, new, 1)

old_click = '''                if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }'''

new_click = '''                if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                    if (!clicked && action.referenceX > 0 && action.referenceY > 0) {
                        clicked = tapReferencePoint(action.referenceX, action.referenceY);
                    }
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }'''

if old_click not in s:
    raise SystemExit('Nie znaleziono bloku wykonania ScreenAction.')
s = s.replace(old_click, new_click, 1)

factory_anchor = '''        static ScreenAction text(String key, long delayMs, List<String> labels) {
            return new ScreenAction(key, delayMs, labels, 0, 0, false);
        }
'''
factory_new = factory_anchor + '''
        static ScreenAction textWithFallback(String key, long delayMs, List<String> labels,
                                             int referenceX, int referenceY) {
            return new ScreenAction(key, delayMs, labels, referenceX, referenceY, false);
        }
'''

if factory_anchor not in s:
    raise SystemExit('Nie znaleziono fabryki ScreenAction.text().')
s = s.replace(factory_anchor, factory_new, 1)

# Ponawianie tego samego ekranu trochę szybciej, jeśli pierwszy klik nie przełączy okna.
s = s.replace('handler.postDelayed(() -> pendingScreenKey = "", 1200);',
              'handler.postDelayed(() -> pendingScreenKey = "", 900);', 1)

for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
    if token in s:
        raise SystemExit(f'W pliku pozostał znacznik HTML: {token}')
if s.count('textWithFallback(') != 2:
    raise SystemExit('Nieprawidłowa liczba wystąpień textWithFallback().')
if s.count('{') != s.count('}'):
    raise SystemExit('Niezgodna liczba klamer.')

p.write_text(s, encoding='utf-8')
print('OK: pierwsza lokalizacja klika po tekście, a współrzędne są tylko fallbackiem')
