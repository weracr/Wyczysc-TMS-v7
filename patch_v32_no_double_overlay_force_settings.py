#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / "app/src/main/java/pl/zabka/wyczysctms/MainActivity.java"
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not MAIN.exists():
    raise SystemExit(f"Nie znaleziono pliku: {MAIN}")
if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

main = MAIN.read_text(encoding="utf-8")
serv = SERV.read_text(encoding="utf-8")

# ============================================================
# MainActivity.java
# 1. Usuń drugi, stały ekran blokady w Activity.
#    Zostaje tylko overlay z AccessibilityService nad ekranami systemowymi.
# ============================================================
new_repair_screen = '''private void showRepairInProgressScreen() {
        // Nie pokazujemy tu drugiego pełnoekranowego czarnego widoku.
        // Ciemne przysłonięcie pokazuje PermissionClickerAccessibilityService jako overlay nad ekranami systemowymi.
        // Dzięki temu nie ma dwóch nakładających się komunikatów.
        Toast.makeText(this, "Naprawa TMS w toku. Nie dotykaj ekranu.", Toast.LENGTH_LONG).show();
    }'''

pattern_repair_screen = r'private void showRepairInProgressScreen\(\) \{.*?\n    \}'
if re.search(pattern_repair_screen, main, flags=re.S):
    main = re.sub(pattern_repair_screen, new_repair_screen, main, flags=re.S, count=1)
else:
    raise SystemExit("Nie znalazłam metody showRepairInProgressScreen() w MainActivity.java")

# 2. Wzmocnij otwieranie szczegółów TMS.
new_grant_method = '''private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram ustawienia TMS. Uprawnienia zostaną nadane automatycznie.", Toast.LENGTH_LONG).show();

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

pattern_grant = r'private void grantTmsPermissionsThenOpen\(\) \{.*?\n    \}'
if re.search(pattern_grant, main, flags=re.S):
    main = re.sub(pattern_grant, new_grant_method, main, flags=re.S, count=1)
else:
    raise SystemExit("Nie znalazłam metody grantTmsPermissionsThenOpen() w MainActivity.java")

# ============================================================
# PermissionClickerAccessibilityService.java
# ============================================================

# 3. Dodaj licznik fallbackowego otwierania ustawień.
if "lastForcedSettingsOpenTime" not in serv:
    if "private long lastOpenTmsTime = 0;" in serv:
        serv = serv.replace(
            "private long lastOpenTmsTime = 0;",
            "private long lastOpenTmsTime = 0;\n    private long lastForcedSettingsOpenTime = 0;"
        )
    elif "private long lastBackTime = 0;" in serv:
        serv = serv.replace(
            "private long lastBackTime = 0;",
            "private long lastBackTime = 0;\n    private long lastForcedSettingsOpenTime = 0;"
        )
    else:
        raise SystemExit("Nie znalazłam miejsca na lastForcedSettingsOpenTime")

# 4. Popraw blok własnej aplikacji: jeśli trwa automatyzacja, nie przechodź do IDLE.
#    Jeśli jesteśmy w trybie nadawania uprawnień, a dalej widzimy ekran Wyczyść TMS, wymuś przejście do ustawień.
block_patterns = [
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{\s*if \(isAutomationRunning\(\)\) showAutomationOverlay\(\);\s*else \{\s*hideAutomationOverlay\(\);\s*setFlowMode\(MODE_IDLE\);\s*\}\s*return;\s*\}',
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{\s*setFlowMode\(MODE_IDLE\);\s*hideAutomationOverlay\(\);\s*return;\s*\}',
    r'if \(isOwnAppOrAdminPanel\(packageName, screenText\)\) \{\s*setFlowMode\(MODE_IDLE\);\s*return;\s*\}',
]
new_own_block = '''if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();

                // Jeżeli tryb nadawania uprawnień jest aktywny, a nadal widzimy ekran Wyczyść TMS,
                // wymuś otwarcie szczegółów TMS w ustawieniach.
                if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {
                    forceOpenTmsSettingsIfNeeded();
                }
            } else {
                hideAutomationOverlay();
                setFlowMode(MODE_IDLE);
            }
            return;
        }'''
changed = False
for pat in block_patterns:
    if re.search(pat, serv, flags=re.S):
        serv = re.sub(pat, new_own_block, serv, flags=re.S, count=1)
        changed = True
        break
if not changed:
    raise SystemExit("Nie znalazłam bloku isOwnAppOrAdminPanel(...) do podmiany")

# 5. Dodaj metodę forceOpenTmsSettingsIfNeeded.
if "private void forceOpenTmsSettingsIfNeeded()" not in serv:
    method = '''private void forceOpenTmsSettingsIfNeeded() {
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
    }

    '''
    if "private void openTmsAppAndFinishPermissionFlow()" in serv:
        serv = serv.replace("private void openTmsAppAndFinishPermissionFlow()", method + "private void openTmsAppAndFinishPermissionFlow()", 1)
    elif "private void openTmsApp()" in serv:
        serv = serv.replace("private void openTmsApp()", method + "private void openTmsApp()", 1)
    else:
        raise SystemExit("Nie znalazłam miejsca na forceOpenTmsSettingsIfNeeded()")

# 6. Jeśli overlay kończy się tylko przy OPEN/GRANT, dodaj FULL_REPAIR.
serv = serv.replace(
    "if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS)) {",
    "if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS) || isMode(MODE_FULL_REPAIR)) {"
)
serv = serv.replace(
    "if (isMode(MODE_OPEN_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS)) {",
    "if (isMode(MODE_OPEN_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR)) {"
)

# 7. sanity check
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', main + serv)
if bad:
    raise SystemExit(f"Podejrzane znaki '*': {bad[:10]}")

MAIN.write_text(main, encoding="utf-8")
SERV.write_text(serv, encoding="utf-8")
print("OK: usunięto podwójną blokadę i dodano fallback wymuszający wejście w ustawienia TMS")
