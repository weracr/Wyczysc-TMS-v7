#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# v70: naprawia WYŁĄCZNIE dwa ekrany lokalizacji na PM95.
# Przyczyna: dialog lokalizacji może nie raportować pakietu PermissionController,
# a ekran końcowy może wejść bez zachowanej flagi waitingForAlwaysLocation.
# Rozpoznanie jest więc oparte na unikalnym tekście ekranu, nie na packageName/flagach.

if 'private long lastInitialLocationSchedule' not in s:
    anchor = 'private long lastAppInfoTapTime = 0;'
    s = s.replace(anchor, anchor + '\n    private long lastInitialLocationSchedule = 0;\n    private long lastAlwaysLocationSchedule = 0;')

# Wstaw twardą obsługę obu ekranów zaraz po zebraniu screenText, przed innymi handlerami.
needle = '''        String screenText = normalize(collectText(root) + " " + collectEventText(event));'''
if needle not in s:
    raise SystemExit('Nie znaleziono miejsca po screenText w handleScreen().')

block = '''        String screenText = normalize(collectText(root) + " " + collectEventText(event));

        // PM95: dwa ekrany lokalizacji rozpoznajemy po unikalnej treści,
        // niezależnie od pakietu zgłoszonego przez AccessibilityEvent.
        if (isMode(MODE_OPEN_TMS) && isPm95InitialLocationDialog(screenText)) {
            schedulePm95InitialLocationClick();
            return;
        }

        if (isMode(MODE_OPEN_TMS) && isPm95AlwaysLocationScreen(screenText)) {
            schedulePm95AlwaysLocationClick();
            return;
        }'''
s = s.replace(needle, block, 1)

helpers = '''private boolean isPm95InitialLocationDialog(String screenText) {
        String text = normalize(screenText);
        return (text.contains("dostep do lokalizacji urzadzenia")
                || text.contains("dokladna") && text.contains("przyblizona"))
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj");
    }

    private void schedulePm95InitialLocationClick() {
        long now = System.currentTimeMillis();
        if (now - lastInitialLocationSchedule < 2500) return;
        lastInitialLocationSchedule = now;

        // PM95 potrzebuje chwili po przejściu z aparatu do lokalizacji.
        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String text = normalize(collectText(root));
            if (!isPm95InitialLocationDialog(text)) return;

            boolean clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Screen PM95: środek pierwszego przycisku lokalizacji około 61,5% wysokości.
                    clicked = tapAt(b.centerX(), b.top + (int) (b.height() * 0.615f));
                }
            }

            if (clicked) {
                markClicked();
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                runtimePermissionsClicked++;
                retryCurrentPermissionWindow();
            }
        }, 1400);
    }

    private boolean isPm95AlwaysLocationScreen(String screenText) {
        String text = normalize(screenText);
        return text.contains("lokalizacja - dostep")
                && text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");
    }

    private void schedulePm95AlwaysLocationClick() {
        long now = System.currentTimeMillis();
        if (now - lastAlwaysLocationSchedule < 3000) return;
        lastAlwaysLocationSchedule = now;
        waitingForAlwaysLocation = true;

        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String text = normalize(collectText(root));
            if (!isPm95AlwaysLocationScreen(text)) return;

            boolean clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Zawsze zezwalaj", "Allow all the time", "Always allow"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Pełny screen PM95: radio pierwszej opcji około x=9,3%, y=51,5%.
                    clicked = tapAt(b.left + (int) (b.width() * 0.093f),
                            b.top + (int) (b.height() * 0.515f));
                }
            }

            if (!clicked) return;
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();

            handler.postDelayed(() -> verifyPm95AlwaysLocationAndBack(0), 1200);
        }, 1500);
    }

    private void verifyPm95AlwaysLocationAndBack(int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            waitingForAlwaysLocation = false;
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                scheduleRuntimeFlowFinishCheck();
            }, 800);
            return;
        }

        if (attempt >= 2) return;
        Rect b = new Rect();
        root.getBoundsInScreen(b);
        if (b.isEmpty()) return;

        // Naprzemiennie klik w tekst/wiersz i radio.
        float xRatio = attempt == 0 ? 0.35f : 0.093f;
        tapAt(b.left + (int) (b.width() * xRatio),
                b.top + (int) (b.height() * 0.515f));
        markClicked();
        handler.postDelayed(() -> verifyPm95AlwaysLocationAndBack(attempt + 1), 1200);
    }

    '''
marker = 'private boolean isUninstallConfirmationDialog'
if marker not in s:
    raise SystemExit('Nie znaleziono miejsca na helpery v70.')
s = s.replace(marker, helpers + marker, 1)

# Nie kończ flow zanim trwa końcowa lokalizacja.
pat = r'private void scheduleRuntimeFlowFinishCheck\(\) \{.*?\n    \}'
m = re.search(pat, s, flags=re.S)
if m:
    body = m.group(0)
    if 'if (waitingForAlwaysLocation) return;' not in body:
        body = body.replace('if (!isMode(MODE_OPEN_TMS)) return;',
                            'if (!isMode(MODE_OPEN_TMS)) return;\n            if (waitingForAlwaysLocation) return;')
        s = s[:m.start()] + body + s[m.end():]

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')

# Kontrola duplikatów.
for sig in ['private boolean isPm95InitialLocationDialog(', 'private boolean isPm95AlwaysLocationScreen(']:
    if s.count(sig) != 1:
        raise SystemExit(f'Nieprawidłowa liczba metody: {sig}')

p.write_text(s, encoding='utf-8')
print('OK: v70 wymusza oba ekrany lokalizacji po unikalnym tekscie, z opoznieniem i weryfikacja')
