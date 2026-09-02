#!/usr/bin/env python3
from pathlib import Path
import subprocess

REL = 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
p = Path.cwd() / REL
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')

# Pobierz wersje sprzed ostatniej zmiany, zamiast naprawiac 100 wtornych bledow nawiasow.
try:
    base = subprocess.check_output(['git', 'show', f'HEAD^:{REL}'], text=True, encoding='utf-8')
except Exception as e:
    raise SystemExit('Nie moge pobrac HEAD^. Najpierw upewnij sie, ze v79 byl zapisany w osobnym commicie. ' + str(e))

s = base

# Wylacz wszystkie zaslony podczas testu. Metody zostaja, ale nie sa wywolywane.
for old in [
    'showFullBlocker(',
    'showGuidanceWithHole(',
    'showStatusBanner('
]:
    # Nie usuwamy definicji metod. Podmieniamy tylko wywolania w glownym handlerze ponizej.
    pass

# Dodaj pole blokujace podwojne zaplanowanie klikniecia.
field_anchor = 'private long lastClickTime = 0;'
if field_anchor not in s:
    raise SystemExit('W wersji HEAD^ nie znaleziono lastClickTime.')
s = s.replace(field_anchor, field_anchor + '\n    private boolean pm95TapPending = false;', 1)

# Znajdz miejsce po zebraniu tekstu w glownym handlerze.
needles = [
    'String text = normalize(collectText(root));',
    'String screenText = normalize(collectText(root) + " " + collectEventText(event));'
]
needle = next((x for x in needles if x in s), None)
if not needle:
    raise SystemExit('Nie znaleziono pobierania tekstu ekranu w HEAD^.')
var = 'text' if needle.startswith('String text') else 'screenText'
call = needle + f'''\n\n        // PM95: precyzyjne punkty z Opcji programisty. Bez blokady ekranu.\n        hidePm95OverlayForTest();\n        if ((MODE_OPEN_TMS.equals(getFlowMode()) || MODE_GRANT_TMS_PERMISSIONS.equals(getFlowMode()))\n                && handlePm95ExactPoint({var})) {{\n            return;\n        }}'''
s = s.replace(needle, call, 1)

methods = r'''
    private void hidePm95OverlayForTest() {
        try {
            hideAllGuidance();
        } catch (Throwable ignored) {
            try {
                hideStatusBanner();
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    private boolean handlePm95ExactPoint(String rawText) {
        String text = normalize(rawText);
        if (pm95TapPending) return true;

        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            schedulePm95Tap(528, 1331, 1450, false);
            return true;
        }
        if (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")) {
            schedulePm95Tap(583, 1097, 1450, false);
            return true;
        }
        if (text.contains("dostep do kontaktow")) {
            schedulePm95Tap(566, 1157, 1200, false);
            return true;
        }
        if (text.contains("urzadzen w poblizu")) {
            schedulePm95Tap(614, 1202, 1200, false);
            return true;
        }
        if (text.contains("polaczen telefonicznych") || text.contains("zarzadzanie nimi")) {
            schedulePm95Tap(554, 1184, 1200, false);
            return true;
        }
        if (text.contains("dostep do zdjec") && text.contains("muzyki") && text.contains("dzwiekow")) {
            schedulePm95Tap(553, 1184, 1200, false);
            return true;
        }
        if (text.contains("dostep do lokalizacji") && text.contains("zaktualizuj ustawienia")) {
            schedulePm95Tap(626, 1329, 1450, false);
            return true;
        }
        if (text.contains("lokalizacja - dostep") && text.contains("zawsze zezwalaj")) {
            schedulePm95Tap(106, 1158, 1600, true);
            return true;
        }
        return false;
    }

    private void schedulePm95Tap(int refX, int refY, long delayMs, boolean backAfter) {
        pm95TapPending = true;
        handler.postDelayed(() -> {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            int x = Math.round(width * (refX / 1024f));
            int y = Math.round(height * (refY / 2048f));
            tapAt(x, y);
            markClicked();

            if (backAfter) {
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    setFlowMode(MODE_IDLE);
                    Toast.makeText(this, "Gotowe. Mozna uruchomic TMS.", Toast.LENGTH_LONG).show();
                }, 1600);
            }
            handler.postDelayed(() -> pm95TapPending = false, 800);
        }, delayMs);
    }
'''

# Wstaw metody na pewno w zakresie klasy: tuz przed ostatnia klamra klasy.
last = s.rfind('\n}')
if last < 0:
    raise SystemExit('Nie znaleziono koncowej klamry klasy w HEAD^.')
s = s[:last] + methods + s[last:]

# Kontrole struktury.
if s.count('private boolean handlePm95ExactPoint(') != 1:
    raise SystemExit('Blad liczby metod handlePm95ExactPoint.')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostal HTML: {token}')

# Zachowaj zepsuta wersje jako backup i zapisz odbudowana.
backup = p.with_suffix('.java.v79-broken-backup')
backup.write_text(p.read_text(encoding='utf-8'), encoding='utf-8')
p.write_text(s, encoding='utf-8')
print('OK: przywrocono HEAD^, dodano v80 w zakresie klasy i zapisano backup v79')
