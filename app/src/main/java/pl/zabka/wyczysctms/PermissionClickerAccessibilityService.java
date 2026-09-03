package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {

    private WindowManager uninstallOverlayWindowManager;
    private View uninstallOverlayTop;
    private View uninstallOverlayBottom;
    private View uninstallOverlayLeft;
    private View uninstallOverlayRight;
    private TextView uninstallOverlayMessage;


    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";

    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";

    private static final long POLL_MS = 400;
    private static final long MIN_CLICK_INTERVAL_MS = 1100;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean watcherRunning = false;
    private boolean actionPending = false;
    private long lastClickTime = 0;
    private String pendingScreenKey = "";

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
        hideManualUninstallOverlay();
        handler.removeCallbacksAndMessages(null);
        watcherRunning = false;
        actionPending = false;
        pendingScreenKey = "";
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

        // Build 149: wszystkie automatyczne kliknięcia pozostają bez zmian.
        // Tylko potwierdzenie odinstalowania wykonuje kierowca przez jasny przycisk OK.
        if (isManualUninstallDialog(text)) {
            showManualUninstallOverlay(root);
            return;
        } else {
            hideManualUninstallOverlay();
        }

        ScreenAction action = detectAction(mode, packageName, text);
        if (action == null) {
            pendingScreenKey = "";
            return;
        }

        scheduleAction(action);
    }

    private ScreenAction detectAction(String mode, String packageName, String text) {
        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && isUninstallDialog(packageName, text)) {
            return ScreenAction.text("uninstall", 1500,
                    Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && isInstallerScreen(packageName, text)) {
            return ScreenAction.text("installer", 1100, installerButtons);
        }

        if (!MODE_OPEN_TMS.equals(mode) && !MODE_GRANT_TMS_PERMISSIONS.equals(mode)) {
            return null;
        }

        // Kolejność jest celowa. Lokalizacja musi być wykryta przed ogólnym dialogiem zgody.
        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            return ScreenAction.point("location_initial", 1500, 528, 1331, false);
        }

        if (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")) {
            return ScreenAction.point("camera", 1500, 583, 1097, false);
        }

        if (text.contains("dostep do kontaktow")) {
            return ScreenAction.point("contacts", 1200, 566, 1157, false);
        }

        if (text.contains("urzadzen w poblizu")) {
            return ScreenAction.point("nearby", 1200, 614, 1202, false);
        }

        if (text.contains("polaczen telefonicznych") || text.contains("zarzadzanie nimi")) {
            return ScreenAction.point("phone", 1200, 554, 1184, false);
        }

        if (text.contains("dostep do zdjec")
                && text.contains("muzyki")
                && text.contains("dzwiekow")) {
            return ScreenAction.point("media", 1200, 553, 1184, false);
        }

        if (text.contains("potwierdz") || text.contains("confirm")) {
            return ScreenAction.text("confirm", 1000,
                    Arrays.asList("Potwierdź", "Potwierdz", "Confirm", "OK", "Ok"));
        }

        if (text.contains("dostep do lokalizacji")
                && (text.contains("zaktualizuj ustawienia")
                || text.contains("aktualizuj ustawienia")
                || text.contains("update settings"))) {
            return ScreenAction.point("location_update", 1500, 626, 1329, false);
        }

        if (text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")) {
            return ScreenAction.point("location_always", 1700, 106, 1158, true);
        }

        if (isRuntimePermissionDialog(packageName, text)) {
            if (text.contains("aparat") || text.contains("camera")) {
                return ScreenAction.text("camera_fallback", 1500, Arrays.asList(
                        "Podczas używania aplikacji",
                        "Podczas uzywania aplikacji",
                        "While using the app"
                ));
            }
            return ScreenAction.text("runtime_allow", 1100,
                    Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"));
        }

        return null;
    }

    private void scheduleAction(ScreenAction action) {
        if (actionPending) return;
        if (action.key.equals(pendingScreenKey)) return;

        actionPending = true;
        pendingScreenKey = action.key;

        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current == null) return;

                String currentText = normalize(collectText(current));
                ScreenAction stillCurrent = detectAction(
                        getFlowMode(),
                        current.getPackageName() == null
                                ? "" : current.getPackageName().toString().toLowerCase(),
                        currentText
                );

                if (stillCurrent == null || !stillCurrent.key.equals(action.key)) {
                    return;
                }

                boolean clicked;
                if (action.labels != null) {
                    clicked = clickVisibleText(current, action.labels);
                } else {
                    clicked = tapReferencePoint(action.referenceX, action.referenceY);
                }

                if (clicked) {
                    markClicked();
                    if (action.backAfterTap) {
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            setFlowMode(MODE_IDLE);
                            Toast.makeText(this,
                                    "Gotowe. Można uruchomić TMS.",
                                    Toast.LENGTH_LONG).show();
                        }, 1700);
                    }
                }
            } finally {
                actionPending = false;
                // Watcher może ponowić ten sam ekran tylko wtedy, gdy klik nie przełączył okna.
                handler.postDelayed(() -> pendingScreenKey = "", 1200);
            }
        }, action.delayMs);
    }

    private boolean tapReferencePoint(int referenceX, int referenceY) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int x = Math.round(width * (referenceX / 1024f));
        int y = Math.round(height * (referenceY / 2048f));
        return tapAt(x, y);
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
                    return true;
                }

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && tapAt(bounds.centerX(), bounds.centerY())) {
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
            if (current.isVisibleToUser()
                    && current.isEnabled()
                    && current.isClickable()
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

    private boolean tapAt(int x, int y) {
        if (x <= 0 || y <= 0 || !canClickNow()) return false;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 140);
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

    private boolean isRuntimePermissionDialog(String packageName, String text) {
        boolean controller = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.equals("android");
        boolean choices = text.contains("nie zezwalaj")
                || text.contains("dont allow")
                || text.contains("don't allow");
        return controller && choices;
    }

    private boolean canClickNow() {
        return System.currentTimeMillis() - lastClickTime >= MIN_CLICK_INTERVAL_MS;
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

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        actionPending = false;
        pendingScreenKey = "";
        if (MODE_OPEN_TMS.equals(mode)) {
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

    private static final class ScreenAction {
        final String key;
        final long delayMs;
        final List<String> labels;
        final int referenceX;
        final int referenceY;
        final boolean backAfterTap;

        private ScreenAction(String key, long delayMs, List<String> labels,
                             int referenceX, int referenceY, boolean backAfterTap) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
            this.backAfterTap = backAfterTap;
        }

        static ScreenAction text(String key, long delayMs, List<String> labels) {
            return new ScreenAction(key, delayMs, labels, 0, 0, false);
        }

        static ScreenAction point(String key, long delayMs,
                                  int referenceX, int referenceY, boolean backAfterTap) {
            return new ScreenAction(key, delayMs, null, referenceX, referenceY, backAfterTap);
        }
    }
    private boolean isManualUninstallDialog(String rawText) {
        String text = normalize(rawText);
        return (text.contains("odinstalowac te aplikacje")
                || text.contains("odinstaluj")
                || text.contains("uninstall"))
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"));
    }

    private void showManualUninstallOverlay(AccessibilityNodeInfo root) {
        if (uninstallOverlayMessage != null) return;
        try {
            if (uninstallOverlayWindowManager == null) {
                uninstallOverlayWindowManager =
                        (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            Rect okBounds = findUninstallOkBounds(root);

            if (okBounds == null || okBounds.isEmpty()) {
                // Fallback dla PM95 na podstawie pełnego ekranu 1024x2048.
                okBounds = new Rect(
                        Math.round(screenWidth * 0.70f),
                        Math.round(screenHeight * 0.50f),
                        Math.round(screenWidth * 0.98f),
                        Math.round(screenHeight * 0.63f));
            } else {
                okBounds.inset(-dpUninstall(18), -dpUninstall(12));
                okBounds.left = Math.max(0, okBounds.left);
                okBounds.top = Math.max(0, okBounds.top);
                okBounds.right = Math.min(screenWidth, okBounds.right);
                okBounds.bottom = Math.min(screenHeight, okBounds.bottom);
            }

            uninstallOverlayTop = addUninstallDim(0, 0, screenWidth, okBounds.top);
            uninstallOverlayBottom = addUninstallDim(
                    0, okBounds.bottom, screenWidth, screenHeight - okBounds.bottom);
            uninstallOverlayLeft = addUninstallDim(
                    0, okBounds.top, okBounds.left, okBounds.height());
            uninstallOverlayRight = addUninstallDim(
                    okBounds.right, okBounds.top,
                    screenWidth - okBounds.right, okBounds.height());

            TextView message = new TextView(this);
            message.setText("Potwierdź naprawę TMS\n\nWybierz jasny przycisk OK");
            message.setTextColor(Color.WHITE);
            message.setTextSize(20);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dpUninstall(24), dpUninstall(18),
                    dpUninstall(24), dpUninstall(18));
            message.setBackgroundColor(Color.rgb(0, 168, 107));

            WindowManager.LayoutParams messageParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            messageParams.gravity = Gravity.BOTTOM;
            messageParams.y = dpUninstall(70);
            uninstallOverlayWindowManager.addView(message, messageParams);
            uninstallOverlayMessage = message;
        } catch (Exception ignored) {
            hideManualUninstallOverlay();
        }
    }

    private Rect findUninstallOkBounds(AccessibilityNodeInfo root) {
        if (root == null) return null;
        for (String label : new String[]{"OK", "Ok", "Odinstaluj", "Uninstall"}) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) return bounds;
            }
        }
        return null;
    }

    private View addUninstallDim(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        View dim = new View(this);
        dim.setBackgroundColor(Color.argb(242, 4, 8, 14));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;
        uninstallOverlayWindowManager.addView(dim, params);
        return dim;
    }

    private void hideManualUninstallOverlay() {
        removeUninstallView(uninstallOverlayTop);
        removeUninstallView(uninstallOverlayBottom);
        removeUninstallView(uninstallOverlayLeft);
        removeUninstallView(uninstallOverlayRight);
        removeUninstallView(uninstallOverlayMessage);
        uninstallOverlayTop = null;
        uninstallOverlayBottom = null;
        uninstallOverlayLeft = null;
        uninstallOverlayRight = null;
        uninstallOverlayMessage = null;
    }

    private void removeUninstallView(View view) {
        if (view == null || uninstallOverlayWindowManager == null) return;
        try {
            uninstallOverlayWindowManager.removeView(view);
        } catch (Exception ignored) {
        }
    }

    private int dpUninstall(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

}
