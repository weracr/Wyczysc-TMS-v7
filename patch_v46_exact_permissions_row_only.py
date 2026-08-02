#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# Ten patch usuwa błędne fallbacki współrzędnych, które mogły trafiać w "Otwieraj domyślnie".
# Klikamy WYŁĄCZNIE dokładny wiersz "Uprawnienia" i odrzucamy każdy rodzic/węzeł,
# którego tekst zawiera "Otwieraj domyślnie" lub "Open by default".

new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 1000) return;
        lastAppInfoTapTime = now;

        if (tapAppInfoPermissionsRow(root)) {
            markClicked();
        }
    }'''
pat_click = r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat_click, s, flags=re.S):
    s = re.sub(pat_click, new_click_app_info, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody clickAppInfoPermissions().')

new_tap_row = '''private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;

        List<AccessibilityNodeInfo> exactNodes = new ArrayList<>();
        collectExactNodes(root, "uprawnienia", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "permissions", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "zezwolenia", exactNodes);

        for (AccessibilityNodeInfo node : exactNodes) {
            if (node == null) continue;

            Rect textRect = new Rect();
            node.getBoundsInScreen(textRect);
            if (textRect.isEmpty()) continue;

            AccessibilityNodeInfo row = findBestPermissionsRowParent(node, textRect.centerY());
            if (row != null) {
                Rect rowRect = new Rect();
                row.getBoundsInScreen(rowRect);
                if (!rowRect.isEmpty()) {
                    return tapAt(rowRect.centerX(), rowRect.centerY());
                }
            }

            // Ostateczny fallback: tylko dokładny tekst "Uprawnienia".
            // Nie używamy już stałej pozycji ekranu, bo trafiała w "Otwieraj domyślnie".
            return tapAt(textRect.centerX(), textRect.centerY());
        }

        return false;
    }'''
pat_row = r'private boolean tapAppInfoPermissionsRow\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat_row, s, flags=re.S):
    s = re.sub(pat_row, new_tap_row, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody tapAppInfoPermissionsRow().')

# Dodaj nowy helper, jeśli go nie ma.
if 'private AccessibilityNodeInfo findBestPermissionsRowParent(' not in s:
    helper = '''private AccessibilityNodeInfo findBestPermissionsRowParent(AccessibilityNodeInfo node, int expectedY) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = null;
        int bestHeight = Integer.MAX_VALUE;

        for (int i = 0; i < 8 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            String text = normalize(collectText(current));

            boolean containsPermissions = text.contains("uprawnienia")
                    || text.contains("permissions")
                    || text.contains("zezwolenia");

            boolean containsDefaultOpen = text.contains("otwieraj domyslnie")
                    || text.contains("otwieraj domyślnie")
                    || text.contains("open by default")
                    || text.contains("obslugiwane linki")
                    || text.contains("obsługiwane linki")
                    || text.contains("supported links");

            if (!rect.isEmpty()) {
                int height = rect.height();
                boolean yInside = expectedY >= rect.top && expectedY <= rect.bottom;
                boolean rowSized = height >= 36 && height <= 220;

                if (current.isClickable()
                        && current.isEnabled()
                        && yInside
                        && rowSized
                        && containsPermissions
                        && !containsDefaultOpen) {
                    if (height < bestHeight) {
                        best = current;
                        bestHeight = height;
                    }
                }
            }
            current = current.getParent();
        }

        return best;
    }

    '''
    marker = 'private AccessibilityNodeInfo findSmallClickableParent'
    if marker in s:
        s = s.replace(marker, helper + marker, 1)
    else:
        marker = 'private boolean isAppPermissionsListScreen'
        if marker not in s:
            raise SystemExit('Nie znalazłam miejsca na findBestPermissionsRowParent().')
        s = s.replace(marker, helper + marker, 1)

# Wzmocnij isDefaultOpenScreen: ekran App Info zawiera wiersz "Otwieraj domyślnie", ale nie wolno go traktować jako osobny ekran.
new_default = '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        String text = normalize(screenText);

        boolean settingsScreen = packageName.contains("settings");
        boolean hasDefaultOpenText = text.contains("otwieraj domyslnie")
                || text.contains("otwieraj domyślnie")
                || text.contains("open by default")
                || text.contains("obslugiwane linki")
                || text.contains("obsługiwane linki")
                || text.contains("supported links");

        boolean appInfoStillVisible = text.contains("informacje o aplikacji")
                || text.contains("o aplikacji")
                || text.contains("app info")
                || text.contains("brak przyznanych uprawnien")
                || text.contains("uprawnienia")
                || text.contains("permissions");

        return settingsScreen && containsTmsText(text) && hasDefaultOpenText && !appInfoStillVisible;
    }'''
pat_default = r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}'
if re.search(pat_default, s, flags=re.S):
    s = re.sub(pat_default, new_default, s, flags=re.S, count=1)

# sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: klik Uprawnienia ograniczony tylko do dokładnego wiersza, bez fallbacku w Otwieraj domyślnie')
