#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not MAIN.exists():
    raise SystemExit(f'Nie znaleziono pliku: {MAIN}')
if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

main = MAIN.read_text(encoding='utf-8')
serv = SERV.read_text(encoding='utf-8')

# ============================================================
# MAIN: jeśli można, otwieraj bezpośrednio ekran uprawnień.
# Jeśli Android tego nie obsłuży, fallback do Informacji o aplikacji.
# ============================================================
new_grant = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram uprawnienia TMS. Uprawnienia zostaną nadane automatycznie.", Toast.LENGTH_LONG).show();

        if (openTmsPermissionSettingsDirect()) {
            return;
        }

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private boolean openTmsPermissionSettingsDirect() {
        Intent[] intents = new Intent[] {
                new Intent("android.settings.APP_PERMISSION_SETTINGS"),
                new Intent("android.settings.APPLICATION_PERMISSIONS_SETTINGS"),
                new Intent("android.settings.MANAGE_APP_PERMISSIONS")
        };

        for (Intent intent : intents) {
            try {
                intent.putExtra("android.provider.extra.APP_PACKAGE", detectedTmsPackage);
                intent.putExtra("android.intent.extra.PACKAGE_NAME", detectedTmsPackage);
                intent.putExtra("package", detectedTmsPackage);
                intent.setData(Uri.parse("package:" + detectedTmsPackage));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }'''

main = re.sub(r'private void grantTmsPermissionsThenOpen\(\) \{.*?\n    \}', new_grant, main, flags=re.S, count=1)
# remove duplicate direct method if old patch left it
matches = list(re.finditer(r'private boolean openTmsPermissionSettingsDirect\(\) \{.*?\n    \}', main, flags=re.S))
if len(matches) > 1:
    for m in reversed(matches[1:]):
        main = main[:m.start()] + main[m.end():]

# ============================================================
# SERVICE: nie obsługuj już "Otwieraj domyślnie" jako osobnego ekranu.
# To było źródłem cofania/przeskoków. Jeżeli przypadkiem wejdzie w ten ekran,
# niech użytkownik widzi to podczas testu, zamiast automatycznie wracać w pętli.
# ============================================================
serv = re.sub(
    r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}',
    '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        return false;
    }''',
    serv,
    flags=re.S,
    count=1
)

# Upewnij się, że App Info jest obsługiwane PRZED ewentualnymi innymi ekranami ustawień.
# Jeżeli blok z isDefaultOpenScreen jest przed AppInfo, przenieś AppInfo przed niego.
if 'if (isDefaultOpenScreen(packageName, screenText))' in serv:
    # usuń wcześniejszy blok default-open, bo metoda i tak zwraca false
    serv = re.sub(r'\n\s*if \(isDefaultOpenScreen\(packageName, screenText\)\) \{\s*goBackFromWrongScreen\(\);\s*return;\s*\}\n', '\n', serv, count=1, flags=re.S)

# ============================================================
# SERVICE: klikanie Uprawnienia.
# 1. Szukaj dokładnego tekstu "Uprawnienia".
# 2. Jeśli nie ma, szukaj subtekstu "Brak przyznanych uprawnień".
# 3. Kliknij najmniejszego klikalnego rodzica wiersza.
# 4. Fallback: klik w centrum znalezionego tekstu/subtekstu, bez stałych współrzędnych.
# ============================================================
new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 900) return;
        lastAppInfoTapTime = now;

        if (tapAppInfoPermissionsRow(root)) {
            markClicked();
        }
    }'''
serv = re.sub(r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_click_app_info, serv, flags=re.S, count=1)

new_tap_row = '''private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectExactNodes(root, "uprawnienia", candidates);
        if (candidates.isEmpty()) collectExactNodes(root, "permissions", candidates);
        if (candidates.isEmpty()) collectExactNodes(root, "zezwolenia", candidates);

        // Na PM90/PM95 czasem główny tekst nie jest klikalny osobno, ale subtekst jest widoczny.
        if (candidates.isEmpty()) collectContainsNodes(root, "brak przyznanych uprawnien", candidates);
        if (candidates.isEmpty()) collectContainsNodes(root, "no permissions granted", candidates);

        for (AccessibilityNodeInfo node : candidates) {
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

            return tapAt(textRect.centerX(), textRect.centerY());
        }
        return false;
    }'''
serv = re.sub(r'private boolean tapAppInfoPermissionsRow\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_tap_row, serv, flags=re.S, count=1)

# Add collectContainsNodes helper if missing
if 'private void collectContainsNodes(' not in serv:
    helper = '''private void collectContainsNodes(AccessibilityNodeInfo node, String wantedPart, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && normalize(text.toString()).contains(wantedPart)) out.add(node);
        CharSequence desc = node.getContentDescription();
        if (desc != null && normalize(desc.toString()).contains(wantedPart)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectContainsNodes(node.getChild(i), wantedPart, out);
    }

    '''
    marker = 'private AccessibilityNodeInfo findBestPermissionsRowParent'
    if marker in serv:
        serv = serv.replace(marker, helper + marker, 1)
    else:
        marker = 'private AccessibilityNodeInfo findSmallClickableParent'
        if marker in serv:
            serv = serv.replace(marker, helper + marker, 1)

# Replace row parent helper to allow subtext row but reject default-open row.
new_best_parent = '''private AccessibilityNodeInfo findBestPermissionsRowParent(AccessibilityNodeInfo node, int expectedY) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = null;
        int bestHeight = Integer.MAX_VALUE;

        for (int i = 0; i < 8 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            String text = normalize(collectText(current));

            boolean containsPermissionRow = text.contains("uprawnienia")
                    || text.contains("permissions")
                    || text.contains("zezwolenia")
                    || text.contains("brak przyznanych uprawnien")
                    || text.contains("no permissions granted");

            boolean containsDefaultOpen = text.contains("otwieraj domyslnie")
                    || text.contains("otwieraj domyślnie")
                    || text.contains("open by default")
                    || text.contains("obslugiwane linki")
                    || text.contains("obsługiwane linki")
                    || text.contains("supported links");

            if (!rect.isEmpty()) {
                int height = rect.height();
                boolean yInside = expectedY >= rect.top && expectedY <= rect.bottom;
                boolean rowSized = height >= 36 && height <= 260;

                if (current.isClickable()
                        && current.isEnabled()
                        && yInside
                        && rowSized
                        && containsPermissionRow
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
    }'''
serv = re.sub(r'private AccessibilityNodeInfo findBestPermissionsRowParent\(AccessibilityNodeInfo node, int expectedY\) \{.*?\n    \}', new_best_parent, serv, flags=re.S, count=1)

# sanity
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f'Podejrzane znaki w kodzie: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: poprawiono przechodzenie z Informacje o aplikacji do Uprawnienia i wyłączono obsługę Otwieraj domyślnie')
