#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
if not SERV.exists() or not MAIN.exists():
    raise SystemExit('Uruchom skrypt w glownym katalogu repo, obok folderu app.')

s = SERV.read_text(encoding='utf-8')
m = MAIN.read_text(encoding='utf-8')

# Importy dla nowoczesnej nakladki.
imports = [
    'import android.graphics.drawable.GradientDrawable;',
    'import android.widget.LinearLayout;'
]
anchor = 'import android.graphics.Rect;'
for imp in imports:
    if imp not in s:
        s = s.replace(anchor, anchor + '\n' + imp, 1)

# Pola nakladki.
field_anchor = '    private String overlayMessage = "";'
if field_anchor not in s:
    raise SystemExit('Nie znaleziono overlayMessage. Najpierw wgraj serwis v97/v99/v100.')
if 'private View automationDimOverlay;' not in s:
    s = s.replace(field_anchor, field_anchor + '''
    private View automationDimOverlay;
    private TextView automationStatusText;''', 1)

# Przy odinstalowaniu/instalacji i automatycznych zgodach pokazuj ciemna nakladke.
# Po root/text w handleCurrentScreen dodaj stan ogolny, ale lokalizacja nadpisze go instrukcja.
needle = '        String text = normalize(collectText(root));'
if needle not in s:
    raise SystemExit('Nie znaleziono pobierania tekstu ekranu.')
insert = needle + '''

        if (isAutomationMode(mode) && !isInitialLocationDialog(text) && !isAlwaysLocationScreen(text)) {
            showAutomationOverlay("Naprawa TMS w toku", "Prosimy nie dotykać ekranu. Aplikacja wykona kolejne kroki automatycznie.");
        }'''
if 'showAutomationOverlay("Naprawa TMS w toku"' not in s:
    s = s.replace(needle, insert, 1)

# Przy pierwszej lokalizacji zachowaj instrukcje i jasny otwor. Końcowa lokalizacja z v100 pozostaje automatyczna.
# Jeśli v97 bez roota, ujednolic wywolanie.
s = s.replace(
    'showInstruction("Nadaj uprawnienie do lokalizacji\\n\\nWybierz: PODCZAS UŻYWANIA APLIKACJI");',
    'showInstruction(root, "Nadaj uprawnienie do lokalizacji\\n\\nWybierz: PODCZAS UŻYWANIA APLIKACJI", true);'
)

# Przy automatycznych ekranach nie usuwaj ciemnej nakladki przez stare hideInstruction();
# hideInstruction usuwa tylko instrukcje lokalizacji, a nowa nakladka jest osobna.

# Aparat i standardowe zgody: wymus ACTION_CLICK + gesture bounds.
pat_click = r'    private boolean clickNodeAtBounds\(AccessibilityNodeInfo node, Rect bounds\) \{.*?\n    \}'
new_click = '''    private boolean clickNodeAtBounds(AccessibilityNodeInfo node, Rect bounds) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return tapAt(bounds.centerX(), bounds.centerY());
    }'''
if re.search(pat_click, s, flags=re.S):
    s = re.sub(pat_click, new_click, s, count=1, flags=re.S)
else:
    raise SystemExit('Nie znaleziono clickNodeAtBounds().')

if 'private boolean tapAt(int x, int y)' not in s:
    tap = '''
    private boolean tapAt(int x, int y) {
        if (x <= 0 || y <= 0) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 80, 200);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }
'''
    marker = '    private AccessibilityNodeInfo smallestClickableParent('
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na tapAt().')
    s = s.replace(marker, tap + '\n' + marker, 1)

