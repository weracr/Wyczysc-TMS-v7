#!/usr/bin/env python3
from pathlib import Path

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

old = '''        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            return ScreenAction.point("location_initial", 1500, 528, 1331, false);
        }'''

old_v83 = '''        if ((text.contains("lokalizacji urzadzenia") || text.contains("dostep do lokalizacji"))
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

new = '''        if ((text.contains("lokalizacji urzadzenia") || text.contains("dostep do lokalizacji"))
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            return ScreenAction.locationGesture("location_initial", 1900);
        }'''

if old_v83 in s:
    s = s.replace(old_v83, new, 1)
elif old in s:
    s = s.replace(old, new, 1)
else:
    raise SystemExit('Nie znaleziono bloku location_initial z v82/v83.')

old_exec = '''                boolean clicked;
                if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                    if (!clicked && action.referenceX > 0 && action.referenceY > 0) {
                        clicked = tapReferencePoint(action.referenceX, action.referenceY);
                    }
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }'''

old_exec_v82 = '''                boolean clicked;
                if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }'''

new_exec = '''                boolean clicked;
                if (action.locationGesture) {
                    clicked = tapLocationButtonByVisibleBounds(current);
                    if (!clicked) {
                        clicked = tapReferencePoint(528, 1331);
                    }
                } else if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                    if (!clicked && action.referenceX > 0 && action.referenceY > 0) {
                        clicked = tapReferencePoint(action.referenceX, action.referenceY);
                    }
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }'''

if old_exec in s:
    s = s.replace(old_exec, new_exec, 1)
elif old_exec_v82 in s:
    s = s.replace(old_exec_v82, new_exec, 1)
else:
    raise SystemExit('Nie znaleziono bloku wykonania ScreenAction.')

marker = '    private boolean tapReferencePoint(int referenceX, int referenceY) {'
helper = '''    private boolean tapLocationButtonByVisibleBounds(AccessibilityNodeInfo root) {
        List<String> labels = Arrays.asList(
                "Podczas używania aplikacji",
                "Podczas uzywania aplikacji",
                "Podczas korzystania z aplikacji",
                "While using the app"
        );

        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            String wanted = normalize(label);

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String visible = normalize(getNodeText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    // Ważne: celowo NIE używamy ACTION_CLICK. Na tym oknie PM95
                    // ACTION_CLICK potrafi zwrócić true bez faktycznej zmiany ekranu.
                    return tapAt(bounds.centerX(), bounds.centerY());
                }
            }
        }
        return false;
    }

'''
if 'private boolean tapLocationButtonByVisibleBounds(' not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na helper lokalizacji.')
    s = s.replace(marker, helper + marker, 1)

# Rozszerz ScreenAction o flagę specjalnego gestu.
old_field = '''        final boolean backAfterTap;

        private ScreenAction(String key, long delayMs, List<String> labels,
                             int referenceX, int referenceY, boolean backAfterTap) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
            this.backAfterTap = backAfterTap;
        }'''
new_field = '''        final boolean backAfterTap;
        final boolean locationGesture;

        private ScreenAction(String key, long delayMs, List<String> labels,
                             int referenceX, int referenceY, boolean backAfterTap,
                             boolean locationGesture) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
            this.backAfterTap = backAfterTap;
            this.locationGesture = locationGesture;
        }'''
if old_field not in s:
    raise SystemExit('Nie znaleziono konstruktora ScreenAction z v82.')
s = s.replace(old_field, new_field, 1)

s = s.replace('return new ScreenAction(key, delayMs, labels, 0, 0, false);',
              'return new ScreenAction(key, delayMs, labels, 0, 0, false, false);')
s = s.replace('return new ScreenAction(key, delayMs, labels, referenceX, referenceY, false);',
              'return new ScreenAction(key, delayMs, labels, referenceX, referenceY, false, false);')
s = s.replace('return new ScreenAction(key, delayMs, null, referenceX, referenceY, backAfterTap);',
              'return new ScreenAction(key, delayMs, null, referenceX, referenceY, backAfterTap, false);')

factory_marker = '''        static ScreenAction point(String key, long delayMs,
                                  int referenceX, int referenceY, boolean backAfterTap) {'''
location_factory = '''        static ScreenAction locationGesture(String key, long delayMs) {
            return new ScreenAction(key, delayMs, null, 0, 0, false, true);
        }

'''
if 'static ScreenAction locationGesture(' not in s:
    if factory_marker not in s:
        raise SystemExit('Nie znaleziono fabryki point().')
    s = s.replace(factory_marker, location_factory + factory_marker, 1)

for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')

p.write_text(s, encoding='utf-8')
print('OK: lokalizacja używa gestu w środek rzeczywistych bounds tekstu, bez ACTION_CLICK')
