#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# 1. Usuń pełnoekranową blokadę i wszystkie komunikaty overlay na czas testu.
for call in [
    r'showFullBlocker\([^;]*\);',
    r'showGuidanceWithHole\([^;]*\);',
    r'showStatusBanner\([^;]*\);'
]:
    s = re.sub(call, 'hideAllGuidance();', s, flags=re.S)

# Jeśli nie ma hideAllGuidance, użyj istniejącego hideStatusBanner.
if 'private void hideAllGuidance()' not in s:
    s = s.replace('hideAllGuidance();', 'hideStatusBanner();')

# 2. Dodaj pola kolejki precyzyjnych kliknięć.
anchor = 'private long lastClickTime = 0;'
if anchor not in s:
    raise SystemExit('Nie znaleziono lastClickTime.')
if 'private boolean preciseTapPending' not in s:
    s = s.replace(anchor, anchor + '\n    private boolean preciseTapPending = false;')

# 3. W handleCurrentScreen/handleScreen uruchom precyzyjny handler przed ogólnymi kliknięciami.
needle_options = [
    'String text = normalize(collectText(root));',
    'String screenText = normalize(collectText(root) + " " + collectEventText(event));'
]
inserted = False
for needle in needle_options:
    if needle in s:
        var = 'text' if needle.startswith('String text') else 'screenText'
        addition = needle + f'''\n\n        // PM95: dokładne punkty z Lokalizacji wskaźnika. Overlay wyłączony.\n        if ((MODE_OPEN_TMS.equals(getFlowMode()) || MODE_GRANT_TMS_PERMISSIONS.equals(getFlowMode()))\n                && handlePm95PointerCoordinates(root, {var})) {{\n            return;\n        }}'''
        s = s.replace(needle, addition, 1)
        inserted = True
        break
if not inserted:
    raise SystemExit('Nie znaleziono miejsca do dodania obsługi współrzędnych.')

# 4. Dodaj handler wykorzystujący dokładne X/Y ze screenów 1024x2048.
methods = '''private boolean handlePm95PointerCoordinates(AccessibilityNodeInfo root, String rawText) {
        String text = normalize(rawText);
        if (preciseTapPending) return true;

        // Pierwszy ekran lokalizacji ma pierwszeństwo przed aparatem i dialogami ogólnymi.
        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            schedulePreciseTap(528, 1331, 1450, false);
            return true;
        }

        if (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")) {
            schedulePreciseTap(583, 1097, 1450, false);
            return true;
        }

        if (text.contains("dostep do kontaktow")) {
            schedulePreciseTap(566, 1157, 1200, false);
            return true;
        }

        if (text.contains("urzadzen w poblizu") || text.contains("urzadzen w poblizu")) {
            schedulePreciseTap(614, 1202, 1200, false);
            return true;
        }

        if (text.contains("polaczen telefonicznych") || text.contains("zarzadzanie nimi")) {
            schedulePreciseTap(554, 1184, 1200, false);
            return true;
        }

        if (text.contains("dostep do zdjec") && text.contains("muzyki") && text.contains("dzwiekow")) {
            schedulePreciseTap(553, 1184, 1200, false);
            return true;
        }

        if (text.contains("dostep do lokalizacji") && text.contains("zaktualizuj ustawienia")) {
            schedulePreciseTap(626, 1329, 1450, false);
            return true;
        }

        if (text.contains("lokalizacja - dostep") && text.contains("zawsze zezwalaj")) {
            // Klik w radio Zawsze zezwalaj, potem systemowy BACK do TMS.
            schedulePreciseTap(106, 1158, 1600, true);
            return true;
        }

        return false;
    }

    private void schedulePreciseTap(int referenceX, int referenceY, long delayMs, boolean backAfterTap) {
        preciseTapPending = true;
        handler.postDelayed(() -> {
            try {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                int x = Math.round(width * (referenceX / 1024f));
                int y = Math.round(height * (referenceY / 2048f));
                tapAt(x, y);
                markClicked();

                if (backAfterTap) {
                    handler.postDelayed(() -> {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        setFlowMode(MODE_IDLE);
                        Toast.makeText(this,
                                "Gotowe. Można uruchomić TMS.",
                                Toast.LENGTH_LONG).show();
                    }, 1600);
                }
            } finally {
                handler.postDelayed(() -> preciseTapPending = false, 700);
            }
        }, delayMs);
    }

    '''
marker_options = ['private boolean isInitialLocationDialog', 'private boolean isUninstallDialog', 'private boolean isOwnAppOrAdminPanel']
marker = next((m for m in marker_options if m in s), None)
if not marker:
    raise SystemExit('Nie znaleziono miejsca na metody PM95.')
if 'private boolean handlePm95PointerCoordinates(' not in s:
    s = s.replace(marker, methods + marker, 1)

# 5. Większy odstęp kliknięć.
s = re.sub(r'private static final long CLICK_GUARD_MS = \d+;',
           'private static final long CLICK_GUARD_MS = 1200;', s, count=1)
s = re.sub(r'private static final long CLICK_DELAY_MS = \d+;',
           'private static final long CLICK_DELAY_MS = 1200;', s, count=1)

# 6. Sprzątaj overlay przy każdym ekranie, jeśli został z wcześniejszej wersji.
if 'hideAllGuidance();' in s:
    # Dodaj po pobraniu roota, ale tylko raz.
    root_line = 'AccessibilityNodeInfo root = getRootInActiveWindow();'
    pos = s.find(root_line)
    if pos >= 0:
        end = pos + len(root_line)
        tail = s[end:end+120]
        if 'hideAllGuidance();' not in tail:
            s = s[:end] + '\n        hideAllGuidance();' + s[end:]

# Walidacja.
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
if s.count('private boolean handlePm95PointerCoordinates(') != 1:
    raise SystemExit('Nieprawidłowa liczba handlerów współrzędnych.')

p.write_text(s, encoding='utf-8')
print('OK: usunięto blokadę i ustawiono dokładne współrzędne PM95 z Lokalizacji wskaźnika')
