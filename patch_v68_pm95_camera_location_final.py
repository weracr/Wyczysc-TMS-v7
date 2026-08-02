#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# PM95: poprawiamy tylko 3 niedziałające kroki:
# 1) aparat -> Podczas używania aplikacji
# 2) pierwsza lokalizacja -> Podczas używania aplikacji
# 3) Lokalizacja - dostęp -> Zawsze zezwalaj -> BACK do TMS

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

            // Fallback tylko dla PM95. Aparat ma pierwszy przycisk wyżej, lokalizacja niżej.
            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    float yRatio = camera ? 0.52f : 0.62f;
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
            scheduleRuntimeFlowFinishCheck();
        }
    }'''
pat = r'private void handleRuntimePermissionDialog\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono handleRuntimePermissionDialog()')
s = re.sub(pat, new_runtime, s, flags=re.S, count=1)

if 'private boolean tapRuntimeChoiceExact(' not in s:
    helper = '''private boolean tapRuntimeChoiceExact(AccessibilityNodeInfo root, String[] labels) {
        if (root == null || labels == null) return false;

        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            String wanted = normalize(label);

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                String visible = normalize(getNodeVisibleText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                // Najpierw natywny ACTION_CLICK na najmniejszym klikalnym rodzicu.
                AccessibilityNodeInfo current = node;
                for (int i = 0; i < 5 && current != null; i++) {
                    Rect r = new Rect();
                    current.getBoundsInScreen(r);
                    if (current.isEnabled() && current.isClickable() && !r.isEmpty()
                            && r.height() >= 45 && r.height() <= 350
                            && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                    current = current.getParent();
                }

                // PM95: gesture dokładnie w środek widocznego napisu/przycisku.
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty() && tapAt(r.centerX(), r.centerY())) return true;
            }
        }
        return false;
    }

    '''
    marker = 'private boolean isTmsLocationPopup'
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na tapRuntimeChoiceExact()')
    s = s.replace(marker, helper + marker, 1)

# Ekran systemowy lokalizacji obsługujemy zawsze w MODE_OPEN_TMS, nie tylko gdy flaga przetrwała.
new_is_location = '''private boolean isLocationPermissionScreen(String packageName, String screenText) {
        String text = normalize(screenText);
        return packageName.contains("settings")
                && (text.contains("lokalizacja - dostep")
                || text.contains("location access")
                || text.contains("zawsze zezwalaj")
                || text.contains("allow all the time"));
    }'''
s = re.sub(r'private boolean isLocationPermissionScreen\(String packageName, String screenText\) \{.*?\n    \}', new_is_location, s, flags=re.S, count=1)

new_location = '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        boolean clicked = tapRuntimeChoiceExact(root,
                new String[]{"Zawsze zezwalaj", "Zezwalaj cały czas", "Zezwalaj caly czas",
                        "Allow all the time", "Always allow"});

        if (!clicked) {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            if (!b.isEmpty()) {
                // Pełny screenshot PM95 1024x2048: radio Zawsze zezwalaj ~ 9,3% x i 51,5% y.
                clicked = tapAt(b.left + (int) (b.width() * 0.093f),
                        b.top + (int) (b.height() * 0.515f));
            }
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();

            // Druga kontrolowana próba w środek tekstu/wiersza, po czym powrót do TMS.
            handler.postDelayed(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current != null && !isAlwaysLocationAlreadyChecked(current)) {
                    Rect b = new Rect();
                    current.getBoundsInScreen(b);
                    if (!b.isEmpty()) {
                        tapAt(b.left + (int) (b.width() * 0.35f),
                                b.top + (int) (b.height() * 0.515f));
                    }
                }

                handler.postDelayed(() -> {
                    AccessibilityNodeInfo verified = getRootInActiveWindow();
                    if (verified != null) enablePreciseLocationIfVisible(verified);
                    waitingForAlwaysLocation = false;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastRuntimePermissionActionTime = System.currentTimeMillis();
                    scheduleRuntimeFlowFinishCheck();
                }, 1000);
            }, 900);
        }
    }'''
pat = r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono handleLocationScreen()')
s = re.sub(pat, new_location, s, flags=re.S, count=1)

# Handler lokalizacji nie może zależeć od waitingForAlwaysLocation.
s = s.replace('if (waitingForAlwaysLocation && isLocationPermissionScreen(packageName, screenText)) {',
              'if (isMode(MODE_OPEN_TMS) && isLocationPermissionScreen(packageName, screenText)) {')

# Bezpieczny odstęp 850 ms, aby pierwszy ekran nie został kliknięty zanim się ustabilizuje.
s = s.replace('private static final long CLICK_DELAY_MS = 700;', 'private static final long CLICK_DELAY_MS = 850;')

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')

p.write_text(s, encoding='utf-8')
print('OK: poprawiono aparat, pierwsza lokalizacje i Zawsze zezwalaj z powrotem do TMS')
