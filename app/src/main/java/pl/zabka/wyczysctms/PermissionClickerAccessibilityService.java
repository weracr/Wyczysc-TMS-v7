package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
    private static final long CLICK_GUARD_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastClickTime = 0;
    private boolean watcherRunning = false;
    private boolean preciseTapPending = false;

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

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && isUninstallDialog(packageName, text)) {
            scheduleTextClick(root, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"), 1450);
            return;
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && isInstallerScreen(packageName, text)) {
            scheduleTextClick(root, installerButtons, 1000);
            return;
        }

        if (!MODE_OPEN_TMS.equals(mode) && !MODE_GRANT_TMS_PERMISSIONS.equals(mode)) {
            return;
        }

        if (handlePm95ExactCoordinates(text)) {
            return;
        }

        if (text.contains("potwierdz") || text.contains("confirm")) {
            scheduleTextClick(root, Arrays.asList("Potwierdź", "Potwierdz", "Confirm", "OK", "Ok"), 1000);
            return;
        }

        if (isRuntimePermissionDialog(packageName, text)) {
            if (text.contains("aparat") || text.contains("camera")) {
                scheduleTextClick(root, Arrays.asList(
                        "Podczas używania aplikacji",
                        "Podczas uzywania aplikacji",
                        "While using the app"
                ), 1450);
            } else {
                scheduleTextClick(root, Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"), 1000);
            }
        }
    }

    private boolean handlePm95ExactCoordinates(String text) {
        if (preciseTapPending) return true;

        if (text.contains("lokalizacji urzadzenia")
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")) {
            schedulePreciseTap(528, 1331, 1450, false);
            return true;
        }

        if (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")) {
            schedulePreciseTap(583, 1097, 1450, false);
            return true;
        }

        if (text.contains("dostep do kontaktow")) {
            schedulePreciseTap(566, 1157, 1200, false);
            return true;
        }

        if (text.contains("urzadzen w poblizu")) {
            schedulePreciseTap(614, 1202, 1200, false);
            return true;
        }

        if (text.contains("polaczen telefonicznych") || text.contains("zarzadzanie nimi")) {
            schedulePreciseTap(554, 1184, 1200, false);
            return true;
        }

        if (text.contains("dostep do zdjec")
                && text.contains("muzyki")
                && text.contains("dzwiekow")) {
            schedulePreciseTap(553, 1184, 1200, false);
            return true;
        }

        if (text.contains("dostep do lokalizacji")
                && (text.contains("zaktualizuj ustawienia") || text.contains("aktualizuj ustawienia"))) {
            schedulePreciseTap(626, 1329, 1450, false);
            return true;
        }

        if (text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")) {
            schedulePreciseTap(106, 1158, 1600, true);
            return true;
        }

        return false;
    }

    private void schedulePreciseTap(int referenceX, int referenceY, long delayMs, boolean backAfterTap) {
        if (preciseTapPending) return;
        preciseTapPending = true;

        handler.postDelayed(() -> {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            int x = Math.round(width * (referenceX / 1024f));
            int y = Math.round(height * (referenceY / 2048f));

            tapAt(x, y);
            markClicked();

            if (backAfterTap) {
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    setFlowMode(MODE_IDLE);
                    Toast.makeText(this,
                            "Gotowe. Można uruchomić TMS.",
                            Toast.LENGTH_LONG).show();
                }, 1600);
            }

            handler.postDelayed(() -> preciseTapPending = false, 800);
        }, delayMs);
    }

    private void scheduleTextClick(AccessibilityNodeInfo ignoredRoot, List<String> labels, long delayMs) {
        if (preciseTapPending) return;
        preciseTapPending = true;

        handler.postDelayed(() -> {
            AccessibilityNodeInfo current = getRootInActiveWindow();
            if (current != null) {
                clickVisibleText(current, labels);
            }
            handler.postDelayed(() -> preciseTapPending = false, 700);
        }, delayMs);
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

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        if (MODE_OPEN_TMS.equals(mode)) {
            lastClickTime = 0;
            preciseTapPending = false;
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