# Metody nowoczesnej, bardzo ciemnej nakladki.
helpers = '''    private boolean isAutomationMode(String mode) {
        return MODE_FULL_REPAIR.equals(mode)
                || MODE_UNINSTALL_TMS.equals(mode)
                || MODE_INSTALL_TMS.equals(mode)
                || MODE_OPEN_TMS.equals(mode)
                || MODE_GRANT_TMS_PERMISSIONS.equals(mode);
    }

    private void showAutomationOverlay(String title, String subtitle) {
        if (automationDimOverlay != null) return;
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.argb(242, 5, 9, 18));

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(24), dp(24), dp(24), dp(24));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.rgb(16, 24, 40));
            cardBg.setCornerRadius(dp(22));
            cardBg.setStroke(dp(1), Color.rgb(52, 64, 84));
            card.setBackground(cardBg);

            TextView icon = new TextView(this);
            icon.setText("✓");
            icon.setTextSize(30);
            icon.setTextColor(Color.rgb(52, 211, 153));
            icon.setGravity(Gravity.CENTER);
            card.addView(icon, new LinearLayout.LayoutParams(-1, dp(52)));

            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextSize(22);
            titleView.setTextColor(Color.WHITE);
            titleView.setGravity(Gravity.CENTER);
            card.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(15);
            subtitleView.setTextColor(Color.rgb(208, 213, 221));
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setPadding(0, dp(10), 0, 0);
            card.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
            automationStatusText = subtitleView;

            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2);
            cardParams.gravity = Gravity.CENTER;
            cardParams.leftMargin = dp(22);
            cardParams.rightMargin = dp(22);
            root.addView(card, cardParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    -1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(root, params);
            automationDimOverlay = root;
        } catch (Exception ignored) {
        }
    }

    private void hideAutomationOverlay() {
        try {
            if (windowManager != null && automationDimOverlay != null) {
                windowManager.removeView(automationDimOverlay);
            }
        } catch (Exception ignored) {
        }
        automationDimOverlay = null;
        automationStatusText = null;
    }

'''
marker = '    private boolean isUninstallDialog('
if 'private void showAutomationOverlay(' not in s:
    if marker not in s:
        raise SystemExit('Nie znaleziono miejsca na overlay.')
    s = s.replace(marker, helpers + marker, 1)

# Gdy pojawia się ręczna lokalizacja, usuń automat overlay przed instrukcją.
loc_marker = '        if (isInitialLocationDialog(text)) {'
if loc_marker in s and 'hideAutomationOverlay();' not in s[s.find(loc_marker):s.find(loc_marker)+220]:
    s = s.replace(loc_marker, loc_marker + '\n            hideAutomationOverlay();', 1)

# Gdy końcowa lokalizacja jest automatyczna z v100, usuń ciemny overlay przed klikaniem.
always_marker = '        if (isAlwaysLocationScreen(text)) {'
if always_marker in s and 'hideAutomationOverlay();' not in s[s.find(always_marker):s.find(always_marker)+220]:
    s = s.replace(always_marker, always_marker + '\n            hideAutomationOverlay();', 1)

# MAIN: komunikat przed startem i próba zamknięcia TMS w tle.
if 'import android.app.ActivityManager;' not in m:
    m = m.replace('import android.app.Activity;\n', 'import android.app.Activity;\nimport android.app.ActivityManager;\n', 1)

pat = r'    private void repairTms\(\) \{'
replacement = '''    private void repairTms() {
        closeTmsBeforeRepair();'''
if not re.search(pat, m):
    raise SystemExit('Nie znaleziono repairTms().')
m = re.sub(pat, replacement, m, count=1)

close_method = '''
    private void closeTmsBeforeRepair() {
        try {
            ActivityManager activityManager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.killBackgroundProcesses(detectedTmsPackage);
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this,
                "Przed naprawą aplikacja TMS powinna być zamknięta. Rozpoczynam bezpieczną naprawę.",
                Toast.LENGTH_LONG).show();
    }

'''
marker_m = '    private void uninstallTms() {'
if 'private void closeTmsBeforeRepair()' not in m:
    if marker_m not in m:
        raise SystemExit('Nie znaleziono miejsca na closeTmsBeforeRepair().')
    m = m.replace(marker_m, close_method + marker_m, 1)

# Manifest permission, jeśli istnieje manifest.
manifest = ROOT / 'app/src/main/AndroidManifest.xml'
if manifest.exists():
    x = manifest.read_text(encoding='utf-8')
    perm = '<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" />'
    if perm not in x:
        x = x.replace('<application', perm + '\n\n    <application', 1)
        manifest.write_text(x, encoding='utf-8')

for name, text in [('Service', s), ('MainActivity', m)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;', '<strong']:
        if token in text:
            raise SystemExit(f'{name}: HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: klamry {text.count("{")} / {text.count("}")}')

SERV.write_text(s, encoding='utf-8')
MAIN.write_text(m, encoding='utf-8')
print('OK: przywrócono klik pierwszej zgody, dodano bardzo ciemną nowoczesną blokadę i zamykanie TMS w tle')
