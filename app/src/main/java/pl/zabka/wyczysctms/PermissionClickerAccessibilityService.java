package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.widget.LinearLayout;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
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

    private static final long POLL_MS = 450;
    private static final long CLICK_DELAY_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean watcherRunning;
    private boolean clickPending;
    private boolean alwaysAllowPending = false;
    private String pendingKey = "";

    private WindowManager windowManager;
    private View instructionOverlay;
    private String overlayMessage = "";
    private View automationDimOverlay;
    private TextView automationStatusText;

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
        hideInstruction();
        handler.removeCallbacksAndMessages(null);
        watcherRunning = false;
        clickPending = false;
        pendingKey = "";
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
        String pkg = root.getPackageName() == null
                ? "" : root.getPackageName().toString().toLowerCase();
        String text = normalize(collectText(root));

        if (isAutomationMode(mode) && !isInitialLocationDialog(text) && !isAlwaysLocationScreen(text)) {
            showAutomationOverlay("Naprawa TMS w toku", "Prosimy nie dotykać ekranu. Aplikacja wykona kolejne kroki automatycznie.");
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && isUninstallDialog(text)) {
            hideInstruction();
            scheduleClick("uninstall", Arrays.asList("OK", "Ok", "Odinstaluj", "Uninstall"), 1400);
            return;
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && isInstallerScreen(text)) {
            hideInstruction();
            scheduleClick("installer", installerButtons, 1200);
            return;
        }

        if (!MODE_OPEN_TMS.equals(mode) && !MODE_GRANT_TMS_PERMISSIONS.equals(mode)) {
            hideInstruction();
            return;
        }

        // Lokalizacja jest jedynym ręcznym krokiem.
        if (isInitialLocationDialog(text)) {
            hideAutomationOverlay();
            showInstruction(root, "Nadaj uprawnienie do lokalizacji\n\nWybierz: PODCZAS UŻYWANIA APLIKACJI", true);
            return;
        }

        // Po ręcznym kliknięciu pierwszej lokalizacji automat kontynuuje.
        if (isTmsLocationUpdatePopup(text)) {
            hideInstruction();
            scheduleClick("location_update", Arrays.asList(
                    "ZAKTUALIZUJ USTAWIENIA", "Zaktualizuj ustawienia",
                    "AKTUALIZUJ USTAWIENIA", "Aktualizuj ustawienia",
                    "UPDATE SETTINGS", "Update settings"
            ), 1200);
            return;
        }

        // Końcowa lokalizacja także ręcznie, ponieważ to ustawienie systemowe.
        if (isAlwaysLocationScreen(text)) {
            hideAutomationOverlay();
            hideInstruction();
            clickAlwaysAllowAndReturn(root);
            return;
        }

        hideInstruction();

        if (text.contains("potwierdz") || text.contains("confirm")) {
            scheduleClick("confirm", Arrays.asList("Potwierdź", "Potwierdz", "Confirm", "OK", "Ok"), 900);
            return;
        }

        if (isCameraDialog(text)) {
            scheduleClick("camera", Arrays.asList(
                    "Podczas używania aplikacji", "Podczas uzywania aplikacji",
                    "Podczas korzystania z aplikacji", "While using the app"
            ), 1300);
            return;
        }

        if (isRuntimePermissionDialog(pkg, text)) {
            scheduleClick("runtime_allow", Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"), 1000);
        }
    }

    private void scheduleClick(String key, List<String> labels, long delayMs) {
        if (clickPending || key.equals(pendingKey)) return;
        clickPending = true;
        pendingKey = key;

        handler.postDelayed(() -> {
            boolean clicked = false;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) clicked = clickVisibleText(root, labels);
            } finally {
                clickPending = false;
                if (!clicked) {
                    handler.postDelayed(() -> pendingKey = "", 700);
                } else {
                    handler.postDelayed(() -> pendingKey = "", 1200);
                }
            }
        }, delayMs);
    }

    private boolean clickVisibleText(AccessibilityNodeInfo root, List<String> labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            String wanted = normalize(label);

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String visible = normalize(nodeText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                AccessibilityNodeInfo clickable = smallestClickableParent(node);
                if (clickable != null
                        && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    return clickNodeAtBounds(node, bounds);
                }
            }
        }
        return false;
    }

    private boolean clickNodeAtBounds(AccessibilityNodeInfo node, Rect bounds) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return tapAt(bounds.centerX(), bounds.centerY());
    }


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

    private AccessibilityNodeInfo smallestClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = null;
        int bestArea = Integer.MAX_VALUE;

        for (int i = 0; i < 7 && current != null; i++) {
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            if (current.isVisibleToUser() && current.isEnabled()
                    && current.isClickable() && !bounds.isEmpty()) {
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

    private void showInstruction(String message) {
        if (message.equals(overlayMessage) && instructionOverlay != null) return;
        hideInstruction();

        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(Color.argb(165, 0, 0, 0));

            TextView text = new TextView(this);
            text.setText(message);
            text.setTextColor(Color.WHITE);
            text.setTextSize(19);
            text.setGravity(Gravity.CENTER);
            text.setPadding(dp(20), dp(16), dp(20), dp(16));
            text.setBackgroundColor(Color.rgb(180, 35, 24));

            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.gravity = Gravity.BOTTOM;
            textParams.leftMargin = dp(16);
            textParams.rightMargin = dp(16);
            textParams.bottomMargin = dp(92);
            overlay.addView(text, textParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlay, params);
            instructionOverlay = overlay;
            overlayMessage = message;
        } catch (Exception ignored) {
        }
    }

    private void hideInstruction() {
        try {
            if (windowManager != null && instructionOverlay != null) {
                windowManager.removeView(instructionOverlay);
            }
        } catch (Exception ignored) {
        }
        instructionOverlay = null;
        overlayMessage = "";
    }

    private void clickAlwaysAllowAndReturn(AccessibilityNodeInfo root) {
        if (alwaysAllowPending) return;
        alwaysAllowPending = true;

        handler.postDelayed(() -> {
            AccessibilityNodeInfo current = getRootInActiveWindow();
            if (current == null) {
                alwaysAllowPending = false;
                return;
            }

            boolean clicked = clickVisibleText(current, Arrays.asList(
                    "Zawsze zezwalaj",
                    "Allow all the time",
                    "Always allow"
            ));

            if (!clicked) {
                Rect target = findBoundsByText(current, Arrays.asList(
                        "Zawsze zezwalaj",
                        "Allow all the time",
                        "Always allow"
                ));
                if (target != null && !target.isEmpty()) {
                    clicked = tapGesture(target.centerX(), target.centerY());
                }
            }

            if (!clicked) {
                // Sprawdzony punkt z działającej gałęzi v82-v89 na PM95.
                clicked = tapGesture(112, 1145);
            }

            if (clicked) {
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    alwaysAllowPending = false;
                    Toast.makeText(this,
                            "Lokalizacja ustawiona. Wracam do TMS.",
                            Toast.LENGTH_SHORT).show();
                }, 1400);
            } else {
                alwaysAllowPending = false;
            }
        }, 1500);
    }

    private Rect findBoundsByText(AccessibilityNodeInfo root, List<String> labels) {
        if (root == null) return null;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
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

    private boolean tapGesture(int x, int y) {
        if (x <= 0 || y <= 0) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 80, 180);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean isAutomationMode(String mode) {
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

    private boolean isUninstallDialog(String text) {
        return (text.contains("odinstalowac te aplikacje")
                || text.contains("odinstaluj")
                || text.contains("uninstall"))
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"));
    }

    private boolean isInstallerScreen(String text) {
        boolean action = text.contains("zainstaluj") || text.contains("instaluj")
                || text.contains("install") || text.contains("gotowe") || text.contains("done")
                || text.contains("aplikacja zostala zainstalowana");
        return action && !text.contains("odinstaluj") && !text.contains("uninstall");
    }

    private boolean isInitialLocationDialog(String text) {
        return text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz") || text.contains("dokladna") || text.contains("przyblizona"));
    }

    private boolean isTmsLocationUpdatePopup(String text) {
        return text.contains("zaktualizuj ustawienia")
                || text.contains("aktualizuj ustawienia")
                || text.contains("update settings");
    }

    private boolean isAlwaysLocationScreen(String text) {
        return text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj");
    }

    private boolean isCameraDialog(String text) {
        return (text.contains("aparat") || text.contains("camera")
                || (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")))
                && text.contains("nie zezwalaj");
    }

    private boolean isRuntimePermissionDialog(String pkg, String text) {
        boolean controller = pkg.contains("permissioncontroller")
                || pkg.contains("packageinstaller") || pkg.equals("android");
        return controller && (text.contains("nie zezwalaj")
                || text.contains("dont allow") || text.contains("don't allow"));
    }

    private String nodeText(AccessibilityNodeInfo node) {
        StringBuilder out = new StringBuilder();
        if (node.getText() != null) out.append(node.getText()).append(' ');
        if (node.getContentDescription() != null) out.append(node.getContentDescription()).append(' ');
        return out.toString();
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

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replace("ą", "a").replace("ć", "c").replace("ę", "e")
                .replace("ł", "l").replace("ń", "n").replace("ó", "o")
                .replace("ś", "s").replace("ż", "z").replace("ź", "z").trim();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
