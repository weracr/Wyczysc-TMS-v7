#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono: {p}')
s = p.read_text(encoding='utf-8')

# Pierwsza lokalizacja: kilka osobnych TAPOW w bezpiecznym obszarze przycisku.
# To NIE jest przesuwanie. Kolejne dotkniecie wykonuje sie tylko, jesli nadal
# widoczne jest to samo okno lokalizacji.

# Ujednolic akcje location_initial.
patterns = [
    r'return ScreenAction\.point\("location_initial",\s*\d+,\s*\d+,\s*\d+,\s*false\);',
    r'return ScreenAction\.locationGesture\("location_initial",\s*\d+\);',
    r'return ScreenAction\.textWithFallback\(\s*"location_initial".*?\);'
]
for pat in patterns:
    if re.search(pat, s, flags=re.S):
        s = re.sub(pat,
                   'return ScreenAction.point("location_initial", 1700, 512, 1248, false);',
                   s, count=1, flags=re.S)
        break
else:
    raise SystemExit('Nie znaleziono location_initial.')

# W scheduleAction kieruj location_initial do sekwencji wielu tapow.
old_variants = [
'''                boolean clicked;
                if ("location_initial".equals(action.key)) {
                    clicked = clickInitialLocationBySystemId(current);
                    if (!clicked) {
                        clicked = tapReferencePoint(488, 1329);
                    }
                } else if (action.locationGesture) {''',
'''                boolean clicked;
                if (action.locationGesture) {''',
'''                boolean clicked;
                if (action.labels != null) {'''
]

new_prefix = '''                boolean clicked;
                if ("location_initial".equals(action.key)) {
                    clicked = startInitialLocationTapGrid();
                } else if (action.locationGesture) {'''

if old_variants[0] in s:
    s = s.replace(old_variants[0], new_prefix, 1)
elif old_variants[1] in s:
    s = s.replace(old_variants[1], new_prefix, 1)
elif old_variants[2] in s:
    s = s.replace(old_variants[2], '''                boolean clicked;
                if ("location_initial".equals(action.key)) {
                    clicked = startInitialLocationTapGrid();
                } else if (action.labels != null) {''', 1)
else:
    raise SystemExit('Nie znaleziono bloku wykonania ScreenAction.')

helper = '''    private boolean startInitialLocationTapGrid() {
        // Bezpieczne punkty wewnątrz przycisku ustalone ze screenów:
        // bounds około X=120..903, Y=1182..1313.
        final int[][] points = new int[][] {
                {512, 1248},
                {350, 1248},
                {675, 1248},
                {512, 1215},
                {512, 1280}
        };

        for (int i = 0; i < points.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                String text = normalize(collectText(root));

                // Nie klikaj kolejnego punktu, jeżeli okno już zniknęło.
                boolean stillLocation = text.contains("podczas uzywania aplikacji")
                        && text.contains("tylko tym razem")
                        && text.contains("nie zezwalaj")
                        && (text.contains("lokaliz")
                        || (text.contains("dokladna") && text.contains("przyblizona")));
                if (!stillLocation) return;

                tapReferencePoint(points[index][0], points[index][1]);
                markClicked();
            }, index * 700L);
        }
        return true;
    }

'''
marker = '    private boolean tapReferencePoint(int referenceX, int referenceY) {'
if 'private boolean startInitialLocationTapGrid()' not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na tap grid.')
    s = s.replace(marker, helper + marker, 1)

# tapAt nie moze blokowac kolejnych punktow przez globalny guard.
# Guard pozostaje dla standardowych clickow, natomiast grid wywołuje osobna metode.
helper2 = '''    private boolean tapGridPoint(int referenceX, int referenceY) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int x;
        int y;
        if (width >= 1000 && width <= 1050) {
            x = referenceX;
            y = referenceY;
        } else {
            int height = getResources().getDisplayMetrics().heightPixels;
            x = Math.round(width * (referenceX / 1024f));
            y = Math.round(height * (referenceY / 2048f));
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 60, 180);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

'''
if 'private boolean tapGridPoint(' not in s:
    s = s.replace(marker, helper2 + marker, 1)

# W gridzie użyj metody bez guarda.
s = s.replace('tapReferencePoint(points[index][0], points[index][1]);\n                markClicked();',
              'tapGridPoint(points[index][0], points[index][1]);\n                markClicked();', 1)

for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
    if token in s:
        raise SystemExit(f'Pozostal HTML: {token}')
if s.count('{') != s.count('}'):
    raise SystemExit(f'Niezgodne klamry: {s.count("{")} / {s.count("}")}')

p.write_text(s, encoding='utf-8')
print('OK: lokalizacja wykonuje maksymalnie 5 osobnych tapow w obrebie przycisku i zatrzymuje sie po zmianie ekranu')
