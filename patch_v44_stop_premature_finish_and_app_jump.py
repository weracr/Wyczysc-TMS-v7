#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# 1. Nie kończ flow tylko dlatego, że chwilowo nie znaleziono sekcji "Nie mają dostępu".
# Kończ dopiero, gdy na pewno jesteśmy na ekranie listy uprawnień i widać sekcję "Mają dostęp" / "Allowed"
# oraz nie ma już sekcji "Nie mają dostępu" / "Not allowed".
new_handle = '''private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        String text = normalize(screenText);

        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)) {
                if (tapPermissionRowByText(root, permission)) {
                    markClicked();
                    return;
                }
            }
        }

        boolean definitelyPermissionList = text.contains("uprawnienia aplikacji")
                || text.contains("app permissions")
                || text.contains("maja dostep")
                || text.contains("allowed");

        boolean stillHasDeniedSection = text.contains("nie maja dostepu")
                || text.contains("not allowed");

        if (definitelyPermissionList && !stillHasDeniedSection) {
            finishPermissionFlowAndCloseSettings();
        }
    }'''
pat = r'private void handlePermissionsList\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_handle, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody handlePermissionsList().')

# 2. Końcówka flow: nie otwieraj apki Wyczyść TMS i nie odpalaj TMS. Po prostu zdejmij tryb, schowaj overlay, cofnij do poprzedniego ekranu.
# HOME często powoduje wrażenie, że "przeskakuje do naszej aplikacji", więc używamy BACK 2x.
new_finish = '''private void finishPermissionFlowAndCloseSettings() {
        if (finalToastShown) return;
        finalToastShown = true;

        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();

        try {
            android.widget.Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }

        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 900);
    }'''
pat_finish = r'private void finishPermissionFlowAndCloseSettings\(\) \{.*?\n    \}'
if re.search(pat_finish, s, flags=re.S):
    s = re.sub(pat_finish, new_finish, s, flags=re.S, count=1)
else:
    # Dodaj, jeśli jej jeszcze nie ma.
    marker = 'private void openTmsAppAndFinishPermissionFlow()'
    if marker in s:
        s = s.replace(marker, new_finish + '\n\n    ' + marker, 1)
    else:
        marker = 'private void openTmsApp()'
        if marker in s:
            s = s.replace(marker, new_finish + '\n\n    ' + marker, 1)
        else:
            raise SystemExit('Nie znalazłam miejsca na finishPermissionFlowAndCloseSettings().')

# 3. Jeśli stary kod nadal kończy przez openTmsAppAndFinishPermissionFlow z listy uprawnień, zamień na bezpieczny finish.
s = s.replace('handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 900);', 'handler.postDelayed(this::finishPermissionFlowAndCloseSettings, 500);')
s = s.replace('handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 1000);', 'handler.postDelayed(this::finishPermissionFlowAndCloseSettings, 500);')

# 4. Usuń agresywne wymuszanie otwierania ustawień z ekranu Wyczyść TMS, bo powoduje pętle/przeskoki.
# Jeśli użytkownik już jest w informacji o aplikacji, nie chcemy wracać do naszej aplikacji i znów wymuszać startActivity.
s = re.sub(
    r'if \(isMode\(MODE_GRANT_TMS_PERMISSIONS\) \|\| isMode\(MODE_FULL_REPAIR\)\) \{\s*forceOpenTmsSettingsIfNeeded\(\);\s*\}',
    '',
    s,
    flags=re.S
)
s = re.sub(
    r'if \(isMode\(MODE_GRANT_TMS_PERMISSIONS\)\) \{\s*forceOpenTmsSettingsIfNeeded\(\);\s*\}',
    '',
    s,
    flags=re.S
)

# 5. W App Info klikaj tylko Uprawnienia. Jeżeli klik się nie uda, nie kończ flow i nie wracaj do apki.
new_click_app_info = '''private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (tapAppInfoPermissionsRow(root)) {
            markClicked();
        }
    }'''
pat_app = r'private void clickAppInfoPermissions\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat_app, s, flags=re.S):
    s = re.sub(pat_app, new_click_app_info, s, flags=re.S, count=1)

# 6. sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: zatrzymano przedwczesne kończenie flow i przeskakiwanie do Wyczyść TMS')
