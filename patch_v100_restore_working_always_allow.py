#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')

s = p.read_text(encoding='utf-8')

# Wymagamy wersji v97/v98/v99 z ręcznym pierwszym ekranem lokalizacji.
if 'isInitialLocationDialog' not in s or 'isAlwaysLocationScreen' not in s:
    raise SystemExit('Ten skrypt wymaga serwisu v97-v99.')

# Dodaj stan końcowej lokalizacji.
field_candidates = [
    '    private boolean clickPending;',
    '    private String pendingKey = "";'
]
anchor = next((x for x in field_candidates if x in s), None)
if not anchor:
    raise SystemExit('Nie znaleziono pól sterujących serwisu.')
if 'private boolean alwaysAllowPending' not in s:
    s = s.replace(anchor, anchor + '\n    private boolean alwaysAllowPending = false;', 1)

# Końcowa lokalizacja ma być automatyczna, bez ręcznej instrukcji.
old_variants = [
'''        if (isAlwaysLocationScreen(text)) {
            showInstruction(root, "Nadaj uprawnienie do lokalizacji\n\nWybierz: ZAWSZE ZEZWALAJ\ni naciśnij WSTECZ", false);
            return;
        }''',
'''        if (isAlwaysLocationScreen(text)) {
            showInstruction("Nadaj uprawnienie do lokalizacji\n\nWybierz: ZAWSZE ZEZWALAJ\ni naciśnij WSTECZ");
            return;
        }'''
]
new_block = '''        if (isAlwaysLocationScreen(text)) {
            hideInstruction();
            clickAlwaysAllowAndReturn(root);
            return;
        }'''
for old in old_variants:
    if old in s:
        s = s.replace(old, new_block, 1)
        break
else:
    # Elastyczna podmiana całego warunku.
    pat = r'        if \(isAlwaysLocationScreen\(text\)\) \{.*?\n        \}'
    if not re.search(pat, s, flags=re.S):
        raise SystemExit('Nie znaleziono obsługi końcowego ekranu lokalizacji.')
    s = re.sub(pat, new_block, s, count=1, flags=re.S)

helper = '''    private void clickAlwaysAllowAndReturn(AccessibilityNodeInfo root) {
        if (alwaysAllowPending) return;
        alwaysAllowPending = true;

        handler.postDelayed(() -> {
            AccessibilityNodeInfo current = getRootInActiveWindow();
            if (current == null) {
                alwaysAllowPending = false;
                return;
            }

            boolean clicked = clickVisibleText(current, Arrays.asList(
                    "Zawsze zezwalaj",
                    "Allow all the time",
                    "Always allow"
            ));

            if (!clicked) {
                Rect target = findBoundsByText(current, Arrays.asList(
                        "Zawsze zezwalaj",
                        "Allow all the time",
                        "Always allow"
                ));
                if (target != null && !target.isEmpty()) {
                    clicked = tapGesture(target.centerX(), target.centerY());
                }
            }

            if (!clicked) {
                // Sprawdzony punkt z działającej gałęzi v82-v89 na PM95.
                clicked = tapGesture(112, 1145);
            }

            if (clicked) {
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    alwaysAllowPending = false;
                    Toast.makeText(this,
                            "Lokalizacja ustawiona. Wracam do TMS.",
                            Toast.LENGTH_SHORT).show();
                }, 1400);
            } else {
                alwaysAllowPending = false;
            }
        }, 1500);
    }

    private Rect findBoundsByText(AccessibilityNodeInfo root, List<String> labels) {
        if (root == null) return null;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) return bounds;
            }
        }
        return null;
    }

    private boolean tapGesture(int x, int y) {
        if (x <= 0 || y <= 0) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 80, 180);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();
        return dispatchGesture(gesture, null, null);
    }

'''
marker_candidates = [
    '    private boolean isUninstallDialog(',
    '    private boolean isAlwaysLocationScreen(',
    '    private String nodeText('
]
marker = next((x for x in marker_candidates if x in s), None)
if not marker:
    raise SystemExit('Nie znaleziono miejsca na metody końcowej lokalizacji.')
if 'private void clickAlwaysAllowAndReturn(' not in s:
    s = s.replace(marker, helper + marker, 1)

for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')
if s.count('private void clickAlwaysAllowAndReturn(') != 1:
    raise SystemExit('Nieprawidłowa liczba metod clickAlwaysAllowAndReturn().')

p.write_text(s, encoding='utf-8')
print('OK: pierwsza lokalizacja pozostaje ręczna, a Zawsze zezwalaj klika się automatycznie i cofa do TMS')
