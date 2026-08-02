package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
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
            clickFirst(root, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));
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
                clickVisibleText(root, Arrays.asList(
                        "Podczas używania aplikacji",
                        "Podczas uzywania aplikacji",
                        "While using the app"
                ));
            } else {
                clickVisibleText(root, Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow"));
            }
        }
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
            finalBackScheduled = false;
            Toast.makeText(this, "Gotowe. Uprawnienia TMS zostały nadane.", Toast.LENGTH_LONG).show();
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
