#!/usr/bin/env python3
from pathlib import Path
import re

p = Path.cwd() / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not p.exists():
    raise SystemExit(f'Nie znaleziono pliku: {p}')
s = p.read_text(encoding='utf-8')

for required in ['showStatusBanner(', 'hideStatusBanner()', 'statusBannerView']:
    if required not in s:
        raise SystemExit(f'Brakuje {required}. Zastosuj najpierw v76.')

# Importy.
anchor = 'import android.accessibilityservice.AccessibilityService;\n'
for line in [
    'import android.content.Intent;\n',
    'import android.graphics.drawable.ColorDrawable;\n',
    'import android.widget.Button;\n',
    'import android.widget.FrameLayout;\n'
]:
    if line.strip() not in s:
        s = s.replace(anchor, anchor + line, 1)

# Pola.
fa = '    private TextView statusBannerText;'
if fa not in s:
    raise SystemExit('Nie znaleziono pól v76.')
if 'private View fullBlocker;' not in s:
    s = s.replace(fa, fa + '''
    private View fullBlocker;
    private final View[] holeBlockers = new View[4];
    private View actionMessageView;
    private static final long UI_STABILIZE_DELAY_MS = 1450;''', 1)

# Większy odstęp.
s = re.sub(r'private static final long CLICK_GUARD_MS = \\d+;',
           'private static final long CLICK_GUARD_MS = 1450;', s, count=1)

# Podmień komunikaty v76.
s = s.replace('''showStatusBanner(
                    "Wymagane działanie: wybierz PODCZAS UŻYWANIA APLIKACJI",
                    true)''', '''showGuidanceWithHole(
                    "Wymagane działanie: wybierz PODCZAS UŻYWANIA APLIKACJI",
                    0.08f, 0.47f, 0.84f, 0.18f)''')
s = s.replace('''showStatusBanner(
                    "Wymagane działanie: wybierz ZAWSZE ZEZWALAJ, a następnie naciśnij WSTECZ",
                    true)''', '''showGuidanceWithHole(
                    "Wymagane działanie: wybierz ZAWSZE ZEZWALAJ, a następnie naciśnij WSTECZ",
                    0.03f, 0.45f, 0.94f, 0.16f)''')
s = s.replace('''showStatusBanner(
                    "Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.",
                    false)''', '''showFullBlocker(
                    "Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.")''')
s = s.replace('hideStatusBanner();', 'hideAllGuidance();')

