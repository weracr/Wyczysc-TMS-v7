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
# MAIN: po instalacji NIE otwieraj już Informacje o aplikacji.
# Najpierw próbuj bezpośrednio ekran Uprawnienia aplikacji.
# ============================================================
new_grant = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram uprawnienia TMS. Uprawnienia zostaną nadane automatycznie.", Toast.LENGTH_LONG).show();

        if (openTmsPermissionSettingsDirect()) {
            return;
        }

        Toast.makeText(this, "Nie udało się otworzyć ekranu uprawnień bezpośrednio. Otwieram szczegóły aplikacji.", Toast.LENGTH_LONG).show();
        openTmsSettings();
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

pat = r'private void grantTmsPermissionsThenOpen\(\) \{.*?\n    \}'
if re.search(pat, main, flags=re.S):
    main = re.sub(pat, new_grant, main, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam grantTmsPermissionsThenOpen() w MainActivity.java')

# usuń duplikaty openTmsPermissionSettingsDirect(), jeśli już powstały
matches = list(re.finditer(r'private boolean openTmsPermissionSettingsDirect\(\) \{.*?\n    \}', main, flags=re.S))
if len(matches) > 1:
    for m in reversed(matches[1:]):
        main = main[:m.start()] + main[m.end():]

# ============================================================
# SERVICE: jeśli musi wymusić ustawienia, też otwieraj bezpośrednio Uprawnienia.
# ============================================================
new_force = '''private void forceOpenTmsSettingsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastForcedSettingsOpenTime < 1800) return;
        lastForcedSettingsOpenTime = now;

        String pkg = resolveTmsPackage(null);
        if (pkg == null) return;

        if (openTmsPermissionSettingsDirect(pkg)) {
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private boolean openTmsPermissionSettingsDirect(String pkg) {
        Intent[] intents = new Intent[] {
                new Intent("android.settings.APP_PERMISSION_SETTINGS"),
                new Intent("android.settings.APPLICATION_PERMISSIONS_SETTINGS"),
                new Intent("android.settings.MANAGE_APP_PERMISSIONS")
        };

        for (Intent intent : intents) {
            try {
                intent.putExtra("android.provider.extra.APP_PACKAGE", pkg);
                intent.putExtra("android.intent.extra.PACKAGE_NAME", pkg);
                intent.putExtra("package", pkg);
                intent.setData(Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            } catch (Exception ignored) {
            }
        }

        return false;
    }'''

pat_force = r'private void forceOpenTmsSettingsIfNeeded\(\) \{.*?\n    \}'
if re.search(pat_force, serv, flags=re.S):
    serv = re.sub(pat_force, new_force, serv, flags=re.S, count=1)
else:
    marker = 'private void finishPermissionFlowWithMessage()'
    if marker in serv:
        serv = serv.replace(marker, new_force + '\n\n    ' + marker, 1)
    else:
        marker = 'private void finishPermissionFlowAndCloseSettings()'
        if marker in serv:
            serv = serv.replace(marker, new_force + '\n\n    ' + marker, 1)
        else:
            raise SystemExit('Nie znalazłam miejsca na forceOpenTmsSettingsIfNeeded()')

matches = list(re.finditer(r'private boolean openTmsPermissionSettingsDirect\(String pkg\) \{.*?\n    \}', serv, flags=re.S))
if len(matches) > 1:
    for m in reversed(matches[1:]):
        serv = serv[:m.start()] + serv[m.end():]

# ============================================================
# SERVICE: nie cofaj z App Info tylko dlatego, że widzi wiersz Otwieraj domyślnie.
# ============================================================
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
if re.search(pat_default, serv, flags=re.S):
    serv = re.sub(pat_default, new_default, serv, flags=re.S, count=1)

# ============================================================
# SERVICE: jeśli jesteśmy w naszej aplikacji i GRANT jest aktywny, wymuś direct permissions.
# U Ciebie ten blok po patchach bywał pusty, więc przywracamy bezpieczne wymuszenie direct permissions.
# ============================================================
old_own = re.search(r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{.*?\n        \}', serv, flags=re.S)
if old_own:
    new_own = '''if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR)) {
                    forceOpenTmsSettingsIfNeeded();
                }
            } else {
                hideAutomationOverlay();
                setFlowMode(MODE_IDLE);
            }
            return;
        }'''
    serv = serv[:old_own.start()] + new_own + serv[old_own.end():]

# sanity
bad = re.findall(r'&gt;|&lt;|<br>|[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f'Podejrzane znaki w kodzie: {bad[:10]}')

MAIN.write_text(main, encoding='utf-8')
SERV.write_text(serv, encoding='utf-8')
print('OK: dodano bezpośrednie otwieranie ekranu Uprawnienia TMS i pominięcie Otwieraj domyślnie')
