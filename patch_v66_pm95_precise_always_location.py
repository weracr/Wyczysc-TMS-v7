#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')
s = p.read_text(encoding='utf-8')

# PM95 1024x2048 ze screena:
# radio "Zawsze zezwalaj" ~ x=95, y=1055
# tekst/wiersz ~ x=360, y=1055
# czyli ok. x=9.3% lub 35%, y=51.5% pelnego aktywnego okna.

new_handle = '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            finishAlwaysLocationAndReturnToTms();
            return;
        }

        boolean clicked = clickLocationOptionRow(root, "Zawsze zezwalaj")
                || clickLocationOptionRow(root, "Zezwalaj cały czas")
                || clickLocationOptionRow(root, "Zezwalaj caly czas")
                || clickLocationOptionRow(root, "Allow all the time")
                || clickLocationOptionRow(root, "Always allow");

        if (!clicked) {
            clicked = tapPm95AlwaysAllowCoordinates(root, false);
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            verifyAlwaysLocationThenReturn();
        }
    }'''
pat = r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono handleLocationScreen()')
s = re.sub(pat, new_handle, s, flags=re.S, count=1)

helpers = '''private boolean clickLocationOptionRow(AccessibilityNodeInfo root, String optionText) {
        if (root == null || optionText == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(optionText);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(optionText);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo current = node;
            for (int i = 0; i < 6 && current != null; i++) {
                Rect rect = new Rect();
                current.getBoundsInScreen(rect);
                if (!rect.isEmpty() && current.isEnabled() && current.isClickable()
                        && rect.height() >= 45 && rect.height() <= 260) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
                current = current.getParent();
            }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapPm95AlwaysAllowCoordinates(AccessibilityNodeInfo root, boolean radioOnly) {
        if (root == null) return false;
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        float xRatio = radioOnly ? 0.093f : 0.35f;
        int x = bounds.left + (int) (bounds.width() * xRatio);
        int y = bounds.top + (int) (bounds.height() * 0.515f);
        return tapAt(x, y);
    }

    private void verifyAlwaysLocationThenReturn() {
        handler.postDelayed(() -> {
            AccessibilityNodeInfo firstCheck = getRootInActiveWindow();
            if (firstCheck == null) return;

            if (isAlwaysLocationAlreadyChecked(firstCheck)) {
                enablePreciseLocationIfVisible(firstCheck);
                finishAlwaysLocationAndReturnToTms();
                return;
            }

            // Druga próba dokładnie w radio po lewej stronie wiersza.
            if (tapPm95AlwaysAllowCoordinates(firstCheck, true)) {
                markClicked();
            }

            handler.postDelayed(() -> {
                AccessibilityNodeInfo secondCheck = getRootInActiveWindow();
                if (secondCheck != null && isAlwaysLocationAlreadyChecked(secondCheck)) {
                    enablePreciseLocationIfVisible(secondCheck);
                    finishAlwaysLocationAndReturnToTms();
                }
            }, 900);
        }, 900);
    }

    '''

# Remove previous copies if reapplying.
s = re.sub(r'private boolean clickLocationOptionRow\(AccessibilityNodeInfo root, String optionText\) \{.*?\n    \}\n\n    private boolean tapPm95AlwaysAllowCoordinates\(AccessibilityNodeInfo root, boolean radioOnly\) \{.*?\n    \}\n\n    private void verifyAlwaysLocationThenReturn\(\) \{.*?\n    \}\n\n    ', '', s, flags=re.S)
marker = 'private boolean isNotificationPermissionScreen'
if marker not in s:
    raise SystemExit('Nie znaleziono miejsca na helpery lokalizacji')
s = s.replace(marker, helpers + marker, 1)

new_finish = '''private void finishAlwaysLocationAndReturnToTms() {
        if (!waitingForAlwaysLocation) return;
        waitingForAlwaysLocation = false;

        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            scheduleRuntimeFlowFinishCheck();
        }, 650);
    }'''
pat2 = r'private void finishAlwaysLocationAndReturnToTms\(\) \{.*?\n    \}'
if re.search(pat2, s, flags=re.S):
    s = re.sub(pat2, new_finish, s, flags=re.S, count=1)
else:
    s = s.replace(marker, new_finish + '\n\n    ' + marker, 1)

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostal HTML: {token}')

p.write_text(s, encoding='utf-8')
print('OK: PM95 wybiera Zawsze zezwalaj na pozycji 51.5%, weryfikuje i wraca do TMS')