methods = r'''    private void showFullBlocker(String message) {
        hideAllGuidance();
        try {
            if (bannerWindowManager == null) {
                bannerWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            FrameLayout layer = new FrameLayout(this);
            layer.setBackgroundColor(Color.argb(220, 0, 0, 0));
            layer.setOnTouchListener((v, e) -> true);
            TextView text = makeInstruction(message, false);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
            tp.gravity = Gravity.BOTTOM;
            tp.bottomMargin = dp2(110);
            layer.addView(text, tp);
            WindowManager.LayoutParams wp = overlayParams(-1, -1, Gravity.TOP | Gravity.START, 0, 0);
            bannerWindowManager.addView(layer, wp);
            fullBlocker = layer;
        } catch (Exception ignored) {}
    }

    private void showGuidanceWithHole(String message, float hx, float hy, float hw, float hh) {
        hideAllGuidance();
        try {
            if (bannerWindowManager == null) {
                bannerWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            int left = (int)(w * hx), top = (int)(h * hy);
            int right = left + (int)(w * hw), bottom = top + (int)(h * hh);
            addBlocker(0, 0, w, top, 0);
            addBlocker(0, bottom, w, h - bottom, 1);
            addBlocker(0, top, left, bottom - top, 2);
            addBlocker(right, top, w - right, bottom - top, 3);

            TextView text = makeInstruction(message, true);
            WindowManager.LayoutParams tp = overlayParams(
                    w, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM, 0, dp2(70));
            tp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            bannerWindowManager.addView(text, tp);
            statusBannerView = text;
            statusBannerText = text;
        } catch (Exception ignored) {}
    }

    private void addBlocker(int x, int y, int width, int height, int index) {
        if (width <= 0 || height <= 0) return;
        View v = new View(this);
        v.setBackgroundColor(Color.argb(220, 0, 0, 0));
        v.setOnTouchListener((view, event) -> true);
        bannerWindowManager.addView(v, overlayParams(width, height,
                Gravity.TOP | Gravity.START, x, y));
        holeBlockers[index] = v;
    }

    private TextView makeInstruction(String message, boolean required) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(19);
        text.setGravity(Gravity.CENTER);
        text.setPadding(24, 20, 24, 20);
        text.setBackgroundColor(required ? Color.rgb(180, 35, 24) : Color.rgb(37, 99, 235));
        return text;
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, int gravity, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        return p;
    }

    private void hideAllGuidance() {
        try {
            if (bannerWindowManager != null && fullBlocker != null) bannerWindowManager.removeView(fullBlocker);
        } catch (Exception ignored) {}
        fullBlocker = null;
        for (int i = 0; i < holeBlockers.length; i++) {
            try {
                if (bannerWindowManager != null && holeBlockers[i] != null) bannerWindowManager.removeView(holeBlockers[i]);
            } catch (Exception ignored) {}
            holeBlockers[i] = null;
        }
        try {
            if (bannerWindowManager != null && statusBannerView != null) bannerWindowManager.removeView(statusBannerView);
        } catch (Exception ignored) {}
        statusBannerView = null;
        statusBannerText = null;
    }

    private int dp2(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showFinishActions() {
        hideAllGuidance();
        try {
            if (bannerWindowManager == null) {
                bannerWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp2(24), dp2(24), dp2(24), dp2(24));
            panel.setGravity(Gravity.CENTER);
            panel.setBackgroundColor(Color.argb(235, 0, 0, 0));

            TextView title = makeInstruction("Naprawa zakończona. Można uruchomić TMS.", false);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

            Button open = new Button(this);
            open.setText("Uruchom TMS");
            open.setOnClickListener(v -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage("pl.optidata.tms_android_2017");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
                hideFinishActions();
            });
            panel.addView(open, new LinearLayout.LayoutParams(-1, dp2(56)));

            Button remove = new Button(this);
            remove.setText("Usuń komunikat");
            remove.setOnClickListener(v -> hideFinishActions());
            panel.addView(remove, new LinearLayout.LayoutParams(-1, dp2(56)));

            bannerWindowManager.addView(panel, overlayParams(-1, -1, Gravity.TOP | Gravity.START, 0, 0));
            actionMessageView = panel;
        } catch (Exception ignored) {}
    }

    private void hideFinishActions() {
        try {
            if (bannerWindowManager != null && actionMessageView != null) bannerWindowManager.removeView(actionMessageView);
        } catch (Exception ignored) {}
        actionMessageView = null;
    }

'''
marker = '    private boolean isAutomationMode(String mode) {'
if marker not in s:
    raise SystemExit('Nie znaleziono isAutomationMode() z v76.')
s = s.replace(marker, methods + marker, 1)

# Opóźnione kliknięcie odinstalowania.
old_uninstall = 'clickFirst(root, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));'
if old_uninstall in s:
    s = s.replace(old_uninstall, '''handler.postDelayed(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current != null) clickFirst(current, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));
            }, UI_STABILIZE_DELAY_MS);''', 1)

# Opóźnione kliknięcie aparatu w ogólnym runtime handlerze.
old_camera = '''clickVisibleText(root, Arrays.asList(
                        "Podczas używania aplikacji",
                        "Podczas uzywania aplikacji",
                        "While using the app"
                ));'''
if old_camera in s:
    s = s.replace(old_camera, '''handler.postDelayed(() -> {
                    AccessibilityNodeInfo current = getRootInActiveWindow();
                    if (current != null) clickVisibleText(current, Arrays.asList(
                            "Podczas używania aplikacji",
                            "Podczas uzywania aplikacji",
                            "While using the app"));
                }, UI_STABILIZE_DELAY_MS);''', 1)

# Po końcowym BACK pokaż wybór.
s = s.replace('Toast.makeText(this, "Gotowe. Uprawnienia TMS zostały nadane.", Toast.LENGTH_LONG).show();',
              'showFinishActions();', 1)

for token in ['&gt;', '&lt;', '<br>']:
    if token in s:
        raise SystemExit(f'Pozostał HTML: {token}')
for sig in ['private void showFullBlocker(', 'private void showGuidanceWithHole(', 'private void showFinishActions(']:
    if s.count(sig) != 1:
        raise SystemExit(f'Nieprawidłowa liczba metod: {sig}')

p.write_text(s, encoding='utf-8')
print('OK: pełna zasłona, przezroczyste pola lokalizacji, delay 1450 ms i wybór Uruchom TMS/Usuń komunikat')
