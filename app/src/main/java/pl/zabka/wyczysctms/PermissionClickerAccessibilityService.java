package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager overlayWindowManager;
    private View automationOverlayView;

    private static final long CLICK_DELAY_MS = 850;
    private static final long BACK_DELAY_MS = 850;

    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";

    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";
    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";

    private long lastClickTime = 0;
    private long lastBackTime = 0;
    private long lastOpenTmsTime = 0;
    private long lastForcedSettingsOpenTime = 0;
    private long lastAppInfoTapTime = 0;
    private boolean openedAppSettingsForMissingPermission = false;
    private boolean finalToastShown = false;

    private final List<String> tmsPackages = Arrays.asList(
            "pl.optidata.tms_android_2017",
            "pl.zabka.tms",
            "pl.zabka.tmsfalcon",
            "com.zabka.tms",
            "com.zabka.tmsfalcon"
    );

    private final List<String> installerButtons = Arrays.asList(
            "Gotowe", "Done",
            "Zainstaluj", "Instaluj", "Aktualizuj", "Zaktualizuj",
            "Install", "Update", "Dalej", "Next", "Kontynuuj", "Continue",
            "Zainstaluj mimo to", "Install anyway", "OK", "Ok"
    );

    private final List<String> allowButtons = Arrays.asList(
            "Zezwól", "Zezwol", "Zezwalaj", "Allow", "OK", "Ok",
            "Włącz", "Wlacz", "Kontynuuj", "Dalej", "Potwierdź", "Potwierdz", "Rozumiem"
    );

    private final List<String> whileUsingButtons = Arrays.asList(
            "Podczas używania aplikacji", "Podczas uzywania aplikacji",
            "Podczas używania tej aplikacji", "Podczas uzywania tej aplikacji",
            "Zezwól tylko podczas używania aplikacji", "Zezwol tylko podczas uzywania aplikacji",
            "Zezwalaj tylko podczas używania aplikacji", "Zezwalaj tylko podczas uzywania aplikacji",
            "While using the app", "While using this app", "Allow only while using the app"
    );

    private final List<String> alwaysLocationButtons = Arrays.asList(
            "Zawsze zezwalaj", "Zawsze pozwalaj", "Zezwalaj cały czas", "Zezwalaj caly czas",
            "Zezwalaj zawsze", "Allow all the time", "Always allow", "Allow always"
    );

    private final List<String> permissionRows = Arrays.asList(
            "Aparat", "Camera",
            "Kontakty", "Contacts",
            "Lokalizacja", "Location",
            "Muzyka i dźwięk", "Muzyka i dzwiek", "Music and audio",
            "Powiadomienia", "Notifications",
            "Telefon", "Phone",
            "Urządzenia w pobliżu", "Urzadzenia w poblizu", "Nearby devices",
            "Zdjęcia i filmy", "Zdjecia i filmy", "Photos and videos", "Zdjęcia", "Zdjecia", "Photos"
    );

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        handler.postDelayed(() -> handleScreen(event), 400);
        handler.postDelayed(() -> handleScreen(event), 950);
        handler.postDelayed(() -> handleScreen(event), 1750);
    }

    @Override
    public void onInterrupt() {}

    private void handleScreen(AccessibilityEvent event) {
        updateOverlayVisibility();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString().toLowerCase();
        String screenText = normalize(collectText(root) + " " + collectEventText(event));

        if (canHandleUninstall() && isUninstallConfirmationDialog(packageName, screenText)) {
            handleUninstallDialog(root);
            return;
        }

        if (canHandleInstall() && isInstallerScreen(packageName, screenText)) {
            handleInstaller(root);
            return;
        }

        if (isOwnAppOrAdminPanel(packageName, screenText)) {
            if (isAutomationRunning()) {
                showAutomationOverlay();
                if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR)) {
                    forceOpenTmsSettingsIfNeeded();
                }
            } else {
                hideAutomationOverlay();
                setFlowMode(MODE_IDLE);
            }
            return;
        }

        if (isDetailsOnlyMode() || isIdleMode()) return;
        if (isBlockedAdminScreen(screenText)) return;
        if (!canHandleTmsPermissions()) return;

        if (isDefaultOpenScreen(packageName, screenText)) {
            goBackFromWrongScreen();
            return;
        }

        if (isLegacyPermissionWarningDialog(packageName, screenText)) {
            clickLegacyPermissionConfirm(root);
            return;
        }

        if (isRuntimePermissionDialog(packageName, screenText)) {
            handleRuntimePermissionDialog(root, screenText);
            return;
        }

        if (isTmsLocationPopup(screenText)) {
            clickTmsPermissionInfo(root);
            return;
        }

        if (isTmsAppInfoScreen(packageName, screenText)) {
            clickAppInfoPermissions(root);
            return;
        }

        if (isAppPermissionsListScreen(packageName, screenText)) {
            handlePermissionsList(root, screenText);
            return;
        }

        if (isCameraPermissionScreen(packageName, screenText)) {
            handleCameraScreen(root);
            return;
        }

        if (isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
            return;
        }

        if (isNotificationPermissionScreen(packageName, screenText)) {
            handleNotificationScreen(root);
            return;
        }

        if (isGenericPermissionScreen(packageName, screenText)) {
            handleGenericPermissionScreen(root);
            return;
        }

        if (isTmsPermissionInfoScreen(screenText)) {
            if (clickTmsPermissionInfo(root)) return;
            openTmsAppSettingsFromMissingPermission(packageName, screenText);
        }
    }

    private boolean isOwnAppOrAdminPanel(String packageName, String screenText) {
        String text = normalize(screenText);
        String ownPackage = getPackageName().toLowerCase();
        return packageName.equals(ownPackage)
                || packageName.contains("wyczysctms")
                || text.contains("wyczysc tms")
                || text.contains("panel administratora")
                || text.contains("nadaj uprawnienia tms i uruchom")
                || text.contains("powrot do ekranu kierowcy");
    }

    private boolean isUninstallConfirmationDialog(String packageName, String screenText) {
        boolean systemDialog = packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");
        boolean uninstallText = screenText.contains("odinstalowac")
                || screenText.contains("odinstaluj")
                || screenText.contains("uninstall")
                || screenText.contains("usunac aplikacje")
                || screenText.contains("usunac te aplikacje");
        return systemDialog && uninstallText;
    }

    private void handleUninstallDialog(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickByTextAllowDanger(root, "OK")
                || clickByTextAllowDanger(root, "Ok")
                || clickByTextAllowDanger(root, "Odinstaluj")
                || clickByTextAllowDanger(root, "Uninstall")) {
            markClicked();
        }
    }

    private boolean isInstallerScreen(String packageName, String screenText) {
        boolean installerPackage = packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller")
                || packageName.contains("files")
                || packageName.contains("documentsui")
                || packageName.contains("package");
        boolean completion = screenText.contains("aplikacja zostala zainstalowana")
                || screenText.contains("aplikacja została zainstalowana")
                || screenText.contains("app installed")
                || screenText.contains("application installed");
        boolean installAction = screenText.contains("zainstaluj")
                || screenText.contains("instaluj")
                || screenText.contains("aktualizuj")
                || screenText.contains("install")
                || screenText.contains("update")
                || screenText.contains("dalej")
                || screenText.contains("next")
                || screenText.contains("kontynuuj")
                || screenText.contains("continue")
                || screenText.contains("gotowe")
                || screenText.contains("done");
        boolean danger = screenText.contains("odinstaluj")
                || screenText.contains("uninstall")
                || screenText.contains("dezaktywuj")
                || screenText.contains("clear data")
                || screenText.contains("wyczysc dane");
        return (installerPackage || completion) && installAction && !danger;
    }

    private void handleInstaller(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickAnyText(root, installerButtons)) markClicked();
    }

    private boolean isDefaultOpenScreen(String packageName, String screenText) {
        return packageName.contains("settings")
                && containsTmsText(screenText)
                && (screenText.contains("otwieraj domyslnie")
                || screenText.contains("otwieraj domyślnie")
                || screenText.contains("open by default")
                || screenText.contains("obslugiwane linki")
                || screenText.contains("obsługiwane linki")
                || screenText.contains("supported links"));
    }

    private void goBackFromWrongScreen() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 1200) return;
        lastBackTime = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean isLegacyPermissionWarningDialog(String packageName, String screenText) {
        boolean systemDialog = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.contains("android")
                || packageName.contains("settings");

        boolean warningText = screenText.contains("starszej wersji androida")
                || screenText.contains("older version of android")
                || screenText.contains("dostep do zdjec i filmow")
                || screenText.contains("dostęp do zdjęć i filmów")
                || screenText.contains("dostep do muzyki")
                || screenText.contains("dostęp do muzyki")
                || screenText.contains("dostep do zdjec i filmow rowniez bedzie mozliwy")
                || screenText.contains("również będzie możliwy")
                || screenText.contains("rowniez bedzie mozliwy");

        boolean confirmButton = screenText.contains("potwierdz")
                || screenText.contains("potwierdź")
                || screenText.contains("confirm")
                || screenText.contains("ok");

        return systemDialog && warningText && confirmButton;
    }

    private void clickLegacyPermissionConfirm(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (clickByTextAllowDanger(root, "Potwierdź")
                || clickByTextAllowDanger(root, "Potwierdz")
                || clickByTextAllowDanger(root, "Confirm")
                || clickByTextAllowDanger(root, "OK")
                || clickByTextAllowDanger(root, "Ok")) {
            markClicked();
        }
    }

    private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        boolean system = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.contains("android");
        boolean permission = screenText.contains("zezwol")
                || screenText.contains("zezwalaj")
                || screenText.contains("allow")
                || screenText.contains("permission")
                || screenText.contains("podczas uzywania")
                || screenText.contains("while using")
                || screenText.contains("aparat")
                || screenText.contains("camera")
                || screenText.contains("lokalizacja")
                || screenText.contains("location")
                || screenText.contains("powiadomienia")
                || screenText.contains("notifications")
                || screenText.contains("kontakty")
                || screenText.contains("contacts")
                || screenText.contains("telefon")
                || screenText.contains("phone")
                || screenText.contains("zdjec")
                || screenText.contains("photos")
                || screenText.contains("nearby devices");
        return system && permission && containsTmsText(screenText);
    }

    private void handleRuntimePermissionDialog(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;
        if ((screenText.contains("aparat") || screenText.contains("camera") || screenText.contains("lokalizacja") || screenText.contains("location"))
                && clickAnyText(root, whileUsingButtons)) {
            markClicked();
            return;
        }
        if (clickAnyText(root, allowButtons)) markClicked();
    }

    private boolean isTmsLocationPopup(String text) {
        return containsTmsText(text)
                && (text.contains("dostep do lokalizacji")
                || text.contains("zaktualizuj ustawienia")
                || text.contains("aktualizuj ustawienia")
                || text.contains("location access")
                || text.contains("update settings"));
    }

    private boolean clickTmsPermissionInfo(AccessibilityNodeInfo root) {
        if (!canClickNow()) return false;
        boolean clicked = clickByText(root, "ZAKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Zaktualizuj ustawienia")
                || clickByText(root, "AKTUALIZUJ USTAWIENIA")
                || clickByText(root, "Aktualizuj ustawienia")
                || clickByText(root, "Ustawienia")
                || clickByText(root, "Settings")
                || clickByText(root, "Update settings");
        if (clicked) markClicked();
        return clicked;
    }

    private boolean isTmsAppInfoScreen(String packageName, String screenText) {
        return packageName.contains("settings")
                && containsTmsText(screenText)
                && (screenText.contains("o aplikacji")
                || screenText.contains("informacje o aplikacji")
                || screenText.contains("app info")
                || screenText.contains("uprawnienia")
                || screenText.contains("permissions")
                || screenText.contains("brak przyznanych uprawnien"));
    }

    private void clickAppInfoPermissions(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        long now = System.currentTimeMillis();
        if (now - lastAppInfoTapTime < 1000) return;
        lastAppInfoTapTime = now;

        if (tapAppInfoPermissionsRow(root)) {
            markClicked();
            return;
        }

        // Fallback for PM90/PM95 Settings layout. This taps the permissions row area,
        // not the lower "Open by default" row.
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            int x = bounds.left + (bounds.width() / 2);
            int y = bounds.top + (int) (bounds.height() * 0.34f);
            if (tapAt(x, y)) markClicked();
        }
    }

    private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> exactNodes = new ArrayList<>();
        collectExactNodes(root, "uprawnienia", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "permissions", exactNodes);
        if (exactNodes.isEmpty()) collectExactNodes(root, "zezwolenia", exactNodes);

        for (AccessibilityNodeInfo node : exactNodes) {
            Rect textRect = new Rect();
            node.getBoundsInScreen(textRect);
            if (textRect.isEmpty()) continue;
            AccessibilityNodeInfo row = findSmallClickableParent(node, textRect.centerY());
            if (row != null) {
                Rect rowRect = new Rect();
                row.getBoundsInScreen(rowRect);
                if (!rowRect.isEmpty()) return tapAt(rowRect.centerX(), rowRect.centerY());
            }
            return tapAt(textRect.centerX(), textRect.centerY());
        }

        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (!rootRect.isEmpty()) {
            return tapAt(rootRect.centerX(), rootRect.top + (int) (rootRect.height() * 0.34f));
        }
        return false;
    }

    private void collectExactNodes(AccessibilityNodeInfo node, String wanted, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && normalize(text.toString()).equals(wanted)) out.add(node);
        CharSequence desc = node.getContentDescription();
        if (desc != null && normalize(desc.toString()).equals(wanted)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectExactNodes(node.getChild(i), wanted, out);
    }

    private AccessibilityNodeInfo findSmallClickableParent(AccessibilityNodeInfo node, int expectedY) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            if (!rect.isEmpty()) {
                int height = rect.height();
                boolean isRow = height > 35 && height < 240 && expectedY >= rect.top && expectedY <= rect.bottom;
                if (isRow && current.isClickable() && current.isEnabled()) return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isAppPermissionsListScreen(String packageName, String screenText) {
        return packageName.contains("settings")
                && containsTmsText(screenText)
                && (screenText.contains("uprawnienia aplikacji")
                || screenText.contains("app permissions")
                || screenText.contains("maja dostep")
                || screenText.contains("nie maja dostepu")
                || screenText.contains("allowed")
                || screenText.contains("not allowed"));
    }

    private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)) {
                if (tapPermissionRowByText(root, permission)) {
                    markClicked();
                    return;
                }
            }
        }

        // Jeżeli nie ma już pozycji w sekcji "Nie mają dostępu", kończymy flow.
        finishPermissionFlowAndCloseSettings();
    }

    private boolean isCameraPermissionScreen(String packageName, String screenText) {
        return (packageName.contains("settings") || packageName.contains("permissioncontroller"))
                && containsTmsText(screenText)
                && (screenText.contains("aparat") || screenText.contains("camera") || screenText.contains("robienie zdjec") || screenText.contains("record video"));
    }

    private void handleCameraScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickAnyText(root, whileUsingButtons)) {
            markClicked();
            goBackToPermissionsListLater();
        }
    }

    private boolean isLocationPermissionScreen(String packageName, String screenText) {
        return packageName.contains("settings")
                && containsTmsText(screenText)
                && (screenText.contains("lokalizacja") || screenText.contains("location") || screenText.contains("zawsze zezwalaj") || screenText.contains("allow all the time") || screenText.contains("precise location"));
    }

    private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            markClicked();
            goBackToPermissionsListLater();
            return;
        }

        if (clickAnyText(root, alwaysLocationButtons)
                || tapTextCenter(root, "Zawsze zezwalaj")
                || tapTextCenter(root, "Zezwalaj cały czas")
                || tapTextCenter(root, "Zezwalaj caly czas")
                || tapTextCenter(root, "Allow all the time")
                || tapTextCenter(root, "Always allow")) {
            markClicked();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) enablePreciseLocationIfVisible(r);
            }, 650);
            goBackToPermissionsListLater();
        }
    }

    private boolean isNotificationPermissionScreen(String packageName, String screenText) {
        return (packageName.contains("settings") || packageName.contains("permissioncontroller"))
                && containsTmsText(screenText)
                && (screenText.contains("powiadomienia") || screenText.contains("notifications") || screenText.contains("zezwalaj na powiadomienia") || screenText.contains("allow notifications"));
    }

    private void handleNotificationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        boolean clicked = false;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia z aplikacji ZABKA TMSFalcon") || clicked;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia z aplikacji ZABKA TMSfalcon") || clicked;
        clicked = clickSwitchNearText(root, "Wszystkie powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "All notifications") || clicked;
        clicked = clickSwitchNearText(root, "Zezwalaj na kropkę powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "Zezwalaj na kropke powiadomienia") || clicked;
        clicked = clickSwitchNearText(root, "Allow notification dot") || clicked;
        clicked = clickAnyText(root, allowButtons) || clicked;
        if (clicked) {
            markClicked();
            handler.postDelayed(this::goBackToPermissionsListLater, 700);
        }
    }

    private boolean isGenericPermissionScreen(String packageName, String screenText) {
        return (packageName.contains("settings") || packageName.contains("permissioncontroller"))
                && containsTmsText(screenText)
                && (screenText.contains("kontakty")
                || screenText.contains("contacts")
                || screenText.contains("telefon")
                || screenText.contains("phone")
                || screenText.contains("zdjec")
                || screenText.contains("photos")
                || screenText.contains("nearby devices")
                || screenText.contains("urzadzenia w poblizu")
                || screenText.contains("muzyka")
                || screenText.contains("music"));
    }

    private void handleGenericPermissionScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;
        if (clickByTextAllowDanger(root, "Potwierdź")
                || clickByTextAllowDanger(root, "Potwierdz")
                || clickByTextAllowDanger(root, "Confirm")) {
            markClicked();
            return;
        }
        if (clickAnyText(root, allowButtons)) {
            markClicked();
            goBackToPermissionsListLater();
        }
    }

    private void goBackToPermissionsListLater() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 1500) return;
        lastBackTime = now;
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), BACK_DELAY_MS);
    }

    private void forceOpenTmsSettingsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastForcedSettingsOpenTime < 1800) return;
        lastForcedSettingsOpenTime = now;

        String pkg = resolveTmsPackage(null);
        if (pkg == null) return;

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void finishPermissionFlowWithMessage() {
        if (finalToastShown) return;
        finalToastShown = true;

        try {
            Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }

        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void finishPermissionFlowAndCloseSettings() {
        if (finalToastShown) return;
        finalToastShown = true;

        setFlowMode(MODE_IDLE);
        hideAutomationOverlay();

        try {
            Toast.makeText(this, "Gotowe. Można uruchomić aplikację TMS.", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }

        // Zamykamy ekran ustawień/uprawnień. HOME jest stabilniejsze niż kilka cofnięć,
        // bo ustawienia Androida potrafią mieć różną głębokość ekranów na PM90/PM95.
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 300);
    }

    private void openTmsAppAndFinishPermissionFlow() {
        openTmsApp();
        handler.postDelayed(() -> {
            if (isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS) || isMode(MODE_FULL_REPAIR)) {
                setFlowMode(MODE_IDLE);
                hideAutomationOverlay();
            }
        }, 2400);
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
            } catch (Exception ignored) {}
        }
        resetFlowFlagsLater();
    }

    private void resetFlowFlagsLater() {
        handler.postDelayed(() -> {
            openedAppSettingsForMissingPermission = false;
            if (isMode(MODE_OPEN_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_FULL_REPAIR)) {
                setFlowMode(MODE_IDLE);
                hideAutomationOverlay();
            }
        }, 3000);
    }

    private boolean isTmsPermissionInfoScreen(String screenText) {
        return containsTmsText(screenText)
                && (screenText.contains("cannot use this application without requested permission")
                || screenText.contains("requested permission")
                || screenText.contains("without requested permission")
                || screenText.contains("permission")
                || screenText.contains("lokalizacja")
                || screenText.contains("location"));
    }

    private void openTmsAppSettingsFromMissingPermission(String currentPackageName, String screenText) {
        if (openedAppSettingsForMissingPermission) return;
        if (!containsTmsText(screenText) || !screenText.contains("permission")) return;
        String tmsPackage = resolveTmsPackage(currentPackageName);
        if (tmsPackage == null) return;
        openedAppSettingsForMissingPermission = true;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + tmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            markClicked();
        } catch (Exception ignored) {}
    }

    private String resolveTmsPackage(String currentPackageName) {
        if (currentPackageName != null) {
            String p = currentPackageName.toLowerCase();
            if (p.contains("tms") || p.contains("falcon") || p.contains("zabka")) return currentPackageName;
        }
        PackageManager pm = getPackageManager();
        for (String pkg : tmsPackages) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) != null) return pkg;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean hasDeniedPermissionsSection(String screenText) {
        String text = normalize(screenText);
        return text.contains("nie maja dostepu") || text.contains("not allowed");
    }

    private boolean isPermissionInDeniedSection(String screenText, String permissionName) {
        String text = normalize(screenText);
        String permission = normalize(permissionName);
        int deniedIndex = text.indexOf("nie maja dostepu");
        if (deniedIndex < 0) deniedIndex = text.indexOf("not allowed");
        if (deniedIndex < 0) return false;
        int permissionIndex = text.indexOf(permission, deniedIndex);
        return permissionIndex > deniedIndex;
    }

    private boolean tapPermissionRowByText(AccessibilityNodeInfo root, String text) {
        return tapTextCenter(root, text);
    }

    private boolean tapTextCenter(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String nodeText = normalize(getNodeVisibleText(node));
            if (!nodeText.equals(wanted) && !nodeText.contains(wanted)) continue;
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.isEmpty()) continue;
            return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapAt(int x, int y) {
        if (x <= 0 || y <= 0) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 85);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {
            return false;
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

    private boolean isNotificationAlreadyEnabled(AccessibilityNodeInfo root) {
        return isTextOptionChecked(root, "Powiadomienia")
                || isTextOptionChecked(root, "Zezwalaj na powiadomienia")
                || isTextOptionChecked(root, "Notifications")
                || isTextOptionChecked(root, "Allow notifications");
    }

    private boolean isTextOptionChecked(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo current = node;
            for (int i = 0; i < 5 && current != null; i++) {
                if (containsCheckedNode(current)) return true;
                current = current.getParent();
            }
        }
        return false;
    }

    private boolean containsCheckedNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isChecked()) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (containsCheckedNode(node.getChild(i))) return true;
        return false;
    }

    private void enablePreciseLocationIfVisible(AccessibilityNodeInfo root) {
        String screenText = normalize(collectText(root));
        if (screenText.contains("uzywaj dokladnej lokalizacji") || screenText.contains("precise location")) {
            if (isTextOptionChecked(root, "Używaj dokładnej lokalizacji")
                    || isTextOptionChecked(root, "Uzywaj dokladnej lokalizacji")
                    || isTextOptionChecked(root, "Precise location")) return;
            if (clickSwitchNearText(root, "Używaj dokładnej lokalizacji")
                    || clickSwitchNearText(root, "Uzywaj dokladnej lokalizacji")
                    || clickSwitchNearText(root, "Precise location")) markClicked();
        }
    }

    private boolean clickAnyText(AccessibilityNodeInfo root, List<String> texts) {
        if (root == null || texts == null) return false;
        for (String text : texts) if (clickByText(root, text)) return true;
        return false;
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String nodeText = normalize(getNodeVisibleText(node));
            if (isDangerousText(nodeText)) continue;
            boolean exact = nodeText.equals(wanted);
            boolean contains = wanted.length() >= 8 && nodeText.contains(wanted) && !isDangerousText(nodeText);
            if (!exact && !contains) continue;
            AccessibilityNodeInfo clickableNode = findClickableParentSafe(node);
            if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private boolean clickByTextAllowDanger(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String nodeText = normalize(getNodeVisibleText(node));
            if (!nodeText.equals(wanted) && !nodeText.contains(wanted)) continue;
            AccessibilityNodeInfo clickableNode = findClickableParentForUninstall(node);
            if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableParentSafe(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            String fullText = normalize(collectText(current));
            if (isBlockedAdminScreen(fullText) || isDangerousText(fullText)) return null;
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableParentForUninstall(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            String fullText = normalize(collectText(current));
            if (isBlockedAdminScreen(fullText)) return null;
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean clickSwitchNearText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo parent = node;
            for (int i = 0; i < 5 && parent != null; i++) {
                if (clickFirstSwitchOrClickableChild(parent)) return true;
                parent = parent.getParent();
            }
        }
        return false;
    }

    private boolean clickFirstSwitchOrClickableChild(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String wholeNodeText = normalize(collectText(node));
        if (isBlockedAdminScreen(wholeNodeText) || isDangerousText(wholeNodeText)) return false;
        CharSequence className = node.getClassName();
        if (className != null) {
            String cls = className.toString().toLowerCase();
            if ((cls.contains("switch") || cls.contains("checkbox")) && node.isEnabled() && node.isClickable() && !node.isChecked()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (node.isClickable() && node.isEnabled()) {
            String nodeText = normalize(getNodeVisibleText(node));
            if (nodeText.contains("doklad") || nodeText.contains("precise") || nodeText.contains("powiadom") || nodeText.contains("notification")) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) if (clickFirstSwitchOrClickableChild(node.getChild(i))) return true;
        return false;
    }

    private String getNodeVisibleText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder builder = new StringBuilder();
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null) builder.append(text).append(" ");
        if (description != null) builder.append(description).append(" ");
        return builder.toString().trim();
    }

    private boolean canClickNow() {
        return System.currentTimeMillis() - lastClickTime >= CLICK_DELAY_MS;
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
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null) builder.append(text).append(" ");
        CharSequence description = node.getContentDescription();
        if (description != null) builder.append(description).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) collectTextRecursive(node.getChild(i), builder);
    }

    private String collectEventText(AccessibilityEvent event) {
        if (event == null || event.getText() == null) return "";
        StringBuilder builder = new StringBuilder();
        for (CharSequence text : event.getText()) if (text != null) builder.append(text).append(" ");
        CharSequence description = event.getContentDescription();
        if (description != null) builder.append(description).append(" ");
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
        return value.contains("zabka") || value.contains("tms") || value.contains("tmsfalcon") || value.contains("falcon");
    }

    private void updateOverlayVisibility() {
        if (isAutomationRunning()) showAutomationOverlay();
        else hideAutomationOverlay();
    }

    private void showAutomationOverlay() {
        if (automationOverlayView != null) return;
        try {
            overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (overlayWindowManager == null) return;

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.argb(235, 0, 0, 0));
            root.setClickable(false);
            root.setFocusable(false);
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            TextView message = new TextView(this);
            message.setText("Naprawa TMS w toku\nNie dotykaj ekranu\nAplikacja automatycznie odinstaluje, zainstaluje i nada uprawnienia");
            message.setTextColor(Color.WHITE);
            message.setTextSize(20);
            message.setGravity(Gravity.CENTER);
            message.setPadding(36, 36, 36, 36);
            message.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            FrameLayout.LayoutParams msgParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            msgParams.gravity = Gravity.CENTER;
            root.addView(message, msgParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;
            overlayWindowManager.addView(root, params);
            automationOverlayView = root;
        } catch (Exception ignored) {
            automationOverlayView = null;
        }
    }

    private void hideAutomationOverlay() {
        try {
            if (overlayWindowManager != null && automationOverlayView != null) overlayWindowManager.removeView(automationOverlayView);
        } catch (Exception ignored) {}
        automationOverlayView = null;
    }

    private boolean isAutomationRunning() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_UNINSTALL_TMS) || isMode(MODE_INSTALL_TMS) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
    }

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void setFlowMode(String mode) {
        if (!MODE_IDLE.equals(mode)) {
            finalToastShown = false;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FLOW_MODE, mode).apply();
    }

    private boolean isMode(String expectedMode) {
        return expectedMode.equals(getFlowMode());
    }

    private boolean canHandleUninstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_UNINSTALL_TMS);
    }

    private boolean canHandleInstall() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_INSTALL_TMS);
    }

    private boolean canHandleTmsPermissions() {
        return isMode(MODE_FULL_REPAIR) || isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
    }

    private boolean isDetailsOnlyMode() {
        return isMode(MODE_DETAILS_ONLY);
    }

    private boolean isIdleMode() {
        return isMode(MODE_IDLE);
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
