package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    private long lastClickTime = 0;
    private static final long CLICK_DELAY_MS = 750;

    private boolean locationSettingsFlowStarted = false;
    private boolean notificationSettingsFlowStarted = false;
    private boolean returnedFromSettings = false;
    private boolean openedAppSettingsForMissingPermission = false;

    private final List<String> tmsPackages = Arrays.asList(
            "pl.optidata.tms_android_2017",
            "pl.zabka.tms",
            "pl.zabka.tmsfalcon",
            "com.zabka.tms",
            "com.zabka.tmsfalcon"
    );

    private final List<String> positiveButtons = Arrays.asList(
            "Zezwól",
            "Zezwol",
            "Zezwalaj",
            "Zezwól tylko podczas używania aplikacji",
            "Zezwol tylko podczas uzywania aplikacji",
            "Zezwalaj tylko podczas używania aplikacji",
            "Zezwalaj tylko podczas uzywania aplikacji",
            "Podczas używania aplikacji",
            "Podczas uzywania aplikacji",
            "Podczas używania tej aplikacji",
            "Podczas uzywania tej aplikacji",
            "Allow",
            "Allow only while using the app",
            "While using the app",
            "While using this app",
            "OK",
            "Ok",
            "Dalej",
            "Kontynuuj",
            "Potwierdź",
            "Potwierdz",
            "Włącz",
            "Wlacz",
            "Włączone",
            "Wlaczone",
            "Zastosuj",
            "Aktualizuj",
            "Zaktualizuj",
            "Zainstaluj",
            "Otwórz",
            "Otworz",
            "Gotowe",
            "Rozumiem"
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

    private final List<String> notificationButtons = Arrays.asList(
            "Zezwól",
            "Zezwol",
            "Zezwalaj",
            "Allow",
            "Włącz",
            "Wlacz",
            "Włączone",
            "Wlaczone",
            "Zezwalaj na powiadomienia",
            "Allow notifications"
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

        handler.postDelayed(() -> handleScreen(event), 200);
        handler.postDelayed(() -> handleScreen(event), 700);
        handler.postDelayed(() -> handleScreen(event), 1300);
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

        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (isTmsLocationPopup(screenText)) {
            handleTmsLocationPopup(root);
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
            return;
        }

        if (isInstallerOrPackageScreen(packageName, screenText)) {
            clickInstallerButtons(root);
        }
    }

    private boolean isTmsLocationPopup(String screenText) {
        boolean containsLocationPopup =
                screenText.contains("dostep do lokalizacji")
                        || screenText.contains("dane lokalizacyjne")
                        || screenText.contains("zaktualizuj ustawienia")
                        || screenText.contains("aktualizuj ustawienia")
                        || screenText.contains("location access")
                        || screenText.contains("update settings");

        boolean containsTms =
                screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("zabka")
                        || screenText.contains("falcon");

        return containsLocationPopup && containsTms;
    }

    private void handleTmsLocationPopup(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        boolean clicked =
                clickByText(root, "ZAKTUALIZUJ USTAWIENIA")
                        || clickByText(root, "Zaktualizuj ustawienia")
                        || clickByText(root, "AKTUALIZUJ USTAWIENIA")
                        || clickByText(root, "Aktualizuj ustawienia")
                        || clickByText(root, "Ustawienia")
                        || clickByText(root, "Update settings")
                        || clickByText(root, "Settings");

        if (clicked) {
            locationSettingsFlowStarted = true;
            notificationSettingsFlowStarted = false;
            returnedFromSettings = false;
            markClicked();
        }
    }

    private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        boolean isAndroidPermissionPackage =
                packageName.contains("permissioncontroller")
                        || packageName.contains("packageinstaller")
                        || packageName.contains("android");

        boolean containsPermissionText =
                screenText.contains("zezwolic")
                        || screenText.contains("zezwol")
                        || screenText.contains("zezwalaj")
                        || screenText.contains("uprawnienie")
                        || screenText.contains("uprawnienia")
                        || screenText.contains("permission")
                        || screenText.contains("permissions")
                        || screenText.contains("allow")
                        || screenText.contains("podczas uzywania")
                        || screenText.contains("while using")
                        || screenText.contains("powiadomienia")
                        || screenText.contains("notifications")
                        || screenText.contains("lokalizacja")
                        || screenText.contains("location");

        boolean containsTmsText =
                screenText.contains("zabka")
                        || screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("falcon");

        return isAndroidPermissionPackage && containsPermissionText && containsTmsText;
    }

    private void clickRuntimePermission(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return;
        }

        if (screenText.contains("lokalizacja") || screenText.contains("location")) {
            if (clickAnyText(root, runtimeLocationButtons)) {
                markClicked();
                return;
            }
        }

        if (screenText.contains("powiadomienia") || screenText.contains("notifications")) {
            if (clickAnyText(root, notificationButtons)) {
                markClicked();
                return;
            }
        }

        if (clickAnyText(root, positiveButtons)) {
            markClicked();
        }
    }

    private boolean isTmsPermissionInfoScreen(String screenText) {
        boolean containsTms =
                screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("zabka")
                        || screenText.contains("falcon");

        boolean containsPermissionProblem =
                screenText.contains("cannot use this application without requested permission")
                        || screenText.contains("requested permission")
                        || screenText.contains("without requested permission")
                        || screenText.contains("brak uprawnien")
                        || screenText.contains("uprawnienia")
                        || screenText.contains("permission")
                        || screenText.contains("lokalizacja")
                        || screenText.contains("powiadomienia")
                        || screenText.contains("notifications")
                        || screenText.contains("location");

        return containsTms && containsPermissionProblem;
    }

    private boolean clickTmsPermissionInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return false;
        }

        boolean clicked =
                clickByText(root, "ZAKTUALIZUJ USTAWIENIA")
                        || clickByText(root, "Zaktualizuj ustawienia")
                        || clickByText(root, "AKTUALIZUJ USTAWIENIA")
                        || clickByText(root, "Aktualizuj ustawienia")
                        || clickByText(root, "Ustawienia")
                        || clickByText(root, "Settings");

        if (clicked) {
            if (screenText.contains("powiadomienia") || screenText.contains("notifications")) {
                notificationSettingsFlowStarted = true;
                locationSettingsFlowStarted = false;
            } else {
                locationSettingsFlowStarted = true;
                notificationSettingsFlowStarted = false;
            }

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

        boolean isTmsScreen =
                screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("zabka")
                        || screenText.contains("falcon");

        boolean hasPermissionError =
                screenText.contains("cannot use this application without requested permission")
                        || screenText.contains("requested permission")
                        || screenText.contains("without requested permission")
                        || screenText.contains("brak uprawnien")
                        || screenText.contains("permission");

        if (!isTmsScreen || !hasPermissionError) {
            return;
        }

        String tmsPackage = resolveTmsPackage(currentPackageName);

        if (tmsPackage == null) {
            return;
        }

        openedAppSettingsForMissingPermission = true;
        locationSettingsFlowStarted = true;
        notificationSettingsFlowStarted = false;
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

    private boolean isAndroidLocationSettingsScreen(String packageName, String screenText) {
        boolean isSettingsPackage = packageName.contains("settings");

        boolean containsLocationSettings =
                screenText.contains("lokalizacja")
                        || screenText.contains("location")
                        || screenText.contains("lokalizacja dostep")
                        || screenText.contains("zawsze zezwalaj")
                        || screenText.contains("zezwalaj caly czas")
                        || screenText.contains("zezwalaj tylko podczas uzywania aplikacji")
                        || screenText.contains("zawsze pytaj")
                        || screenText.contains("nie zezwalaj")
                        || screenText.contains("allow all the time")
                        || screenText.contains("while using the app")
                        || screenText.contains("uzywaj dokladnej lokalizacji")
                        || screenText.contains("precise location");

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

        String screenText = normalize(collectText(root));

        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 900);
            return;
        }

        boolean clicked = clickAnyText(root, alwaysLocationButtons);

        if (clicked) {
            markClicked();

            handler.postDelayed(() -> {
                AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                if (currentRoot != null) {
                    enablePreciseLocationIfVisible(currentRoot);
                }
            }, 500);

            handler.postDelayed(this::goBackAndOpenTms, 1200);
            return;
        }

        enablePreciseLocationIfVisible(root);

        if ((locationSettingsFlowStarted || notificationSettingsFlowStarted) && !returnedFromSettings) {
            handler.postDelayed(this::goBackAndOpenTms, 1200);
        }
    }

    private boolean isAlwaysLocationAlreadyChecked(AccessibilityNodeInfo root) {
        return isTextOptionChecked(root, "Zawsze zezwalaj")
                || isTextOptionChecked(root, "Zezwalaj cały czas")
                || isTextOptionChecked(root, "Zezwalaj caly czas")
                || isTextOptionChecked(root, "Zezwalaj zawsze")
                || isTextOptionChecked(root, "Allow all the time")
                || isTextOptionChecked(root, "Always allow");
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
            if (node == null) {
                continue;
            }

            AccessibilityNodeInfo current = node;

            for (int i = 0; i < 5 && current != null; i++) {
                if (containsCheckedRadioOrCheckBox(current)) {
                    return true;
                }

                current = current.getParent();
            }
        }

        return false;
    }

    private boolean containsCheckedRadioOrCheckBox(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }

        CharSequence className = node.getClassName();

        if (className != null) {
            String cls = className.toString().toLowerCase();

            if ((cls.contains("radio") || cls.contains("checkbox") || cls.contains("switch"))
                    && node.isChecked()) {
                return true;
            }
        }

        if (node.isChecked()) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsCheckedRadioOrCheckBox(node.getChild(i))) {
                return true;
            }
        }

        return false;
    }

    private boolean isAndroidNotificationSettingsScreen(String packageName, String screenText) {
        boolean isSettingsPackage =
                packageName.contains("settings")
                        || packageName.contains("permissioncontroller");

        boolean containsNotificationSettings =
                screenText.contains("powiadomienia")
                        || screenText.contains("notifications")
                        || screenText.contains("zezwalaj na powiadomienia")
                        || screenText.contains("allow notifications")
                        || screenText.contains("wszystkie powiadomienia")
                        || screenText.contains("all notifications");

        boolean containsTms =
                screenText.contains("zabka")
                        || screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("falcon");

        return isSettingsPackage && containsNotificationSettings && containsTms;
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
            handler.postDelayed(this::goBackAndOpenTms, 900);
            return;
        }

        if (clickSwitchNearText(root, "Powiadomienia")
                || clickSwitchNearText(root, "Zezwalaj na powiadomienia")
                || clickSwitchNearText(root, "Notifications")
                || clickSwitchNearText(root, "Allow notifications")) {

            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 1000);
            return;
        }

        if (clickAnyText(root, notificationButtons)) {
            markClicked();
            handler.postDelayed(this::goBackAndOpenTms, 1000);
        }
    }

    private boolean isNotificationAlreadyEnabled(AccessibilityNodeInfo root) {
        return isTextOptionChecked(root, "Powiadomienia")
                || isTextOptionChecked(root, "Zezwalaj na powiadomienia")
                || isTextOptionChecked(root, "Notifications")
                || isTextOptionChecked(root, "Allow notifications");
    }

    private boolean isTmsAppInfoScreen(String packageName, String screenText) {
        boolean isSettingsPackage = packageName.contains("settings");

        boolean containsTms =
                screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("zabka")
                        || screenText.contains("falcon");

        boolean containsAppInfo =
                screenText.contains("o aplikacji")
                        || screenText.contains("informacje o aplikacji")
                        || screenText.contains("app info")
                        || screenText.contains("uprawnienia")
                        || screenText.contains("permissions")
                        || screenText.contains("powiadomienia")
                        || screenText.contains("notifications")
                        || screenText.contains("pamiec")
                        || screenText.contains("storage");

        return isSettingsPackage && containsTms && containsAppInfo;
    }

    private void handleTmsAppInfoScreen(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return;
        }

        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        boolean clicked =
                clickByText(root, "Uprawnienia")
                        || clickByText(root, "Permissions")
                        || clickByText(root, "Zezwolenia");

        if (clicked) {
            markClicked();
        }
    }

    private boolean isAppPermissionsListScreen(String packageName, String screenText) {
        boolean isSettingsPackage = packageName.contains("settings");

        boolean containsPermissionList =
                screenText.contains("uprawnienia aplikacji")
                        || screenText.contains("app permissions")
                        || screenText.contains("maja dostep")
                        || screenText.contains("nie maja dostepu")
                        || screenText.contains("allowed")
                        || screenText.contains("not allowed");

        boolean containsTms =
                screenText.contains("zabka")
                        || screenText.contains("tms")
                        || screenText.contains("tmsfalcon")
                        || screenText.contains("falcon");

        return isSettingsPackage && containsPermissionList && containsTms;
    }

    private void handleAppPermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) {
            return;
        }

        if (isBlockedAdminScreen(screenText)) {
            return;
        }

        if (screenText.contains("lokalizacja") || screenText.contains("location")) {
            if (clickByText(root, "Lokalizacja") || clickByText(root, "Location")) {
                locationSettingsFlowStarted = true;
                notificationSettingsFlowStarted = false;
                returnedFromSettings = false;
                markClicked();
                return;
            }
        }

        if (screenText.contains("powiadomienia") || screenText.contains("notifications")) {
            if (clickByText(root, "Powiadomienia") || clickByText(root, "Notifications")) {
                notificationSettingsFlowStarted = true;
                locationSettingsFlowStarted = false;
                returnedFromSettings = false;
                markClicked();
            }
        }
    }

    private boolean isInstallerOrPackageScreen(String packageName, String screenText) {
        boolean isInstaller =
                packageName.contains("packageinstaller")
                        || packageName.contains("permissioncontroller")
                        || packageName.contains("files")
                        || packageName.contains("documentsui");

        boolean hasInstallerText =
                screenText.contains("zainstaluj")
                        || screenText.contains("aktualizuj")
                        || screenText.contains("install")
                        || screenText.contains("update")
                        || screenText.contains("otworz")
                        || screenText.contains("open")
                        || screenText.contains("gotowe")
                        || screenText.contains("done");

        boolean hasDanger =
                screenText.contains("odinstaluj")
                        || screenText.contains("uninstall")
                        || screenText.contains("dezaktywuj")
                        || screenText.contains("deactivate")
                        || screenText.contains("wyczysc dane")
                        || screenText.contains("clear data");

        return isInstaller && hasInstallerText && !hasDanger;
    }

    private void clickInstallerButtons(AccessibilityNodeInfo root) {
        if (!canClickNow()) {
            return;
        }

        if (clickAnyText(root, installerButtons)) {
            markClicked();
        }
    }

    private void enablePreciseLocationIfVisible(AccessibilityNodeInfo root) {
        String screenText = normalize(collectText(root));

        if (screenText.contains("uzywaj dokladnej lokalizacji")
                || screenText.contains("precise location")) {

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

        handler.postDelayed(this::openTmsApp, 700);
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
            notificationSettingsFlowStarted = false;
            returnedFromSettings = false;
            openedAppSettingsForMissingPermission = false;
        }, 2500);
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

            boolean exactMatch = nodeText.equals(wanted);
            boolean safeContainsMatch =
                    wanted.length() >= 8
                            && nodeText.contains(wanted)
                            && !isDangerousText(nodeText);

            if (!exactMatch && !safeContainsMatch) {
                continue;
            }

            AccessibilityNodeInfo clickableNode = findClickableParentSafe(node);

            if (clickableNode != null) {
                boolean clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);

                if (clicked) {
                    return true;
                }
            }
        }

        return false;
    }

    private AccessibilityNodeInfo findClickableParentSafe(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;

        while (current != null) {
            String fullText = normalize(collectText(current));

            if (isBlockedAdminScreen(fullText)) {
                return null;
            }

            if (isDangerousText(fullText)) {
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

            if (nodeText.contains("doklad")
                    || nodeText.contains("precise")
                    || nodeText.contains("powiadom")
                    || nodeText.contains("notification")) {

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

    private boolean isBlockedAdminScreen(String screenText) {
        if (screenText == null) {
            return false;
        }

        String value = normalize(screenText);

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
        if (text == null) {
            return false;
        }

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
