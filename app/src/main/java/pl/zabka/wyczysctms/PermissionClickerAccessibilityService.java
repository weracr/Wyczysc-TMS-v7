package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastClickTime = 0;
    private static final long CLICK_DELAY_MS = 900;

    private boolean locationSettingsFlowStarted = false;
    private boolean locationAlwaysClicked = false;
    private boolean returnedFromSettings = false;

    private final List<String> tmsPackages = Arrays.asList(
            "pl.optidata.tms_android_2017",
            "pl.zabka.tms",
            "pl.zabka.tmsfalcon",
            "com.zabka.tms",
            "com.zabka.tmsfalcon"
    );

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        try {
            handler.postDelayed(() -> handleScreen(event), 250);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void handleScreen(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        String packageName = "";

        if (event.getPackageName() != null) {
            packageName = event.getPackageName().toString().toLowerCase();
        }

        String screenText = collectText(root).toLowerCase();

        if (isRuntimePermissionDialog(packageName, screenText)) {
            clickRuntimePermission(root);
            return;
        }

        if (isTmsLocationInfoScreen(screenText)) {
            clickUpdateSettings(root);
            return;
        }

        if (isAndroidLocationSettingsScreen(packageName, screenText)) {
            handleLocationSettings(root);
            return;
        }
    }

    private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        boolean isAndroidPermissionPackage =
                packageName.contains("permissioncontroller")
                        || packageName.contains("packageinstaller")
                        || packageName.contains("settings");

        boolean containsPermissionText =
                screenText.contains("zezwolić")
                        || screenText.contains("zezwolic")
                        || screenText.contains("uprawnienie")
                        || screenText.contains("permission")
                        || screenText.contains("allow")
                        || screenText.contains("podczas używania")
                        || screenText.contains("while using");

        boolean containsTmsText =
                screenText.contains("zabka")
                        || screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("falcon");

        return isAndroidPermissionPackage && containsPermissionText && containsTmsText;
    }

    private void clickRuntimePermission(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        if (clickByText(root, "Podczas używania aplikacji")) {
            markClicked();
            return;
        }

        if (clickByText(root, "Podczas używania tej aplikacji")) {
            markClicked();
            return;
        }

        if (clickByText(root, "While using the app")) {
            markClicked();
            return;
        }

        if (clickByText(root, "While using this app")) {
            markClicked();
            return;
        }

        if (clickByText(root, "Zezwól")) {
            markClicked();
            return;
        }

        if (clickByText(root, "Zezwalaj")) {
            markClicked();
            return;
        }

        if (clickByText(root, "Allow")) {
            markClicked();
        }
    }

    private boolean isTmsLocationInfoScreen(String screenText) {
        return screenText.contains("dostęp do lokalizacji")
                && screenText.contains("tmsfalcon")
                && (
                        screenText.contains("zaktualizuj ustawienia")
                                || screenText.contains("ustawienia")
                );
    }

    private void clickUpdateSettings(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        if (clickByText(root, "ZAKTUALIZUJ USTAWIENIA")) {
            locationSettingsFlowStarted = true;
            locationAlwaysClicked = false;
            returnedFromSettings = false;
            markClicked();
            return;
        }

        if (clickByText(root, "Zaktualizuj ustawienia")) {
            locationSettingsFlowStarted = true;
            locationAlwaysClicked = false;
            returnedFromSettings = false;
            markClicked();
        }
    }

    private boolean isAndroidLocationSettingsScreen(String packageName, String screenText) {
        boolean isSettingsPackage = packageName.contains("settings");

        boolean containsLocationSettings =
                screenText.contains("lokalizacja")
                        && (
                                screenText.contains("zawsze zezwalaj")
                                        || screenText.contains("zezwalaj tylko podczas używania aplikacji")
                                        || screenText.contains("zawsze pytaj")
                                        || screenText.contains("nie zezwalaj")
                                        || screenText.contains("używaj dokładnej lokalizacji")
                                        || screenText.contains("uzywaj dokladnej lokalizacji")
                        );

        boolean containsTms =
                screenText.contains("zabka")
                        || screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("falcon");

        return isSettingsPackage && containsLocationSettings && containsTms;
    }

    private void handleLocationSettings(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        if (!locationAlwaysClicked) {
            if (clickByText(root, "Zawsze zezwalaj")) {
                locationAlwaysClicked = true;
                markClicked();

                handler.postDelayed(() -> {
                    AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                    if (currentRoot != null) {
                        enablePreciseLocationIfVisible(currentRoot);
                    }
                }, 700);

                handler.postDelayed(this::goBackAndOpenTms, 1500);
                return;
            }
        }

        enablePreciseLocationIfVisible(root);

        if (locationSettingsFlowStarted && !returnedFromSettings) {
            handler.postDelayed(this::goBackAndOpenTms, 1200);
        }
    }

    private void enablePreciseLocationIfVisible(AccessibilityNodeInfo root) {
        String screenText = collectText(root).toLowerCase();

        if (
                screenText.contains("używaj dokładnej lokalizacji")
                        || screenText.contains("uzywaj dokladnej lokalizacji")
                        || screenText.contains("precise location")
        ) {
            clickSwitchNearText(root, "Używaj dokładnej lokalizacji");
            clickSwitchNearText(root, "Uzywaj dokladnej lokalizacji");
            clickSwitchNearText(root, "Precise location");
        }
    }

    private void goBackAndOpenTms() {
        returnedFromSettings = true;

        performGlobalAction(GLOBAL_ACTION_BACK);

        handler.postDelayed(this::openTmsApp, 800);
    }

    private void openTmsApp() {
        PackageManager pm = getPackageManager();

        for (String pkg : tmsPackages) {
            try {
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);

                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    resetFlowFlagsLater();
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        resetFlowFlagsLater();
    }

    private void resetFlowFlagsLater() {
        handler.postDelayed(() -> {
            locationSettingsFlowStarted = false;
            locationAlwaysClicked = false;
            returnedFromSettings = false;
        }, 3000);
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);

        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }

            AccessibilityNodeInfo clickableNode = findClickableParent(node);

            if (clickableNode != null) {
                boolean clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);

                if (clicked) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean clickSwitchNearText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);

        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo parent = node;

            for (int i = 0; i < 4 && parent != null; i++) {
                if (clickFirstSwitchOrClickableChild(parent)) {
                    return true;
                }

                parent = parent.getParent();
            }
        }

        return false;
    }

    private boolean clickFirstSwitchOrClickableChild(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }

        CharSequence className = node.getClassName();

        if (className != null) {
            String cls = className.toString().toLowerCase();

            if (
                    cls.contains("switch")
                            && node.isEnabled()
                            && node.isClickable()
                            && !node.isChecked()
            ) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        if (node.isClickable() && node.isEnabled()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();

            String nodeText = "";

            if (text != null) {
                nodeText += text.toString().toLowerCase();
            }

            if (desc != null) {
                nodeText += " " + desc.toString().toLowerCase();
            }

            if (
                    nodeText.contains("dokład")
                            || nodeText.contains("doklad")
                            || nodeText.contains("precise")
            ) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickFirstSwitchOrClickableChild(node.getChild(i))) {
                return true;
            }
        }

        return false;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;

        while (current != null) {
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }

            current = current.getParent();
        }

        return null;
    }

    private boolean canClickNow() {
        long now = System.currentTimeMillis();
        return now - lastClickTime >= CLICK_DELAY_MS;
    }

    private void markClicked() {
        lastClickTime = System.currentTimeMillis();
    }

    private String collectText(AccessibilityNodeInfo node) {
        StringBuilder builder = new StringBuilder();
        collectTextRecursive(node, builder);
        return builder.toString();
    }

    private void collectTextRecursive(AccessibilityNodeInfo node, StringBuilder builder) {
        if (node == null) {
            return;
        }

        CharSequence text = node.getText();

        if (text != null) {
            builder.append(text).append(" ");
        }

        CharSequence description = node.getContentDescription();

        if (description != null) {
            builder.append(description).append(" ");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectTextRecursive(node.getChild(i), builder);
        }
    }
}
