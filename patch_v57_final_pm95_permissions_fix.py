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

# v57 finalny fix dla PM90/PM95:
# - nie używa direct intentów do APP_PERMISSION_SETTINGS
# - jeśli telefon trafi w Otwieraj domyślnie, robi BACK
# - gdy jest na Informacje o aplikacji, klika twardo w miejsce wiersza Uprawnienia z przesłanego screena
# - uprawnienia nie uruchamiają się w FULL_REPAIR_FLOW, tylko dopiero w MODE_GRANT_TMS_PERMISSIONS

# MAIN: grantTmsPermissionsThenOpen ma otwierać tylko Informacje o aplikacji.
new_grant = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram informacje o aplikacji TMS. Przejdę do uprawnień automatycznie.", Toast.LENGTH_LONG).show();

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Nie można otworzyć ustawień TMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }'''
main = re.sub(r'private void grantTmsPermissionsThenOpen\(\) \{.*?\n    \}', new_grant, main, flags=re.S, count=1)
main = re.sub(r'\n\s*private boolean openTmsPermissionSettingsDirect\(\) \{.*?\n    \}', '', main, flags=re.S)

# SERVICE: forceOpenTmsSettingsIfNeeded też tylko App Info.
new_force = '''private void forceOpenTmsSettingsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastForcedSettingsOpenTime < 1800) return;
        lastForcedSettingsOpenTime = now;

        String pkg = resolveTmsPackage(null);
        if (pkg == null) return;

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }'''
serv = re.sub(r'private void forceOpenTmsSettingsIfNeeded\(\) \{.*?\n    \}', new_force, serv, flags=re.S, count=1)
serv = re.sub(r'\n\s*private boolean openTmsPermissionSettingsDirect\(String pkg\) \{.*?\n    \}', '', serv, flags=re.S)

# SERVICE: aktywny ekran Otwieraj domyślnie musi robić BACK.
new_default = '''private boolean isDefaultOpenScreen(String packageName, String screenText) {
        String text = normalize(screenText);
        if (!packageName.contains("settings")) return false;
        if (!containsTmsText(text)) return false;

        boolean defaultTitle = text.contains("otwieraj domyslnie")
                || text.contains("otwieraj domyślnie")
                || text.contains("open by default");
        boolean defaultBody = text.contains("otwieraj obslugiwane linki")
                || text.contains("otwieraj obsługiwane linki")
                || text.contains("open supported links")
                || text.contains("linki otwierane w tej aplikacji")
                || text.contains("links opened in this app");
        boolean appInfoTitle = text.contains("informacje o aplikacji")
                || text.contains("o aplikacji")
                || text.contains("app info");

        return defaultTitle && defaultBody && !appInfoTitle;
    }'''
serv = re.sub(r'private boolean isDefaultOpenScreen\(String packageName, String screenText\) \{.*?\n    \}', new_default, serv, flags=re.S, count=1)

# Wstaw obsługę default-open, jeśli jest usunięta.
if 'if (isDefaultOpenScreen(packageName, screenText))' not in serv:
    needle = 'if (isLegacyPermissionWarningDialog(packageName, screenText)) {'
    if needle not in serv:
        needle = 'if (isRuntimePermissionDialog(packageName, screenText)) {'
    block = '''if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        '''
    if needle in serv:
        serv = serv.replace(needle, block + needle, 1)

# SERVICE: App Info -> twardy tap Uprawnienia, potem tekstowe fallbacki.
new_click = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 900) return;
        lastAppInfoTapTime = now;

        if (tapPm95PermissionsRow(root)
                || tapExactVisibleText(root, "Uprawnienia")
                || tapExactVisibleText(root, "Permissions")
                || tapExactVisibleText(root, "Zezwolenia")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnień")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnien")
                || tapContainsVisibleText(root, "No permissions granted")) {
            markClicked();
        }
    }'''
serv = re.sub(r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}', new_click, serv, flags=re.S, count=1)

if 'private boolean tapPm95PermissionsRow(' not in serv:
    hard = '''private boolean tapPm95PermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (rootRect.isEmpty()) return false;

        // Dla układu ze screena PM90/PM95 wiersz Uprawnienia jest około 66% wysokości aktywnego okna.
        int x = rootRect.left + (rootRect.width() / 2);
        int y = rootRect.top + (int) (rootRect.height() * 0.66f);
        return tapAt(x, y);
    }

    '''
    marker = 'private boolean tapExactVisibleText'
    if marker in serv:
        serv = serv.replace(marker, hard + marker, 1)
    else:
        marker = 'private boolean tapAppInfoPermissionsRow'
        if marker in serv:
            serv = serv.replace(marker, hard + marker, 1)
        else:
            marker = 'private boolean isAppPermissionsListScreen'
            if marker in serv:
                serv = serv.replace(marker, hard + marker, 1)

# Add helpers if missing.
if 'private boolean tapExactVisibleText(' not in serv:
    helpers = '''private boolean tapExactVisibleText(AccessibilityNodeInfo root, String wantedText) {
        if (root == null || wantedText == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectExactNodes(root, normalize(wantedText), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapContainsVisibleText(AccessibilityNodeInfo root, String wantedPart) {
        if (root == null || wantedPart == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectContainsNodes(root, normalize(wantedPart), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    '''
    serv = serv.replace('private boolean tapPm95PermissionsRow', helpers + 'private boolean tapPm95PermissionsRow', 1)

if 'private void collectContainsNodes(' not in serv:
    ch = '''private void collectContainsNodes(AccessibilityNodeInfo node, String wantedPart, List<AccessibilityNodeInfo> out) {
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
        serv = serv.replace(marker, ch + marker, 1)
    else:
        marker = 'private boolean isAppPermissionsListScreen'
        if marker in serv:
            serv = serv.replace(marker, ch + marker, 1)

# Uprawnienia nie mogą działać w FULL_REPAIR, dopiero GRANT.
serv = re.sub(r'private boolean canHandleTmsPermissions\(\) \{.*?\n    \}', '''private boolean canHandleTmsPermissions() {
        return isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
    }''', serv, flags=re.S, count=1)

# Force settings only in GRANT.
serv = re.sub(
    r'if \(isMode\(MODE_GRANT_TMS_PERMISSIONS\) \|\| isMode\(MODE_FULL_REPAIR\)\) \{\s*forceOpenTmsSettingsIfNeeded\(\);\s*\}',
    'if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {\n                    forceOpenTmsSettingsIfNeeded();\n                }',
    serv,
    flags=re.S
)

# Sanity.
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f'Podejrzane znaki w kodzie: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: final v57 PM95 - default-open robi BACK, a App Info robi twardy tap w Uprawnienia')
