#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')

s = p.read_text(encoding='utf-8')

# PM95: aparat działa, ale następny dialog lokalizacji pojawia się zanim minie blokada kliknięć.
# Po kliknięciu aparatu wcześniejsze callbacki zdarzenia trafiają już na nowy ekran,
# lecz canClickNow() je odrzuca. Jeśli Android nie wyśle kolejnego zdarzenia, lokalizacja zostaje.
# Naprawa: osobny retry bieżącego okna po 1300 i 2400 ms oraz większa stabilizacja końcowej lokalizacji.

# Nie wydłużamy całego procesu nadmiernie. 950 ms daje PM95 czas na zmianę systemowego dialogu.
s = re.sub(r'private static final long CLICK_DELAY_MS = \d+;',
           'private static final long CLICK_DELAY_MS = 950;', s, count=1)

# Dodaj retry aktualnego okna niezależny od starego AccessibilityEvent.
if 'private void retryCurrentPermissionWindow()' not in s:
    helper = '''private void retryCurrentPermissionWindow() {
        handler.postDelayed(this::handleCurrentPermissionWindow, 1300);
        handler.postDelayed(this::handleCurrentPermissionWindow, 2400);
    }

    private void handleCurrentPermissionWindow() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String packageName = root.getPackageName() == null
                ? "" : root.getPackageName().toString().toLowerCase();
        String screenText = normalize(collectText(root));

        if (isLegacyPermissionWarningDialog(packageName, screenText)) {
            clickLegacyPermissionConfirm(root);
            return;
        }
        if (isRuntimePermissionDialog(packageName, screenText)) {
            handleRuntimePermissionDialog(root, screenText);
            return;
        }
        if (isTmsLocationPopup(screenText)) {
            clickTmsPermissionInfo(root);
            return;
        }
        if (isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
        }
    }

    '''
    marker = 'private boolean isTmsLocationPopup'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na retryCurrentPermissionWindow()')
    s = s.replace(marker, helper + marker, 1)

# Po każdym udanym natywnym kliknięciu wymuś obsługę kolejnego dialogu po ustabilizowaniu.
pat = r'(private void handleRuntimePermissionDialog\(AccessibilityNodeInfo root, String screenText\) \{.*?if \(clicked\) \{.*?runtimePermissionsClicked\+\+;)(.*?scheduleRuntimeFlowFinishCheck\(\);.*?\n        \}\n    \})'
m = re.search(pat, s, flags=re.S)
if not m:
    raise SystemExit('Nie znaleziono końcówki handleRuntimePermissionDialog()')
if 'retryCurrentPermissionWindow();' not in m.group(0):
    replacement = m.group(1) + '\n            retryCurrentPermissionWindow();' + m.group(2)
    s = s[:m.start()] + replacement + s[m.end():]

# Twarda obsługa pierwszej lokalizacji: tekst, potem środek przycisku zgodnie z pełnym screenem.
new_runtime = '''private void handleRuntimePermissionDialog(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        String text = normalize(screenText);
        boolean camera = text.contains("aparat") || text.contains("camera")
                || text.contains("robienie zdjec") || text.contains("record video");
        boolean location = text.contains("lokalizacja") || text.contains("location")
                || text.contains("dokladna") || text.contains("precise");

        boolean clicked;
        if (camera || location) {
            clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Aparat: pierwszy przycisk ok. 51,5%. Lokalizacja z grafikami: ok. 61,5%.
                    float yRatio = camera ? 0.515f : 0.615f;
                    clicked = tapAt(b.left + b.width() / 2,
                            b.top + (int) (b.height() * yRatio));
                }
            }
        } else {
            clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Zezwól", "Zezwol", "Zezwalaj", "Allow"});
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            runtimePermissionsClicked++;
            retryCurrentPermissionWindow();
            scheduleRuntimeFlowFinishCheck();
        }
    }'''
pat_runtime = r'private void handleRuntimePermissionDialog\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
s = re.sub(pat_runtime, new_runtime, s, flags=re.S, count=1)

# Po kliknięciu ZAKTUALIZUJ USTAWIENIA daj Settings więcej czasu i jawnie ponów obsługę.
pat_popup = r'(private boolean clickTmsPermissionInfo\(AccessibilityNodeInfo root\) \{.*?if \(clicked\) \{.*?lastRuntimePermissionActionTime = System\.currentTimeMillis\(\);)(.*?\n        \}\n        return clicked;\n    \})'
pm = re.search(pat_popup, s, flags=re.S)
if pm and 'retryCurrentPermissionWindow();' not in pm.group(0):
    replacement = pm.group(1) + '\n            retryCurrentPermissionWindow();' + pm.group(2)
    s = s[:pm.start()] + replacement + s[pm.end():]

# Końcowe Zawsze zezwalaj: 1,3 s na narysowanie ekranu i 1,2 s na weryfikację.
# Używamy istniejącego handleLocationScreen, ale wymuszamy ponowne sprawdzenie niezależne od eventu.
pat_loc = r'(private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?if \(clicked\) \{.*?lastRuntimePermissionActionTime = System\.currentTimeMillis\(\);)(.*?\n        \}\n    \})'
lm = re.search(pat_loc, s, flags=re.S)
if lm and 'retryCurrentPermissionWindow();' not in lm.group(0):
    replacement = lm.group(1) + '\n            retryCurrentPermissionWindow();' + lm.group(2)
    s = s[:lm.start()] + replacement + s[lm.end():]

# Flow nie może zakończyć się w czasie przełączania lokalizacji.
pat_finish = r'private void scheduleRuntimeFlowFinishCheck\(\) \{.*?\n    \}'
fm = re.search(pat_finish, s, flags=re.S)
if fm:
    body = fm.group(0)
    if 'if (waitingForAlwaysLocation) return;' not in body:
        body = body.replace('if (!isMode(MODE_OPEN_TMS)) return;',
                            'if (!isMode(MODE_OPEN_TMS)) return;\n            if (waitingForAlwaysLocation) return;')
        s = s[:fm.start()] + body + s[fm.end():]

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')

p.write_text(s, encoding='utf-8')
print('OK: dodano opoznione retry po aparacie, pierwszej lokalizacji i przejsciu do Zawsze zezwalaj')
