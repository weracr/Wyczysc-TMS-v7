#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

s = SERV.read_text(encoding="utf-8")

# 1. Upewnij się, że lista przycisków instalatora ma Gotowe/Otwórz jako priorytet.
patterns = [
    r'private final List<String> installerButtons = Arrays\.asList\((.*?)\);',
]
new_list = '''private final List<String> installerButtons = Arrays.asList(
            "Gotowe", "Done", "Otwórz", "Otworz", "Open",
            "Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj",
            "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue",
            "Zainstaluj mimo to", "Install anyway", "OK", "Ok"
    );'''
for pat in patterns:
    if re.search(pat, s, flags=re.S):
        s = re.sub(pat, new_list, s, flags=re.S, count=1)
        break

# 2. Wymuś obsługę installer/uninstall PRZED ignorowaniem własnej aplikacji.
# Dzięki temu dialog "Aplikacja została zainstalowana" nie zostanie pominięty tylko dlatego,
# że w tle widać ekran "Naprawa TMS w toku".
priority_marker = 'String screenText = normalize(collectText(root) + " " + collectEventText(event));'
priority_block = '''String screenText = normalize(collectText(root) + " " + collectEventText(event));

        // Priorytet: systemowe okna odinstalowania/instalacji obsługujemy zanim sprawdzimy własną aplikację,
        // bo dialog instalatora może leżeć nad ekranem "Naprawa TMS w toku".
        if (canHandleUninstall() && isUninstallConfirmationDialog(packageName, screenText)) {
            clickUninstallConfirmation(root);
            return;
        }

        if (canHandleInstall() && isInstallerScreen(packageName, screenText)) {
            clickInstallerButtons(root);
            return;
        }'''
if priority_marker in s and 'systemowe okna odinstalowania/instalacji obsługujemy zanim sprawdzimy własną aplikację' not in s:
    s = s.replace(priority_marker, priority_block, 1)

# 3. Jeśli własna aplikacja jest widoczna podczas aktywnego flow, nie ustawiaj IDLE.
# Naprawia sytuację, gdzie ekran "Naprawa TMS w toku" kasował tryb FULL_REPAIR.
s = re.sub(
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{\s*setFlowMode\(MODE_IDLE\);\s*return;\s*\}',
    '''if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                return;
            }
            setFlowMode(MODE_IDLE);
            hideAutomationOverlay();
            return;
        }''',
    s,
    flags=re.S
)
s = re.sub(
    r'if \(isOwnAppScreen\(packageName, screenText\)\) \{\s*return;\s*\}',
    '''if (isOwnAppScreen(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                return;
            }
            return;
        }''',
    s,
    flags=re.S
)

# 4. Dodaj isAutomationRunning, jeśli jeszcze nie ma.
if 'private boolean isAutomationRunning()' not in s:
    method = '''private boolean isAutomationRunning() {
        return isMode(MODE_FULL_REPAIR)
                || isMode(MODE_REPAIR_TMS)
                || isMode(MODE_UNINSTALL_TMS)
                || isMode(MODE_INSTALL_TMS)
                || isMode(MODE_GRANT_TMS_PERMISSIONS)
                || isMode(MODE_OPEN_TMS);
    }

    '''
    if 'private String getFlowMode()' in s:
        s = s.replace('private String getFlowMode()', method + 'private String getFlowMode()', 1)
    else:
        s = s.replace('private String normalize', method + 'private String normalize', 1)

# 5. Wzmocnij rozpoznawanie okna instalatora, zwłaszcza "Aplikacja została zainstalowana".
new_installer_method = '''private boolean isInstallerScreen(String packageName, String screenText) {
        boolean installerPackage = packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller")
                || packageName.contains("files")
                || packageName.contains("documentsui")
                || packageName.contains("package");

        boolean installCompletion = screenText.contains("aplikacja zostala zainstalowana")
                || screenText.contains("aplikacja została zainstalowana")
                || screenText.contains("app installed")
                || screenText.contains("application installed")
                || screenText.contains("zostala zainstalowana")
                || screenText.contains("została zainstalowana");

        boolean installAction = screenText.contains("zainstaluj")
                || screenText.contains("instaluj")
                || screenText.contains("aktualizuj")
                || screenText.contains("install")
                || screenText.contains("update")
                || screenText.contains("dalej")
                || screenText.contains("next")
                || screenText.contains("kontynuuj")
                || screenText.contains("continue")
                || screenText.contains("gotowe")
                || screenText.contains("done")
                || screenText.contains("otworz")
                || screenText.contains("otwórz")
                || screenText.contains("open");

        boolean danger = screenText.contains("odinstaluj")
                || screenText.contains("uninstall")
                || screenText.contains("dezaktywuj")
                || screenText.contains("clear data")
                || screenText.contains("wyczysc dane")
                || screenText.contains("wyczyść dane");

        return (installerPackage || installCompletion) && installAction && !danger;
    }'''
pat = r'private boolean isInstallerScreen\(String packageName, String screenText\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_installer_method, s, flags=re.S, count=1)

# 6. Jeśli metoda nazywa się inaczej w starszej wersji, spróbuj też podmienić isInstallerOrPackageScreen.
pat2 = r'private boolean isInstallerOrPackageScreen\(String packageName, String screenText\) \{.*?\n    \}'
if re.search(pat2, s, flags=re.S):
    s = re.sub(pat2, new_installer_method.replace('isInstallerScreen', 'isInstallerOrPackageScreen'), s, flags=re.S, count=1)

# 7. sanity check
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f"Podejrzane znaki '*': {bad[:10]}")

SERV.write_text(s, encoding="utf-8")
print("OK: poprawiono autoklik okna instalatora i utrzymanie flow nad ekranem naprawy")
