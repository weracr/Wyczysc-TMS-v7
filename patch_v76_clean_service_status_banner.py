#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

# Patch zgodny z CZYSTYM serwisem (bez automationOverlayView).
# Dodaje od zera banner statusowy oraz ręczną obsługę dwóch ekranów lokalizacji.

# Imports
imports = {
    'import android.graphics.Color;': 'import android.graphics.Color;\n',
    'import android.graphics.PixelFormat;': 'import android.graphics.PixelFormat;\n',
    'import android.view.Gravity;': 'import android.view.Gravity;\n',
    'import android.view.View;': 'import android.view.View;\n',
    'import android.view.WindowManager;': 'import android.view.WindowManager;\n',
    'import android.widget.TextView;': 'import android.widget.TextView;\n',
}
anchor = 'import android.graphics.Path;\n'
for imp, line in imports.items():
    if imp not in s:
        if anchor not in s:
            raise SystemExit('Nie znaleziono sekcji importów.')
        s = s.replace(anchor, anchor + line, 1)

# Fields
field_anchor = '    private boolean finalBackScheduled = false;'
if field_anchor not in s:
    raise SystemExit('To nie jest oczekiwany czysty serwis: brak finalBackScheduled.')
if 'private WindowManager bannerWindowManager;' not in s:
    s = s.replace(field_anchor, field_anchor + '''
    private WindowManager bannerWindowManager;
    private View statusBannerView;
    private TextView statusBannerText;''', 1)

# Replace beginning of handleCurrentScreen after text collection.
needle = '''        String text = normalize(collectText(root));'''
if needle not in s:
    raise SystemExit('Nie znaleziono pobierania tekstu w handleCurrentScreen().')

insert = needle + '''

        // Dwa kroki lokalizacji wykonuje kierowca. Banner przepuszcza dotyk.
        if (MODE_OPEN_TMS.equals(mode) && isInitialLocationDialog(text)) {
            showStatusBanner(
                    "Wymagane działanie: wybierz PODCZAS UŻYWANIA APLIKACJI",
                    true);
            return;
        }

        if (MODE_OPEN_TMS.equals(mode) && isAlwaysLocationSettings(text)) {
            showStatusBanner(
                    "Wymagane działanie: wybierz ZAWSZE ZEZWALAJ, a następnie naciśnij WSTECZ",
                    true);
            return;
        }

        if (isAutomationMode(mode)) {
            showStatusBanner(
                    "Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.",
                    false);
        } else {
            hideStatusBanner();
        }'''
s = s.replace(needle, insert, 1)

# Remove old auto-location blocks because manual blocks above already return.
# They can remain unreachable for the exact screens, no invasive deletion needed.

# Add helpers before isInitialLocationDialog.
helper_marker = '    private boolean isInitialLocationDialog(String text) {'
if helper_marker not in s:
    raise SystemExit('Nie znaleziono isInitialLocationDialog().')

helpers = '''    private boolean isAutomationMode(String mode) {
        return MODE_FULL_REPAIR.equals(mode)
                || MODE_UNINSTALL_TMS.equals(mode)
                || MODE_INSTALL_TMS.equals(mode)
                || MODE_OPEN_TMS.equals(mode)
                || MODE_GRANT_TMS_PERMISSIONS.equals(mode);
    }

    private void showStatusBanner(String message, boolean actionRequired) {
        try {
            if (bannerWindowManager == null) {
                bannerWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            if (statusBannerView == null) {
                TextView banner = new TextView(this);
                banner.setTextColor(Color.WHITE);
                banner.setTextSize(17);
                banner.setGravity(Gravity.CENTER);
                banner.setPadding(24, 18, 24, 18);
                statusBannerText = banner;
                statusBannerView = banner;

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
                bannerWindowManager.addView(statusBannerView, params);
            }

            statusBannerText.setText(message);
            statusBannerText.setBackgroundColor(actionRequired
                    ? Color.rgb(180, 35, 24)
                    : Color.rgb(37, 99, 235));
        } catch (Exception ignored) {
        }
    }

    private void hideStatusBanner() {
        try {
            if (bannerWindowManager != null && statusBannerView != null) {
                bannerWindowManager.removeView(statusBannerView);
            }
        } catch (Exception ignored) {
        }
        statusBannerView = null;
        statusBannerText = null;
    }

'''
s = s.replace(helper_marker, helpers + helper_marker, 1)

# Ensure cleanup when flow finishes.
s = s.replace('            setFlowMode(MODE_IDLE);\n            finalBackScheduled = false;',
              '            setFlowMode(MODE_IDLE);\n            hideStatusBanner();\n            finalBackScheduled = false;', 1)

# Clean up on service interrupt.
s = s.replace('    public void onInterrupt() {\n    }',
              '    public void onInterrupt() {\n        hideStatusBanner();\n    }', 1)

# Sanity
for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'W pliku pozostał HTML: {token}')
for sig in ['private void showStatusBanner(', 'private void hideStatusBanner(', 'private boolean isAutomationMode(']:
    if s.count(sig) != 1:
        raise SystemExit(f'Nieprawidłowa liczba metod: {sig}')
if 'FLAG_NOT_TOUCHABLE' not in s:
    raise SystemExit('Banner nie przepuszcza dotyku.')

p.write_text(s, encoding='utf-8')
print('OK: dodano banner do czystego serwisu; lokalizacja ręczna, pozostałe kroki automatyczne')
