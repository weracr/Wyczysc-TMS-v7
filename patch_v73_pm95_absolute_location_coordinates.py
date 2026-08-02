#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')

s = p.read_text(encoding='utf-8')

# PM95: stosujemy absolutne wymiary ekranu z DisplayMetrics zamiast bounds roota.
# Root Accessibility na ekranach systemowych może mieć inne bounds niż cały ekran,
# co przesuwało wcześniejsze kliknięcia mimo poprawnych proporcji ze screena.

if 'private long lastPm95LocationTap' not in s:
    anchor = 'private long lastAppInfoTapTime = 0;'
    s = s.replace(anchor, anchor + '\n    private long lastPm95LocationTap = 0;')

# Dodaj rozpoznanie i obsługę przed wszystkimi ogólnymi handlerami.
needle = '        String screenText = normalize(collectText(root) + " " + collectEventText(event));'
if needle not in s:
    raise SystemExit('Nie znaleziono screenText w handleScreen().')

insert = needle + '''

        if (isMode(MODE_OPEN_TMS) && handlePm95LocationAbsolute(screenText)) {
            return;
        }'''
s = s.replace(needle, insert, 1)

helpers = '''private boolean handlePm95LocationAbsolute(String screenText) {
        String text = normalize(screenText);

        boolean firstLocation = text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz") || (text.contains("dokladna") && text.contains("przyblizona")));

        boolean finalLocation = text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");

        if (!firstLocation && !finalLocation) return false;

        long now = System.currentTimeMillis();
        if (now - lastPm95LocationTap < 1300) return true;
        lastPm95LocationTap = now;

        if (firstLocation) {
            // Pełny screenshot PM95: środek pierwszego przycisku około 50% szerokości i 60,5% wysokości.
            handler.postDelayed(() -> tapAbsoluteScreenRatio(0.50f, 0.605f), 450);
            handler.postDelayed(() -> tapAbsoluteScreenRatio(0.50f, 0.605f), 1700);
            return true;
        }

        waitingForAlwaysLocation = true;
        // Pełny screenshot PM95: radio Zawsze zezwalaj około 15% szerokości i 52,9% wysokości.
        handler.postDelayed(() -> tapAbsoluteScreenRatio(0.15f, 0.529f), 500);
        // Druga próba w tekst/środek tego samego wiersza.
        handler.postDelayed(() -> tapAbsoluteScreenRatio(0.42f, 0.529f), 1800);
        handler.postDelayed(this::verifyAbsoluteAlwaysLocation, 3000);
        return true;
    }

    private boolean tapAbsoluteScreenRatio(float xRatio, float yRatio) {
        if (!isMode(MODE_OPEN_TMS)) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0) return false;
        boolean clicked = tapAt((int) (width * xRatio), (int) (height * yRatio));
        if (clicked) markClicked();
        return clicked;
    }

    private void verifyAbsoluteAlwaysLocation() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            waitingForAlwaysLocation = false;
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                scheduleRuntimeFlowFinishCheck();
            }, 900);
            return;
        }

        // Ostatnia próba bez zgadywania bounds roota.
        tapAbsoluteScreenRatio(0.15f, 0.529f);
        handler.postDelayed(() -> {
            AccessibilityNodeInfo check = getRootInActiveWindow();
            if (check != null && isAlwaysLocationAlreadyChecked(check)) {
                enablePreciseLocationIfVisible(check);
                waitingForAlwaysLocation = false;
                performGlobalAction(GLOBAL_ACTION_BACK);
                scheduleRuntimeFlowFinishCheck();
            }
        }, 1400);
    }

    '''

if 'private boolean handlePm95LocationAbsolute(' not in s:
    marker = 'private boolean isUninstallConfirmationDialog'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na helpery v73.')
    s = s.replace(marker, helpers + marker, 1)

# Ensure runtime flow cannot finish while final location is pending.
pat = r'private void scheduleRuntimeFlowFinishCheck\(\) \{.*?\n    \}'
m = re.search(pat, s, flags=re.S)
if m:
    body = m.group(0)
    if 'if (waitingForAlwaysLocation) return;' not in body:
        body = body.replace('if (!isMode(MODE_OPEN_TMS)) return;',
                            'if (!isMode(MODE_OPEN_TMS)) return;\n            if (waitingForAlwaysLocation) return;', 1)
        s = s[:m.start()] + body + s[m.end():]

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')

p.write_text(s, encoding='utf-8')
print('OK: v73 używa absolutnych wymiarów ekranu PM95 dla obu kroków lokalizacji')
