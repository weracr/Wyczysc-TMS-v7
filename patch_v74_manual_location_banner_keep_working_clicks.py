#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# v74: zachowuje działające kliknięcia (odinstalowanie, instalacja, aparat, telefon itd.),
# ale dwa ekrany lokalizacji obsługuje ręcznie kierowca. Banner jest nietykalny,
# więc nie zasłania ani nie blokuje przycisków Androida.

# 1. Dodaj stan bannera.
if 'private TextView manualInstructionText;' not in s:
    anchor = 'private View automationOverlayView;'
    if anchor not in s:
        raise SystemExit('Nie znaleziono pola automationOverlayView.')
    s = s.replace(anchor, anchor + '\n    private TextView manualInstructionText;')

# 2. W handleScreen po screenText przechwyć wyłącznie dwa ekrany lokalizacji.
needle = '        String screenText = normalize(collectText(root) + " " + collectEventText(event));'
if needle not in s:
    raise SystemExit('Nie znaleziono screenText w handleScreen().')

block = needle + '''

        // HYBRYDA PM95: lokalizację wybiera kierowca, pozostałe działające zgody zostają automatyczne.
        if (isMode(MODE_OPEN_TMS) && isManualInitialLocationScreen(screenText)) {
            showManualInstructionBanner("Proszę kliknąć: PODCZAS UŻYWANIA APLIKACJI");
            return;
        }

        if (isMode(MODE_OPEN_TMS) && isManualAlwaysLocationScreen(screenText)) {
            showManualInstructionBanner("Proszę wybrać: ZAWSZE ZEZWALAJ, a następnie nacisnąć strzałkę WSTECZ");
            return;
        }

        hideManualInstructionBanner();'''
s = s.replace(needle, block, 1)

# 3. Dodaj rozpoznanie ekranów i banner przepuszczający dotyk.
helpers = '''private boolean isManualInitialLocationScreen(String screenText) {
        String text = normalize(screenText);
        return text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz")
                || (text.contains("dokladna") && text.contains("przyblizona")));
    }

    private boolean isManualAlwaysLocationScreen(String screenText) {
        String text = normalize(screenText);
        return text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");
    }

    private void showManualInstructionBanner(String message) {
        try {
            if (overlayWindowManager == null) {
                overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            if (automationOverlayView == null) {
                TextView banner = new TextView(this);
                banner.setTextColor(Color.WHITE);
                banner.setTextSize(17);
                banner.setGravity(Gravity.CENTER);
                banner.setPadding(24, 18, 24, 18);
                banner.setBackgroundColor(Color.rgb(180, 35, 24));
                manualInstructionText = banner;
                automationOverlayView = banner;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP;
                overlayWindowManager.addView(automationOverlayView, params);
            }

            if (manualInstructionText != null) {
                manualInstructionText.setText(message);
            }
        } catch (Exception ignored) {
        }
    }

    private void hideManualInstructionBanner() {
        try {
            if (overlayWindowManager != null && automationOverlayView != null) {
                overlayWindowManager.removeView(automationOverlayView);
            }
        } catch (Exception ignored) {
        }
        automationOverlayView = null;
        manualInstructionText = null;
    }

    '''
marker = 'private boolean isOwnAppOrAdminPanel'
if marker not in s:
    raise SystemExit('Nie znaleziono miejsca na metody bannera.')
s = s.replace(marker, helpers + marker, 1)

# 4. Istniejący overlay nie może usuwać ręcznego bannera na początku każdego eventu.
s = s.replace('        updateOverlayVisibility();\n', '')

# 5. Podmień hideAutomationOverlay, aby sprzątał także banner.
pat = r'private void hideAutomationOverlay\(\) \{.*?\n    \}'
replacement = '''private void hideAutomationOverlay() {
        hideManualInstructionBanner();
    }'''
if re.search(pat, s, flags=re.S):
    s = re.sub(pat, replacement, s, flags=re.S, count=1)

# 6. Wyłącz automatyczne handlery lokalizacji, bo przechwytuje je banner wcześniej.
# Nie usuwamy kodu, aby nie naruszać innych działających etapów.

# Sanity.
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'W pliku pozostał HTML: {token}')
if s.count('private boolean isManualInitialLocationScreen(') != 1:
    raise SystemExit('Nieprawidłowa liczba metod isManualInitialLocationScreen().')
if s.count('private void showManualInstructionBanner(') != 1:
    raise SystemExit('Nieprawidłowa liczba metod showManualInstructionBanner().')

p.write_text(s, encoding='utf-8')
print('OK: zachowano działający autoklik, a oba ekrany lokalizacji pokazują banner i przepuszczają dotyk kierowcy')
