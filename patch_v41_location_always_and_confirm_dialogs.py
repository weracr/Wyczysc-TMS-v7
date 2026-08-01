#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'

if not SERV.exists():
    raise SystemExit(f'Nie znaleziono pliku: {SERV}')

s = SERV.read_text(encoding='utf-8')

# 1. Upewnij się, że Potwierdź/Confirm jest w allowButtons.
if '"Potwierdź"' not in s and 'private final List<String> allowButtons' in s:
    s = s.replace('"Rozumiem"', '"Potwierdź", "Potwierdz", "Confirm", "Rozumiem"')

# 2. Obsługa okna potwierdzenia starszej wersji Androida dla Muzyka i dźwięk / Zdjęcia i filmy.
# Wstawiamy priorytetowo po sprawdzeniu canHandleTmsPermissions(), przed zwykłą obsługą runtime permission.
priority = '''if (isLegacyPermissionWarningDialog(packageName, screenText)) {
            clickLegacyPermissionConfirm(root);
            return;
        }

        '''
if 'isLegacyPermissionWarningDialog(packageName, screenText)' not in s:
    needle = 'if (isRuntimePermissionDialog(packageName, screenText)) {'
    if needle not in s:
        raise SystemExit('Nie znalazłam miejsca przed isRuntimePermissionDialog().')
    s = s.replace(needle, priority + needle, 1)

# 3. Dodaj metody do potwierdzania warning dialogów, jeśli ich nie ma.
if 'private boolean isLegacyPermissionWarningDialog(' not in s:
    methods = '''private boolean isLegacyPermissionWarningDialog(String packageName, String screenText) {
        boolean systemDialog = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");

        boolean warningText = screenText.contains("starszej wersji androida")
                || screenText.contains("older version of android")
                || screenText.contains("dostep do zdjec i filmow")
                || screenText.contains("dostęp do zdjęć i filmów")
                || screenText.contains("dostep do muzyki")
                || screenText.contains("dostęp do muzyki")
                || screenText.contains("dostep do zdjec i filmow rowniez bedzie mozliwy")
                || screenText.contains("również będzie możliwy")
                || screenText.contains("rowniez bedzie mozliwy");

        boolean confirmButton = screenText.contains("potwierdz")
                || screenText.contains("potwierdź")
                || screenText.contains("confirm")
                || screenText.contains("ok");

        return systemDialog && warningText && confirmButton;
    }

    private void clickLegacyPermissionConfirm(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (clickByTextAllowDanger(root, "Potwierdź")
                || clickByTextAllowDanger(root, "Potwierdz")
                || clickByTextAllowDanger(root, "Confirm")
                || clickByTextAllowDanger(root, "OK")
                || clickByTextAllowDanger(root, "Ok")) {
            markClicked();
        }
    }

    '''
    marker = 'private boolean isRuntimePermissionDialog'
    if marker not in s:
        raise SystemExit('Nie znalazłam miejsca na metody legacy dialog.')
    s = s.replace(marker, methods + marker, 1)

# 4. Podmień handleLocationScreen tak, żeby wymuszał Zawsze zezwalaj, a nie zostawiał "podczas używania".
new_location = '''private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            goBackToPermissionsListLater();
            return;
        }

        if (clickAnyText(root, alwaysLocationButtons)
                || tapTextCenter(root, "Zawsze zezwalaj")
                || tapTextCenter(root, "Zezwalaj cały czas")
                || tapTextCenter(root, "Zezwalaj caly czas")
                || tapTextCenter(root, "Allow all the time")
                || tapTextCenter(root, "Always allow")) {
            markClicked();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) enablePreciseLocationIfVisible(r);
            }, 650);
            goBackToPermissionsListLater();
        }
    }'''
pat = r'private void handleLocationScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, new_location, s, flags=re.S, count=1)
else:
    raise SystemExit('Nie znalazłam handleLocationScreen().')

# 5. W generic permission screen najpierw sprawdź warning dialog, potem szukaj Zezwalaj.
new_generic = '''private void handleGenericPermissionScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickByTextAllowDanger(root, "Potwierdź")
                || clickByTextAllowDanger(root, "Potwierdz")
                || clickByTextAllowDanger(root, "Confirm")) {
            markClicked();
            return;
        }
        if (clickAnyText(root, allowButtons)) {
            markClicked();
            goBackToPermissionsListLater();
        }
    }'''
pat2 = r'private void handleGenericPermissionScreen\(AccessibilityNodeInfo root\) \{.*?\n    \}'
if re.search(pat2, s, flags=re.S):
    s = re.sub(pat2, new_generic, s, flags=re.S, count=1)

# 6. sanity check
bad = re.findall(r'[A-Za-z]\*[A-Za-z]|=\*|\*\s*(if|return|boolean|private)|\*\s*\|', s)
if bad:
    raise SystemExit(f'Podejrzane znaki *: {bad[:10]}')

SERV.write_text(s, encoding='utf-8')
print('OK: lokalizacja ustawiana na Zawsze zezwalaj i dodano potwierdzanie okien Muzyka/Zdjęcia')
