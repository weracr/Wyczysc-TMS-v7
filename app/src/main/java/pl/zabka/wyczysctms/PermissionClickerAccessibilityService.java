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

    private static final String TMS_PACKAGE = "pl.optidata.tms_android_2017";
    private static final long POLL_MS = 450;
    private static final long CLICK_GUARD_MS = 800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean watcherRunning;
    private boolean actionPending;
    private long lastClickTime;
    private boolean settingsCoordinatePending = false;
    private String lastSettingsStage = "";
    private String pendingKey = "";

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
        handler.removeCallbacksAndMessages(null);
        watcherRunning = false;
        actionPending = false;
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
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString().toLowerCase();
        String text = normalize(collectText(root));
        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && (text.contains("odinstalowac te aplikacje") || text.contains("odinstaluj"))) {
            scheduleSettingsCoordinate("uninstall_ok", 861, 1169, 1500, 0);
            return;
        }

        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)
                && handlePm95SettingsCoordinates(text)) {
            return;
        }

        Action action = detectAction(mode, pkg, text);
        if (action == null) {
            pendingKey = "";
            return;
        }
        scheduleAction(action);
    }

    private Action detectAction(String mode, String pkg, String text) {
        if ((MODE_FULL_REPAIR.equals(mode) || MODE_UNINSTALL_TMS.equals(mode))
                && isUninstallDialog(pkg, text)) {
            return Action.textWithFallback("uninstall", 1500, Arrays.asList("OK", "Ok", "Odinstaluj", "Uninstall"), 861, 1169);
        }

        if ((MODE_FULL_REPAIR.equals(mode) || MODE_INSTALL_TMS.equals(mode))
                && isInstallerScreen(pkg, text)) {
            return Action.text("installer", 1400, installerButtons);
        }

        if (MODE_GRANT_TMS_PERMISSIONS.equals(mode)) {
            if (isAppInfoScreen(pkg, text)) {
                return Action.text("app_info_permissions", 1400,
                        Arrays.asList("Uprawnienia", "Permissions", "Zezwolenia"));
            }

            if (isPermissionsListScreen(pkg, text)) {
                return Action.text("permissions_location", 1400,
                        Arrays.asList("Lokalizacja", "Location"));
            }

            if (isLocationSettingsScreen(pkg, text)) {
                return Action.text("always_location", 1500,
                        Arrays.asList("Zawsze zezwalaj", "Allow all the time", "Always allow"));
            }
        }

        if (!MODE_OPEN_TMS.equals(mode)) return null;

        // Gdy lokalizacja została ustawiona wcześniej, to okno nie powinno się pojawić.
        // Zostawiamy bezpieczny fallback po tekście.
        if (isInitialLocationDialog(text)) {
            return Action.text("initial_location_fallback", 1800,
                    Arrays.asList("Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"));
        }

        if (text.contains("robienie zdjec") && text.contains("nagrywanie filmow")) {
            return Action.text("camera", 1500,
                    Arrays.asList("Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"));
        }

        if (text.contains("potwierdz") || text.contains("confirm")) {
            return Action.text("confirm", 1000,
                    Arrays.asList("Potwierdź", "Potwierdz", "Confirm", "OK", "Ok"));
        }

        if (isRuntimePermissionDialog(pkg, text)) {
            return Action.text("runtime_allow", 1100,
                    Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"));
        }

        return null;
    }

    private void scheduleAction(Action action) {
        if (actionPending || action.key.equals(pendingKey)) return;
        actionPending = true;
        pendingKey = action.key;

        handler.postDelayed(() -> {
            boolean clicked = false;
            try {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current == null) return;

                String pkg = current.getPackageName() == null ? "" : current.getPackageName().toString().toLowerCase();
                String text = normalize(collectText(current));
                Action stillCurrent = detectAction(getFlowMode(), pkg, text);
                if (stillCurrent == null || !stillCurrent.key.equals(action.key)) return;

                clicked = clickVisibleText(current, action.labels);
                if (!clicked && action.referenceX > 0 && action.referenceY > 0) {
                    clicked = tapPhysicalPoint(action.referenceX, action.referenceY);
                }
                if (clicked) {
                    markClicked();
                    if ("always_location".equals(action.key)) {
                        handler.postDelayed(this::finishSettingsAndLaunchTms, 1600);
                    }
                }
            } finally {
                actionPending = false;
                if (!clicked) {
                    handler.postDelayed(() -> pendingKey = "", 900);
                }
            }
        }, action.delayMs);
    }

    private void finishSettingsAndLaunchTms() {
        // Lokalizacja jest na osobnej podstronie. Wracamy do listy i do App Info.
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(this::launchTms, 1000);
        }, 900);
    }

    private void launchTms() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TMS_PACKAGE);
        if (launch == null) {
            setFlowMode(MODE_IDLE);
            Toast.makeText(this, "Nie znaleziono aplikacji TMS.", Toast.LENGTH_LONG).show();
            return;
        }
        setFlowMode(MODE_OPEN_TMS);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
    }

    private boolean tapPhysicalPoint(int x, int y) {
        if (!canClickNow()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 80, 180);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean handlePm95SettingsCoordinates(String rawText) {
        String text = normalize(rawText);

        if ((text.contains("informacje o aplikacji") || text.contains("app info"))
                && text.contains("uprawnienia")
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"))) {
            scheduleSettingsTextOrCoordinate(
                    "app_info_permissions",
                    Arrays.asList("Uprawnienia", "Permissions", "Brak przyznanych uprawnień", "Brak przyznanych uprawnien"),
                    185, 1465, 1600, 0);
            return true;
        }

        if ((text.contains("uprawnienia aplikacji") || text.contains("app permissions"))
                && text.contains("lokalizacja")) {
            scheduleSettingsTextOrCoordinate(
                    "permissions_location",
                    Arrays.asList("Lokalizacja", "Location"),
                    154, 1749, 1600, 0);
            return true;
        }

        if ((text.contains("lokalizacja - dostep") || text.contains("location access"))
                && text.contains("zawsze zezwalaj")) {
            scheduleSettingsTextOrCoordinate(
                    "always_allow",
                    Arrays.asList("Zawsze zezwalaj", "Allow all the time", "Always allow"),
                    112, 1145, 1700, 2);
            return true;
        }

        return false;
    }

    private void scheduleSettingsTextOrCoordinate(String stage, List<String> labels,
                                                  int x, int y, long delayMs, int backsAfter) {
        if (settingsCoordinatePending || stage.equals(lastSettingsStage)) return;
        settingsCoordinatePending = true;
        lastSettingsStage = stage;

        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                boolean clicked = current != null && clickVisibleText(current, labels);
                if (!clicked) {
                    clicked = tapPhysicalPointPm95(x, y);
                }

                if (clicked) {
                    markClicked();
                    if (backsAfter > 0) {
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            handler.postDelayed(() -> {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                handler.postDelayed(this::launchTmsFromService, 1000);
                            }, 900);
                        }, 1500);
                    }
                }
            } finally {
                settingsCoordinatePending = false;
                handler.postDelayed(() -> lastSettingsStage = "", 900);
            }
        }, delayMs);
    }

    private void scheduleSettingsCoordinate(String stage, int x, int y,
                                            long delayMs, int backsAfter) {
        if (settingsCoordinatePending || stage.equals(lastSettingsStage)) return;
        settingsCoordinatePending = true;
        lastSettingsStage = stage;

        handler.postDelayed(() -> {
            try {
                tapPhysicalPointPm95(x, y);
                markClicked();

                if (backsAfter > 0) {
                    handler.postDelayed(() -> {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            handler.postDelayed(this::launchTmsFromService, 1000);
                        }, 900);
                    }, 1500);
                }
            } finally {
                settingsCoordinatePending = false;
                handler.postDelayed(() -> lastSettingsStage = "", 1200);
            }
        }, delayMs);
    }

    private boolean tapPhysicalPointPm95(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 80, 180);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private void launchTmsFromService() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("pl.optidata.tms_android_2017");
        if (launch == null) {
            setFlowMode(MODE_IDLE);
            Toast.makeText(this, "Nie znaleziono aplikacji TMS.", Toast.LENGTH_LONG).show();
            return;
        }
        setFlowMode(MODE_OPEN_TMS);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
    }

    private boolean clickVisibleText(AccessibilityNodeInfo root, List<String> labels) {
        if (!canClickNow()) return false;

        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            String wanted = normalize(label);

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String visible = normalize(nodeText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                AccessibilityNodeInfo clickable = smallestClickableParent(node);
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && tapAt(bounds.centerX(), bounds.centerY())) return true;
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
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable() && !bounds.isEmpty()) {
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
                new GestureDescription.StrokeDescription(path, 80, 180);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean isAppInfoScreen(String pkg, String text) {
        return pkg.contains("settings")
                && (text.contains("informacje o aplikacji") || text.contains("app info"))
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"))
                && text.contains("uprawnienia");
    }

    private boolean isPermissionsListScreen(String pkg, String text) {
        return pkg.contains("settings")
                && (text.contains("uprawnienia aplikacji") || text.contains("app permissions")
                || text.contains("dozwolone") || text.contains("niedozwolone"))
                && text.contains("lokaliz");
    }

    private boolean isLocationSettingsScreen(String pkg, String text) {
        return pkg.contains("settings")
                && text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj");
    }

    private boolean isInitialLocationDialog(String text) {
        return text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz")
                || text.contains("dokladna")
                || text.contains("przyblizona"));
    }

    private boolean isUninstallDialog(String pkg, String text) {
        return (text.contains("odinstalowac te aplikacje")
                || text.contains("odinstalować tę aplikację")
                || text.contains("odinstaluj")
                || text.contains("uninstall"))
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"));
    }

    private boolean isInstallerScreen(String pkg, String text) {
        boolean installing = text.contains("zainstaluj")
                || text.contains("instaluj")
                || text.contains("install")
                || text.contains("aktualizuj")
                || text.contains("update");
        boolean finished = text.contains("gotowe")
                || text.contains("done");
        return (installing || finished)
                && !text.contains("odinstaluj")
                && !text.contains("uninstall");
    }

    private boolean isRuntimePermissionDialog(String pkg, String text) {
        boolean controller = pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || pkg.equals("android");
        return controller && (text.contains("nie zezwalaj") || text.contains("dont allow") || text.contains("don't allow"));
    }

    private boolean canClickNow() {
        return System.currentTimeMillis() - lastClickTime >= CLICK_GUARD_MS;
    }

    private void markClicked() {
        lastClickTime = System.currentTimeMillis();
    }

    private String nodeText(AccessibilityNodeInfo node) {
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
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out);
    }

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        actionPending = false;
        pendingKey = "";
        if (MODE_OPEN_TMS.equals(mode)) lastClickTime = 0;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FLOW_MODE, mode).apply();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replace("ą", "a").replace("ć", "c").replace("ę", "e")
                .replace("ł", "l").replace("ń", "n").replace("ó", "o")
                .replace("ś", "s").replace("ż", "z").replace("ź", "z").trim();
    }

    private static final class Action {
        final String key;
        final long delayMs;
        final List<String> labels;
        final int referenceX;
        final int referenceY;

        private Action(String key, long delayMs, List<String> labels, int referenceX, int referenceY) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
        }

        static Action text(String key, long delayMs, List<String> labels) {
            return new Action(key, delayMs, labels, 0, 0);
        }

        static Action textWithFallback(String key, long delayMs, List<String> labels,
                                       int referenceX, int referenceY) {
            return new Action(key, delayMs, labels, referenceX, referenceY);
        }
    }
}
