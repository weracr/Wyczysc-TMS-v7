#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# Potrzebny Toast do komunikatu końcowego.
if 'import android.widget.Toast;' not in s:
    s = s.replace('import android.widget.TextView;', 'import android.widget.TextView;\nimport android.widget.Toast;')

# Flaga końcowego komunikatu, żeby nie spamować Toastami.
if 'private boolean finalToastShown' not in s:
    s = s.replace(
        'private boolean openedAppSettingsForMissingPermission = false;',
        'private boolean openedAppSettingsForMissingPermission = false;\n    private boolean finalToastShown = false;'
    )

# Po każdym nowym trybie automatyzacji odblokuj możliwość pokazania finalnego Toastu.
if 'finalToastShown = false;' not in s:
    s = s.replace(
        'private void setFlowMode(String mode) {\n        getSharedPreferences',
        'private void setFlowMode(String mode) {\n        if (!MODE_IDLE.equals(mode)) {\n            finalToastShown = false;\n        }\n        getSharedPreferences'
    )

# Zastąp końcówkę handlePermissionsList: zamiast openTmsAppAndFinishPermissionFlow zamknij ustawienia i zdejmij blokadę.
new_handle = '''private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)) {
                if (tapPermissionRowByText(root, permission)) {
                    markClicked();
                    return;
                }
            }
        }

        // Jeżeli nie ma już pozycji w sekcji "Nie mają dostępu", kończymy flow.
        finishPermissionFlowAndCloseSettings();
    }'''

pat = r'private void handlePermissionsList\(AccessibilityNodeInfo root, String screenText\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_handle, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam metody handlePermissionsList().')

# Dodaj metodę kończącą flow.
if 'private void finishPermissionFlowAndCloseSettings()' not in s:
    method = '''private void finishPermissionFlowAndCloseSettings() {
        if (finalToastShown) return;
        finalToastShown = true;

        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();

        try {
            Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }

        // Zamykamy ekran ustawień/uprawnień. HOME jest stabilniejsze niż kilka cofnięć,
        // bo ustawienia Androida potrafią mieć różną głębokość ekranów na PM90/PM95.
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 300);
    }

    '''
    if 'private void openTmsAppAndFinishPermissionFlow()' in s:
        s = s.replace('private void openTmsAppAndFinishPermissionFlow()', method + 'private void openTmsAppAndFinishPermissionFlow()', 1)
    elif 'private void openTmsApp()' in s:
        s = s.replace('private void openTmsApp()', method + 'private void openTmsApp()', 1)
    else:
        raise SystemExit('Nie znalazłam miejsca na wklejenie finishPermissionFlowAndCloseSettings().')

# Jeśli gdzieś nadal końcówka odpala TMS po listach uprawnień, zamień ją na zamknięcie ustawień.
s = s.replace('handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 900);', 'handler.postDelayed(this::finishPermissionFlowAndCloseSettings, 500);')
s = s.replace('handler.postDelayed(this::openTmsAppAndFinishPermissionFlow, 1000);', 'handler.postDelayed(this::finishPermissionFlowAndCloseSettings, 500);')
s = s.replace('handler.postDelayed(this::openTmsApp, 1200);', 'handler.postDelayed(this::finishPermissionFlowAndCloseSettings, 500);')

# Dodatkowe zabezpieczenie: jeśli jesteśmy na liście Uprawnienia aplikacji i nie ma "Nie mają dostępu", zakończ.
# Nie ingerujemy w sekcję z denied permission, tam nadal klikamy po kolei.
if 'private boolean hasDeniedPermissionsSection' not in s:
    helper = '''private boolean hasDeniedPermissionsSection(String screenText) {
        String text = normalize(screenText);
        return text.contains("nie maja dostepu") || text.contains("not allowed");
    }

    '''
    marker = 'private boolean isPermissionInDeniedSection'
    if marker in s:
        s = s.replace(marker, helper + marker, 1)

# sanity
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: po nadaniu wszystkich uprawnień zamyka ustawienia, zdejmuje blokadę i pokazuje komunikat')
