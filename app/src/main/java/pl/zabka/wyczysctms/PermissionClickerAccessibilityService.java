package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.graphics.drawable.ColorDrawable;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.widget.TextView;
import android.view.WindowManager;
import android.view.View;
import android.view.Gravity;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {

    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";

    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";

    private static final long POLL_MS = 500;
    private static final long CLICK_GUARD_MS = 1100;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastClickTime = 0;
    private boolean watcherRunning = false;
    private boolean waitingForAlwaysLocation = false;
    private boolean finalBackScheduled = false;
    private WindowManager bannerWindowManager;
    private View statusBannerView;
    private TextView statusBannerText;
    private View fullBlocker;
    private final View[] holeBlockers = new View[4];
    private View actionMessageView;
    private static final long UI_STABILIZE_DELAY_MS = 1450;

    private final List<String> installerButtons = Arrays.asList(
            "Gotowe", "Done", "Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj",
            "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue",
            "Zainstaluj mimo to", "Install anyway", "OK", "Ok"
    );

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startWatcher();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        startWatcher();
        handleCurrentScreen();
    }

    @Override
    public void onInterrupt() {
        hideAllGuidance();
    }

    private void startWatcher() {
        if (watcherRunning) return;
        watcherRunning = true;
        handler.post(watcher);
    }

    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            handleCurrentScreen();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void handleCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String mode = getFlowMode();
        String packageName = root.getPackageName() == null
                ? "" : root.getPackageName().toString().toLowerCase();
        String text = normalize(collectText(root));

        // Dwa kroki lokalizacji wykonuje kierowca. Banner przepuszcza dotyk.
        if (MODE_OPEN_TMS.equals(mode) && isInitialLocationDialog(text)) {
            showGuidanceWithHole(
                    "Wymagane działanie: wybierz PODCZAS UŻYWANIA APLIKACJI",
                    0.08f, 0.47f, 0.84f, 0.18f);
            return;
        }

        if (MODE_OPEN_TMS.equals(mode) && isAlwaysLocationSettings(text)) {
            showGuidanceWithHole(
                    "Wymagane działanie: wybierz ZAWSZE ZEZWALAJ, a następnie naciśnij WSTECZ",
                    0.03f, 0.45f, 0.94f, 0.16f);
            return;
        }

        if (isAutomationMode(mode)) {
            showFullBlocker(
                    "Naprawa TMS w toku. Prosimy przez chwilę nie dotykać ekranu.");
        } else {
            hideAllGuidance();
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && isUninstallDialog(packageName, text)) {
            handler.postDelayed(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current != null) clickFirst(current, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));
            }, UI_STABILIZE_DELAY_MS);
            return;
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && isInstallerScreen(packageName, text)) {
            clickFirst(root, installerButtons);
            return;
        }

        if (!MODE_OPEN_TMS.equals(mode) && !MODE_GRANT_TMS_PERMISSIONS.equals(mode)) {
            return;
        }

        // 1. Pierwszy dialog lokalizacji PM95. Ten warunek ma pierwszeństwo przed ogólnym runtime.
        if (isInitialLocationDialog(text)) {
            clickVisibleText(root, Arrays.asList(
                    "Podczas używania aplikacji",
                    "Podczas uzywania aplikacji",
                    "While using the app"
            ));
            return;
        }

        // 2. Końcowy ekran ustawień lokalizacji PM95.
        if (isAlwaysLocationSettings(text)) {
            waitingForAlwaysLocation = true;
            handleAlwaysLocation(root);
            return;
        }

        // 3. Komunikat z TMS prowadzący do końcowego ekranu lokalizacji.
        if (isTmsUpdateLocationPopup(text)) {
            if (clickVisibleText(root, Arrays.asList(
                    "ZAKTUALIZUJ USTAWIENIA",
                    "Zaktualizuj ustawienia",
                    "AKTUALIZUJ USTAWIENIA",
                    "Aktualizuj ustawienia",
                    "UPDATE SETTINGS",
                    "Update settings"
            ))) {
                waitingForAlwaysLocation = true;
            }
            return;
        }

        // 4. Ostrzeżenie Androida dla starszej aplikacji / mediów.
        if (text.contains("potwierdz") || text.contains("confirm")) {
            if (clickVisibleText(root, Arrays.asList("Potwierdź", "Potwierdz", "Confirm", "OK", "Ok"))) {
                return;
            }
        }

        // 5. Pozostałe natywne dialogi runtime.
        if (isRuntimePermissionDialog(packageName, text)) {
            if (text.contains("aparat") || text.contains("camera")) {
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo current = getRootInActiveWindow();
                    if (current != null) clickVisibleText(current, Arrays.asList(
                            "Podczas używania aplikacji",
                            "Podczas uzywania aplikacji",
                            "While using the app"));
                }, UI_STABILIZE_DELAY_MS);
            } else {
                clickVisibleText(root, Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"));
            }
        }
    }

    private void showFullBlocker(String message) {
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

    private boolean isAutomationMode(String mode) {
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

    private boolean isInitialLocationDialog(String text) {
        return text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz")
                || (text.contains("dokladna") && text.contains("przyblizona")));
    }

    private boolean isAlwaysLocationSettings(String text) {
        return text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");
    }

    private boolean isTmsUpdateLocationPopup(String text) {
        return containsTmsText(text)
                && (text.contains("zaktualizuj ustawienia")
                || text.contains("aktualizuj ustawienia")
                || text.contains("update settings"));
    }

    private boolean isRuntimePermissionDialog(String packageName, String text) {
        boolean controller = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.equals("android");
        boolean choices = text.contains("nie zezwalaj")
                || text.contains("dont allow")
                || text.contains("don't allow");
        return controller && choices;
    }

    private void handleAlwaysLocation(AccessibilityNodeInfo root) {
        if (isOptionChecked(root, Arrays.asList("Zawsze zezwalaj", "Allow all the time", "Always allow"))) {
            enablePreciseLocationIfNeeded(root);
            scheduleBackToTms();
            return;
        }

        clickVisibleText(root, Arrays.asList("Zawsze zezwalaj", "Allow all the time", "Always allow"));
    }

    private void scheduleBackToTms() {
        if (finalBackScheduled) return;
        finalBackScheduled = true;
        waitingForAlwaysLocation = false;

        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            setFlowMode(MODE_IDLE);
            hideAllGuidance();
            finalBackScheduled = false;
            showFinishActions();
        }, 900);
    }

    private void enablePreciseLocationIfNeeded(AccessibilityNodeInfo root) {
        if (isOptionChecked(root, Arrays.asList(
                "Używaj dokładnej lokalizacji",
                "Uzywaj dokladnej lokalizacji",
                "Precise location"
        ))) {
            return;
        }

        clickVisibleText(root, Arrays.asList(
                "Używaj dokładnej lokalizacji",
                "Uzywaj dokladnej lokalizacji",
                "Precise location"
        ));
    }

    private boolean clickVisibleText(AccessibilityNodeInfo root, List<String> labels) {
        if (!canClickNow()) return false;

        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;

            String wanted = normalize(label);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;

                String visible = normalize(getNodeText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                AccessibilityNodeInfo clickable = smallestClickableParent(node);
                if (clickable != null
                        && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    markClicked();
                    return true;
                }

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && tapAt(bounds.centerX(), bounds.centerY())) {
                    markClicked();
                    return true;
                }
            }
        }
        return false;
    }

    private AccessibilityNodeInfo smallestClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = null;
        int bestArea = Integer.MAX_VALUE;

        for (int i = 0; i < 7 && current != null; i++) {
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()
                    && !bounds.isEmpty()) {
                int area = bounds.width() * bounds.height();
                if (area < bestArea) {
                    best = current;
                    bestArea = area;
                }
            }
            current = current.getParent();
        }
        return best;
    }

    private boolean isOptionChecked(AccessibilityNodeInfo root, List<String> labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                AccessibilityNodeInfo current = node;
                for (int i = 0; i < 6 && current != null; i++) {
                    if (containsCheckedNode(current)) return true;
                    current = current.getParent();
                }
            }
        }
        return false;
    }

    private boolean containsCheckedNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isCheckable() && node.isChecked()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsCheckedNode(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean tapAt(int x, int y) {
        if (x <= 0 || y <= 0) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 120);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean isUninstallDialog(String packageName, String text) {
        boolean system = packageName.contains("packageinstaller")
                || packageName.contains("settings")
                || packageName.equals("android");
        return system && (text.contains("odinstaluj") || text.contains("uninstall"));
    }

    private boolean isInstallerScreen(String packageName, String text) {
        boolean installer = packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller");
        boolean action = text.contains("zainstaluj")
                || text.contains("instaluj")
                || text.contains("install")
                || text.contains("gotowe")
                || text.contains("done");
        return installer && action && !text.contains("odinstaluj") && !text.contains("uninstall");
    }

    private boolean clickFirst(AccessibilityNodeInfo root, List<String> labels) {
        return clickVisibleText(root, labels);
    }

    private boolean canClickNow() {
        return System.currentTimeMillis() - lastClickTime >= CLICK_GUARD_MS;
    }

    private void markClicked() {
        lastClickTime = System.currentTimeMillis();
    }

    private String getNodeText(AccessibilityNodeInfo node) {
        StringBuilder out = new StringBuilder();
        if (node.getText() != null) out.append(node.getText()).append(' ');
        if (node.getContentDescription() != null) out.append(node.getContentDescription()).append(' ');
        return out.toString().trim();
    }

    private String collectText(AccessibilityNodeInfo node) {
        StringBuilder out = new StringBuilder();
        collectText(node, out);
        return out.toString();
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        if (node.getText() != null) out.append(node.getText()).append(' ');
        if (node.getContentDescription() != null) out.append(node.getContentDescription()).append(' ');
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), out);
        }
    }

    private boolean containsTmsText(String text) {
        return text.contains("zabka") || text.contains("tms") || text.contains("falcon");
    }

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        if (MODE_OPEN_TMS.equals(mode)) {
            waitingForAlwaysLocation = false;
            finalBackScheduled = false;
            lastClickTime = 0;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_FLOW_MODE, mode)
                .apply();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replace("ą", "a")
                .replace("ć", "c")
                .replace("ę", "e")
                .replace("ł", "l")
                .replace("ń", "n")
                .replace("ó", "o")
                .replace("ś", "s")
                .replace("ż", "z")
                .replace("ź", "z")
                .trim();
    }
}
