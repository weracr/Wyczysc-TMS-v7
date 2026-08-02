#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# v71: nie polega na wykryciu tekstu dwóch ekranów lokalizacji.
# Skoro aparat działa, po kliknięciu aparatu planujemy bezpośrednio klik pierwszej lokalizacji.
# Skoro przycisk TMS "ZAKTUALIZUJ USTAWIENIA" działa, po nim planujemy klik "Zawsze zezwalaj".

if 'private boolean initialLocationSequenceScheduled' not in s:
    anchor = 'private boolean finalToastShown = false;'
    s = s.replace(anchor, anchor + '''
    private boolean initialLocationSequenceScheduled = false;
    private boolean alwaysLocationSequenceScheduled = false;''')

# Helpery do twardych kliknięć po czasie.
helpers = '''private void schedulePm95InitialLocationAfterCamera() {
        if (initialLocationSequenceScheduled) return;
        initialLocationSequenceScheduled = true;

        // Lokalizacja pojawia się po zamknięciu dialogu aparatu.
        handler.postDelayed(() -> tapCurrentWindowRatio(0.50f, 0.615f), 1700);
        handler.postDelayed(() -> tapCurrentWindowRatio(0.50f, 0.615f), 3000);
    }

    private void schedulePm95AlwaysLocationAfterUpdateSettings() {
        if (alwaysLocationSequenceScheduled) return;
        alwaysLocationSequenceScheduled = true;
        waitingForAlwaysLocation = true;

        // Ustawienia potrzebują czasu na narysowanie ekranu Lokalizacja - dostęp.
        handler.postDelayed(() -> tapCurrentWindowRatio(0.35f, 0.515f), 2100);
        handler.postDelayed(() -> tapCurrentWindowRatio(0.093f, 0.515f), 3400);
        handler.postDelayed(this::verifyPm95AlwaysLocationAndReturn, 4700);
    }

    private boolean tapCurrentWindowRatio(float xRatio, float yRatio) {
        if (!isMode(MODE_OPEN_TMS)) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        Rect b = new Rect();
        root.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        boolean result = tapAt(b.left + (int) (b.width() * xRatio),
                b.top + (int) (b.height() * yRatio));
        if (result) markClicked();
        return result;
    }

    private void verifyPm95AlwaysLocationAndReturn() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String text = normalize(collectText(root));
        boolean stillOnLocationSettings = text.contains("lokalizacja - dostep")
                || text.contains("zawsze zezwalaj")
                || text.contains("zezwalaj tylko podczas uzywania aplikacji");

        if (stillOnLocationSettings && !isAlwaysLocationAlreadyChecked(root)) {
            tapCurrentWindowRatio(0.093f, 0.515f);
            handler.postDelayed(this::verifyPm95AlwaysLocationAndReturn, 1300);
            return;
        }

        enablePreciseLocationIfVisible(root);
        waitingForAlwaysLocation = false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastRuntimePermissionActionTime = System.currentTimeMillis();
        scheduleRuntimeFlowFinishCheck();
    }

    '''
if 'private void schedulePm95InitialLocationAfterCamera()' not in s:
    marker = 'private boolean isTmsLocationPopup'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na helpery v71')
    s = s.replace(marker, helpers + marker, 1)

# Po skutecznym kliknięciu aparatu uruchom sekwencję lokalizacji.
pat = r'private void handleRuntimePermissionDialog\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
m = re.search(pat, s, flags=re.S)
if not m:
    raise SystemExit('Nie znaleziono handleRuntimePermissionDialog()')
body = m.group(0)

# Dodaj rozpoznanie aparatu po tekście, jeśli obecna metoda ma zmienną camera.
if 'schedulePm95InitialLocationAfterCamera();' not in body:
    target = 'runtimePermissionsClicked++;'
    if target not in body:
        raise SystemExit('Nie znaleziono miejsca po runtimePermissionsClicked++')
    body = body.replace(target,
        target + '''
            if (camera) {
                schedulePm95InitialLocationAfterCamera();
            }''', 1)
    s = s[:m.start()] + body + s[m.end():]

# Po skutecznym kliknięciu ZAKTUALIZUJ USTAWIENIA uruchom końcową sekwencję.
pat2 = r'private boolean clickTmsPermissionInfo\(AccessibilityNodeInfo root\) \{.*?\n    \}'
m2 = re.search(pat2, s, flags=re.S)
if not m2:
    raise SystemExit('Nie znaleziono clickTmsPermissionInfo()')
body2 = m2.group(0)
if 'schedulePm95AlwaysLocationAfterUpdateSettings();' not in body2:
    target2 = 'lastRuntimePermissionActionTime = System.currentTimeMillis();'
    if target2 not in body2:
        raise SystemExit('Nie znaleziono miejsca po kliknięciu Update Settings')
    body2 = body2.replace(target2,
        target2 + '\n            schedulePm95AlwaysLocationAfterUpdateSettings();', 1)
    s = s[:m2.start()] + body2 + s[m2.end():]

# Reset sekwencji na starcie flow.
pat3 = r'private void setFlowMode\(String mode\) \{.*?\n    \}'
m3 = re.search(pat3, s, flags=re.S)
if not m3:
    raise SystemExit('Nie znaleziono setFlowMode()')
body3 = m3.group(0)
if 'initialLocationSequenceScheduled = false;' not in body3:
    needle = 'if (MODE_OPEN_TMS.equals(mode)) {'
    if needle in body3:
        body3 = body3.replace(needle, needle + '''
            initialLocationSequenceScheduled = false;
            alwaysLocationSequenceScheduled = false;''', 1)
    s = s[:m3.start()] + body3 + s[m3.end():]

# Końcowy timeout nie może przerwać Always Location.
pat4 = r'private void scheduleRuntimeFlowFinishCheck\(\) \{.*?\n    \}'
m4 = re.search(pat4, s, flags=re.S)
if m4:
    body4 = m4.group(0)
    if 'if (waitingForAlwaysLocation) return;' not in body4:
        body4 = body4.replace('if (!isMode(MODE_OPEN_TMS)) return;',
            'if (!isMode(MODE_OPEN_TMS)) return;\n            if (waitingForAlwaysLocation) return;', 1)
        s = s[:m4.start()] + body4 + s[m4.end():]

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostal HTML: {token}')

p.write_text(s, encoding='utf-8')
print('OK: v71 po aparacie wymusza pierwsza lokalizacje, a po Update Settings wymusza Zawsze zezwalaj i BACK')
