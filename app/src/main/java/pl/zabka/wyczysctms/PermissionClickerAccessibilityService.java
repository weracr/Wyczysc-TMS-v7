package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final long CLICK_DELAY_MS = 2500;

    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";

    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_REPAIR_TMS = "REPAIR_TMS_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";

    private long lastClickTime = 0;
    private boolean returnedFromSettings = false;
    private boolean openedAppSettingsForMissingPermission = false;

    private final List<String> tmsPackages = Arrays.asList(
            "pl.optidata.tms_android_2017",
            "pl.zabka.tms",
            "pl.zabka.tmsfalcon",
            "com.zabka.tms",
            "com.zabka.tmsfalcon"
    );

    private final List<String> runtimeLocationButtons = Arrays.asList(
            "Podczas używania aplikacji",
            "Podczas uzywania aplikacji",
            "Podczas używania tej aplikacji",
            "Podczas uzywania tej aplikacji",
            "Zezwól tylko podczas używania aplikacji",
            "Zezwol tylko podczas uzywania aplikacji",
            "Zezwalaj tylko podczas używania aplikacji",
            "Zezwalaj tylko podczas uzywania aplikacji",
            "While using the app",
            "While using this app",
            "Allow only while using the app"
    );

    private final List<String> alwaysLocationButtons = Arrays.asList(
            "Zawsze zezwalaj",
            "Zawsze pozwalaj",
            "Zezwalaj cały czas",
            "Zezwalaj caly czas",
            "Zezwalaj zawsze",
            "Allow all the time",
            "Always allow",
            "Allow always"
    );

    private final List<String> allowButtons = Arrays.asList(
            "Zezwól",
            "Zezwol",
            "Zezwalaj",
            "Allow",
            "OK",
            "Ok",
            "Włącz",
            "Wlacz",
            "Włączone",
            "Wlaczone",
            "Kontynuuj",
            "Dalej",
            "Potwierdź",
            "Potwierdz",
            "Zastosuj",
            "Rozumiem"
    );

    private final List<String> installerButtons = Arrays.asList(
            "Zainstaluj",
            "Aktualizuj",
            "Zaktualizuj",
            "Install",
            "Update",
            "Otwórz",
            "Otworz",
            "Open",
            "Gotowe",
            "Done"
    );

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        handler.postDelayed(() -> handleScreen(event), 1500);
        handler.postDelayed(() -> handleScreen(event), 3200);
        handler.postDelayed(() -> handleScreen(event), 5200);
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

        String screenText = normalize(collectText(root) + " " + collectEventText(event));

        if (isAdminPanelText(screenText)) {
            setFlowMode(MODE_IDLE);
            return;
        }

        if (isOwnAppScreen(packageName, screenText)) {
            return;
        }

        if (isDetailsOnlyMode() || isIdleMode()) {
            return;
        }

        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (canHandleUninstall() && isUninstallConfirmationDialog(packageName, screenText)) {
            handleUninstallConfirmation(root);
            return;
        }

        if (canHandleInstall() && isInstallerOrPackageScreen(packageName, screenText)) {
            clickInstallerButtons(root);
            return;
        }

        if (!canHandleTmsPermissions()) {
            return;
        }

        if (isTmsLocationPopup(screenText)) {
            handleTmsLocationPopup(root);
            return;
        }

        if (isAndroidCameraSettingsScreen(packageName, screenText)) {
            handleCameraSettings(root);
            return;
        }

        if (isAndroidLocationSettingsScreen(packageName, screenText)) {
            handleLocationSettings(root);
            return;
        }

        if (isAndroidNotificationSettingsScreen(packageName, screenText)) {
            handleNotificationSettings(root);
            return;
        }

        if (isTmsAppInfoScreen(packageName, screenText)) {
            handleTmsAppInfoScreen(root, screenText);
            return;
        }

        if (isAppPermissionsListScreen(packageName, screenText)) {
            handleAppPermissionsList(root, screenText);
            return;
        }

        if (isRuntimePermissionDialog(packageName, screenText)) {
            clickRuntimePermission(root, screenText);
            return;
        }

        if (isTmsPermissionInfoScreen(screenText)) {
            if (clickTmsPermissionInfoScreen(root, screenText)) {
                return;
            }

            openTmsAppSettingsFromMissingPermission(packageName, screenText);
        }
    }

    private boolean isAdminPanelText(String screenText) {
        if (screenText == null) {
            return false;
        }

        String value = normalize(screenText);

        return value.contains("panel administratora")
                || value.contains("aktywuj administratora urzadzenia")
                || value.contains("aktywuj administratora urządzenia")
                || value.contains("nadaj dostep do wszystkich plikow")
                || value.contains("nadaj dostęp do wszystkich plików")
                || value.contains("nadaj zgode na instalowanie apk")
                || value.contains("nadaj zgodę na instalowanie apk")
                || value.contains("szczegoly tms w ustawieniach")
                || value.contains("szczegóły tms w ustawieniach")
                || value.contains("powrot do ekranu kierowcy")
                || value.contains("powrót do ekranu kierowcy");
    }

    private boolean isOwnAppScreen(String packageName, String screenText) {
        String ownPackage = getPackageName().toLowerCase();

        boolean packageIsOwnApp = packageName != null
                && (packageName.equals(ownPackage)
                || packageName.contains("wyczysctms")
                || packageName.contains("wyczysc"));

        boolean textLooksLikeOwnApp = screenText != null
                && (screenText.contains("wyczysc tms")
                || screenText.contains("wyczyść tms")
                || screenText.contains("panel administratora")
                || screenText.contains("napraw tms")
                || screenText.contains("otworz tms")
                || screenText.contains("otwórz tms")
                || screenText.contains("powrot do ekranu kierowcy")
                || screenText.contains("powrót do ekranu kierowcy")
                || screenText.contains("nadaj zgode na instalowanie apk")
                || screenText.contains("nadaj zgodę na instalowanie apk")
                || screenText.contains("aktywuj administratora urzadzenia")
                || screenText.contains("aktywuj administratora urządzenia"));

        return packageIsOwnApp || textLooksLikeOwnApp;
    }

    private boolean isUninstallConfirmationDialog(String packageName, String screenText) {
        boolean isSystemPackage = packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");

        boolean containsUninstallQuestion = screenText.contains("odinstalowac te aplikacje")
                || screenText.contains("odinstalowac aplikacje")
                || screenText.contains("uninstall this app")
                || screenText.contains("uninstall app");

        return isSystemPackage && containsUninstallQuestion && containsTmsText(screenText);
    }

    private void handleUninstallConfirmation(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        boolean clicked = clickByTextForUninstall(root, "OK")
                || clickByTextForUninstall(root, "Ok")
                || clickByTextForUninstall(root, "Odinstaluj")
                || clickByTextForUninstall(root, "Uninstall");

        if (clicked) {
            markClicked();
        }
    }

    private boolean isTmsLocationPopup(String screenText) {
        boolean containsLocationPopup = screenText.contains("dostep do lokalizacji")
                || screenText.contains("dane lokalizacyjne")
                || screenText.contains("zaktualizuj ustawienia")
                || screenText.contains("aktualizuj ustawienia")
                || screenText.contains("location access")
                || screenText.contains("update settings");

        return containsLocationPopup && containsTmsText(screenText);
    }

    private void handleTmsLocationPopup(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        boolean clicked = clickByText(root, "ZAKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Zaktualizuj ustawienia")
                || clickByText(root, "AKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Aktualizuj ustawienia")
                || clickByText(root, "Ustawienia")
                || clickByText(root, "Update settings")
                || clickByText(root, "Settings");

        if (clicked) {
            returnedFromSettings = false;
            markClicked();
        }
    }

    private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        boolean isSystem = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");

        boolean containsPermission = screenText.contains("zezwol")
                || screenText.contains("zezwalaj")
                || screenText.contains("permission")
                || screenText.contains("allow")
                || screenText.contains("podczas uzywania")
                || screenText.contains("while using")
                || screenText.contains("lokalizacja")
                || screenText.contains("location")
                || screenText.contains("aparat")
                || screenText.contains("camera")
                || screenText.contains("kontakty")
                || screenText.contains("contacts")
                || screenText.contains("phone")
                || screenText.contains("zdjec")
                || screenText.contains("photos")
                || screenText.contains("nearby devices");

        return isSystem && containsPermission && containsTmsText(screenText);
    }

    private void clickRuntimePermission(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return;
        }

        if (screenText.contains("lokalizacja")
                || screenText.contains("location")
                || screenText.contains("aparat")
                || screenText.contains("camera")) {
            if (clickAnyText(root, runtimeLocationButtons)) {
                markClicked();
                return;
            }
        }

        if (clickAnyText(root, allowButtons)) {
            markClicked();
        }
    }

    private boolean isTmsPermissionInfoScreen(String screenText) {
        boolean containsPermissionProblem = screenText.contains("cannot use this application without requested permission")
                || screenText.contains("requested permission")
                || screenText.contains("without requested permission")
                || screenText.contains("permission")
                || screenText.contains("lokalizacja")
                || screenText.contains("location");

        return containsTmsText(screenText) && containsPermissionProblem;
    }

    private boolean clickTmsPermissionInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return false;
        }

        boolean clicked = clickByText(root, "ZAKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Zaktualizuj ustawienia")
                || clickByText(root, "AKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Aktualizuj ustawienia")
                || clickByText(root, "Ustawienia")
                || clickByText(root, "Settings");

        if (clicked) {
            returnedFromSettings = false;
            markClicked();
            return true;
        }

        return false;
    }

    private void openTmsAppSettingsFromMissingPermission(String currentPackageName, String screenText) {
        if (openedAppSettingsForMissingPermission) {
            return;
        }

        if (!containsTmsText(screenText) || !screenText.contains("permission")) {
            return;
        }

        String tmsPackage = resolveTmsPackage(currentPackageName);

        if (tmsPackage == null) {
            return;
        }

        openedAppSettingsForMissingPermission = true;
        returnedFromSettings = false;

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + tmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            markClicked();
        } catch (Exception ignored) {
        }
    }

    private String resolveTmsPackage(String currentPackageName) {
        if (currentPackageName != null) {
            String pkg = currentPackageName.toLowerCase();
            if (pkg.contains("tms") || pkg.contains("falcon") || pkg.contains("zabka")) {
                return currentPackageName;
            }
        }

        PackageManager pm = getPackageManager();
        for (String pkg : tmsPackages) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    return pkg;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean isAndroidCameraSettingsScreen(String packageName, String screenText) {
        boolean isSettings = packageName.contains("settings") || packageName.contains("permissioncontroller");
        boolean hasCamera = screenText.contains("aparat")
                || screenText.contains("camera")
                || screenText.contains("robienie zdjec")
                || screenText.contains("nagrywanie filmow")
                || screenText.contains("take pictures")
                || screenText.contains("record video");
        return isSettings && hasCamera && containsTmsText(screenText);
    }

    private void handleCameraSettings(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        boolean clicked = clickAnyText(root, runtimeLocationButtons) || clickAnyText(root, allowButtons);

        if (clicked) {
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 2500);
        }
    }

    private boolean isAndroidLocationSettingsScreen(String packageName, String screenText) {
        boolean isSettings = packageName.contains("settings");
        boolean hasLocation = screenText.contains("lokalizacja")
                || screenText.contains("location")
                || screenText.contains("zawsze zezwalaj")
                || screenText.contains("zezwalaj caly czas")
                || screenText.contains("allow all the time")
                || screenText.contains("while using")
                || screenText.contains("precise location")
                || screenText.contains("uzywaj dokladnej lokalizacji");
        return isSettings && hasLocation && containsTmsText(screenText);
    }

    private void handleLocationSettings(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        String screenText = normalize(collectText(root));
        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 2500);
            return;
        }

        if (clickAnyText(root, alwaysLocationButtons)) {
            markClicked();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                if (currentRoot != null) {
                    enablePreciseLocationIfVisible(currentRoot);
                }
            }, 900);
            handler.postDelayed(this::goBackAndOpenTms, 1800);
            return;
        }

        enablePreciseLocationIfVisible(root);
    }

    private boolean isAlwaysLocationAlreadyChecked(AccessibilityNodeInfo root) {
        return isTextOptionChecked(root, "Zawsze zezwalaj")
                || isTextOptionChecked(root, "Zezwalaj cały czas")
                || isTextOptionChecked(root, "Zezwalaj caly czas")
                || isTextOptionChecked(root, "Zezwalaj zawsze")
                || isTextOptionChecked(root, "Allow all the time")
                || isTextOptionChecked(root, "Always allow");
    }

    private boolean isAndroidNotificationSettingsScreen(String packageName, String screenText) {
        boolean isSettings = packageName.contains("settings") || packageName.contains("permissioncontroller");
        boolean hasNotification = screenText.contains("powiadomienia")
                || screenText.contains("notifications")
                || screenText.contains("zezwalaj na powiadomienia")
                || screenText.contains("allow notifications");
        return isSettings && hasNotification && containsTmsText(screenText);
    }

    private void handleNotificationSettings(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        String screenText = normalize(collectText(root));
        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (isNotificationAlreadyEnabled(root)) {
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 2500);
            return;
        }

        if (clickSwitchNearText(root, "Powiadomienia")
                || clickSwitchNearText(root, "Zezwalaj na powiadomienia")
                || clickSwitchNearText(root, "Notifications")
                || clickSwitchNearText(root, "Allow notifications")) {
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 2500);
            return;
        }

        if (clickAnyText(root, allowButtons)) {
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 2500);
        }
    }

    private boolean isNotificationAlreadyEnabled(AccessibilityNodeInfo root) {
        return isTextOptionChecked(root, "Powiadomienia")
                || isTextOptionChecked(root, "Zezwalaj na powiadomienia")
                || isTextOptionChecked(root, "Notifications")
                || isTextOptionChecked(root, "Allow notifications");
    }

    private boolean isTmsAppInfoScreen(String packageName, String screenText) {
        boolean isSettings = packageName.contains("settings");
        boolean appInfo = screenText.contains("o aplikacji")
                || screenText.contains("informacje o aplikacji")
                || screenText.contains("app info")
                || screenText.contains("uprawnienia")
                || screenText.contains("permissions");
        return isSettings && containsTmsText(screenText) && appInfo;
    }

    private void handleTmsAppInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow() || isBlockedAdminScreen(screenText)) {
            return;
        }
        if (clickByText(root, "Uprawnienia") || clickByText(root, "Permissions") || clickByText(root, "Zezwolenia")) {
            markClicked();
        }
    }

    private boolean isAppPermissionsListScreen(String packageName, String screenText) {
        boolean isSettings = packageName.contains("settings");
        boolean hasList = screenText.contains("uprawnienia aplikacji")
                || screenText.contains("app permissions")
                || screenText.contains("maja dostep")
                || screenText.contains("nie maja dostepu")
                || screenText.contains("allowed")
                || screenText.contains("not allowed");
        return isSettings && hasList && containsTmsText(screenText);
    }

    private void handleAppPermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow() || isBlockedAdminScreen(screenText)) {
            return;
        }

        if (isPermissionInDeniedSection(screenText, "aparat") || isPermissionInDeniedSection(screenText, "camera")) {
            if (tapPermissionRowByText(root, "Aparat") || tapPermissionRowByText(root, "Camera")) {
                markClicked();
                return;
            }
        }

        if (isPermissionInDeniedSection(screenText, "powiadomienia") || isPermissionInDeniedSection(screenText, "notifications")) {
            if (tapPermissionRowByText(root, "Powiadomienia") || tapPermissionRowByText(root, "Notifications")) {
                markClicked();
                return;
            }
        }

        if (isPermissionInDeniedSection(screenText, "lokalizacja") || isPermissionInDeniedSection(screenText, "location")) {
            if (tapPermissionRowByText(root, "Lokalizacja") || tapPermissionRowByText(root, "Location")) {
                returnedFromSettings = false;
                markClicked();
            }
        }
    }

    private boolean tapPermissionRowByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        String wanted = normalize(text);

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }

            String nodeText = normalize(getNodeVisibleText(node));
            if (!nodeText.equals(wanted) && !nodeText.contains(wanted)) {
                continue;
            }

            Rect textBounds = new Rect();
            node.getBoundsInScreen(textBounds);
            if (textBounds.isEmpty()) {
                continue;
            }

            int tapX = textBounds.centerX();
            int tapY = textBounds.centerY();
            return tapAt(tapX, tapY);
        }

        return false;
    }

    private boolean tapAt(int x, int y) {
        if (x <= 0 || y <= 0) {
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPermissionInDeniedSection(String screenText, String permissionName) {
        if (screenText == null || permissionName == null) {
            return false;
        }

        String text = normalize(screenText);
        String permission = normalize(permissionName);
        int deniedIndex = text.indexOf("nie maja dostepu");
        if (deniedIndex < 0) {
            deniedIndex = text.indexOf("not allowed");
        }
        if (deniedIndex < 0) {
            return false;
        }
        int permissionIndex = text.indexOf(permission, deniedIndex);
        return permissionIndex > deniedIndex;
    }

    private boolean isInstallerOrPackageScreen(String packageName, String screenText) {
        boolean installer = packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller")
                || packageName.contains("files")
                || packageName.contains("documentsui");

        boolean ok = screenText.contains("zainstaluj")
                || screenText.contains("aktualizuj")
                || screenText.contains("install")
                || screenText.contains("update")
                || screenText.contains("otworz")
                || screenText.contains("open")
                || screenText.contains("gotowe")
                || screenText.contains("done");

        boolean danger = screenText.contains("odinstaluj")
                || screenText.contains("uninstall")
                || screenText.contains("dezaktywuj")
                || screenText.contains("deactivate")
                || screenText.contains("wyczysc dane")
                || screenText.contains("clear data");

        return installer && ok && !danger;
    }

    private void clickInstallerButtons(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }
        if (clickAnyText(root, installerButtons)) {
            markClicked();
        }
    }

    private boolean isTextOptionChecked(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo current = node;
            for (int i = 0; i < 5 && current != null; i++) {
                if (containsCheckedNode(current)) {
                    return true;
                }
                current = current.getParent();
            }
        }
        return false;
    }

    private boolean containsCheckedNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isChecked()) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsCheckedNode(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private void enablePreciseLocationIfVisible(AccessibilityNodeInfo root) {
        String screenText = normalize(collectText(root));
        if (screenText.contains("uzywaj dokladnej lokalizacji") || screenText.contains("precise location")) {
            if (isTextOptionChecked(root, "Używaj dokładnej lokalizacji")
                    || isTextOptionChecked(root, "Uzywaj dokladnej lokalizacji")
                    || isTextOptionChecked(root, "Precise location")) {
                return;
            }
            if (clickSwitchNearText(root, "Używaj dokładnej lokalizacji")
                    || clickSwitchNearText(root, "Uzywaj dokladnej lokalizacji")
                    || clickSwitchNearText(root, "Precise location")) {
                markClicked();
            }
        }
    }

    private void goBackAndOpenTms() {
        if (returnedFromSettings) {
            return;
        }
        returnedFromSettings = true;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(this::openTmsApp, 2200);
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
            returnedFromSettings = false;
            openedAppSettingsForMissingPermission = false;
            if (isMode(MODE_OPEN_TMS)) {
                setFlowMode(MODE_IDLE);
            }
        }, 6000);
    }

    private boolean clickAnyText(AccessibilityNodeInfo root, List<String> texts) {
        if (root == null || texts == null) {
            return false;
        }
        for (String text : texts) {
            if (clickByText(root, text)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }
            String nodeText = normalize(getNodeVisibleText(node));
            if (isDangerousText(nodeText)) {
                continue;
            }

            boolean exact = nodeText.equals(wanted);
            boolean contains = wanted.length() >= 8 && nodeText.contains(wanted) && !isDangerousText(nodeText);
            if (!exact && !contains) {
                continue;
            }

            AccessibilityNodeInfo clickableNode = findClickableParentSafe(node);
            if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickByTextForUninstall(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) {
                continue;
            }
            String nodeText = normalize(getNodeVisibleText(node));
            if (!nodeText.equals(wanted) && !nodeText.contains(wanted)) {
                continue;
            }
            AccessibilityNodeInfo clickableNode = findClickableParentForUninstall(node);
            if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableParentSafe(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            String fullText = normalize(collectText(current));
            if (isBlockedAdminScreen(fullText) || isDangerousText(fullText)) {
                return null;
            }
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableParentForUninstall(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            String fullText = normalize(collectText(current));
            if (isBlockedAdminScreen(fullText)) {
                return null;
            }
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
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
            for (int i = 0; i < 5 && parent != null; i++) {
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
        String wholeNodeText = normalize(collectText(node));
        if (isBlockedAdminScreen(wholeNodeText) || isDangerousText(wholeNodeText)) {
            return false;
        }
        CharSequence className = node.getClassName();
        if (className != null) {
            String cls = className.toString().toLowerCase();
            if ((cls.contains("switch") || cls.contains("checkbox"))
                    && node.isEnabled()
                    && node.isClickable()
                    && !node.isChecked()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        if (node.isClickable() && node.isEnabled()) {
            String nodeText = normalize(getNodeVisibleText(node));
            if (nodeText.contains("doklad") || nodeText.contains("precise") || nodeText.contains("powiadom") || nodeText.contains("notification")) {
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

    private String getNodeVisibleText(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null) {
            builder.append(text).append(" ");
        }
        if (description != null) {
            builder.append(description).append(" ");
        }
        return builder.toString().trim();
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

    private String collectEventText(AccessibilityEvent event) {
        if (event == null || event.getText() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence text : event.getText()) {
            if (text != null) {
                builder.append(text).append(" ");
            }
        }
        CharSequence description = event.getContentDescription();
        if (description != null) {
            builder.append(description).append(" ");
        }
        return builder.toString();
    }

    private boolean isBlockedAdminScreen(String text) {
        String value = normalize(text);
        return value.contains("administratorzy urzadzenia")
                || value.contains("aplikacje administratora urzadzenia")
                || value.contains("administrator urzadzenia")
                || value.contains("device admin")
                || value.contains("device administrator")
                || value.contains("admin apps")
                || value.contains("aktywuj tego administratora")
                || value.contains("aktywowac tego administratora")
                || value.contains("dezaktywuj tego administratora")
                || value.contains("deactivate this device admin");
    }

    private boolean isDangerousText(String text) {
        String value = normalize(text);
        return value.contains("odinstaluj")
                || value.contains("uninstall")
                || value.contains("usun")
                || value.contains("delete")
                || value.contains("wyczysc dane")
                || value.contains("clear data")
                || value.contains("wyczysc miejsce")
                || value.contains("clear storage")
                || value.contains("resetuj")
                || value.contains("dezaktywuj")
                || value.contains("deactivate");
    }

    private boolean containsTmsText(String text) {
        String value = normalize(text);
        return value.contains("zabka")
                || value.contains("tms")
                || value.contains("tmsfalcon")
                || value.contains("falcon");
    }

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FLOW_MODE, mode).apply();
    }

    private boolean isMode(String expectedMode) {
        return expectedMode.equals(getFlowMode());
    }

    private boolean canHandleUninstall() {
        return isMode(MODE_UNINSTALL_TMS) || isMode(MODE_REPAIR_TMS);
    }

    private boolean canHandleInstall() {
        return isMode(MODE_INSTALL_TMS) || isMode(MODE_REPAIR_TMS);
    }

    private boolean canHandleTmsPermissions() {
        return isMode(MODE_OPEN_TMS) || isMode(MODE_REPAIR_TMS);
    }

    private boolean isDetailsOnlyMode() {
        return isMode(MODE_DETAILS_ONLY);
    }

    private boolean isIdleMode() {
        return isMode(MODE_IDLE);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .toLowerCase()
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
