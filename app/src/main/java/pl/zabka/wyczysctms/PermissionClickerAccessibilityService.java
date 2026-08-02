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

    private static final long CLICK_DELAY_MS = 950;
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
    private long lastPm95LocationTap = 0;
    private long lastInitialLocationSchedule = 0;
    private long lastAlwaysLocationSchedule = 0;
    private boolean openedAppSettingsForMissingPermission = false;
    private boolean finalToastShown = false;
    private boolean initialLocationSequenceScheduled = false;
    private boolean alwaysLocationSequenceScheduled = false;
    private boolean waitingForAlwaysLocation = false;
    private long lastRuntimePermissionActionTime = 0;
    private int runtimePermissionsClicked = 0;

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

        if (isMode(MODE_OPEN_TMS) && handlePm95LocationAbsolute(screenText)) {
            return;
        }

        // PM95: dwa ekrany lokalizacji rozpoznajemy po unikalnej treści,
        // niezależnie od pakietu zgłoszonego przez AccessibilityEvent.
        if (isMode(MODE_OPEN_TMS) && isPm95InitialLocationDialog(screenText)) {
            schedulePm95InitialLocationClick();
            return;
        }

        if (isMode(MODE_OPEN_TMS) && isPm95AlwaysLocationScreen(screenText)) {
            schedulePm95AlwaysLocationClick();
            return;
        }

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
                if (isMode(MODE_GRANT_TMS_PERMISSIONS)) {
                    forceOpenTmsSettingsIfNeeded();
                }
            } else {
                hideAutomationOverlay();
                setFlowMode(MODE_IDLE);
            }
            return;
        }

        if (isIdleMode()) { hideAutomationOverlay(); return; }
        if (isDetailsOnlyMode()) return;
        if (isBlockedAdminScreen(screenText)) return;
        if (!canHandleTmsPermissions()) return;

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

        if (isMode(MODE_OPEN_TMS) && isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
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

    private boolean isPm95InitialLocationDialog(String screenText) {
        String text = normalize(screenText);
        return (text.contains("dostep do lokalizacji urzadzenia")
                || text.contains("dokladna") && text.contains("przyblizona"))
                && text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj");
    }

    private void schedulePm95InitialLocationClick() {
        long now = System.currentTimeMillis();
        if (now - lastInitialLocationSchedule < 2500) return;
        lastInitialLocationSchedule = now;

        // PM95 potrzebuje chwili po przejściu z aparatu do lokalizacji.
        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String text = normalize(collectText(root));
            if (!isPm95InitialLocationDialog(text)) return;

            boolean clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Screen PM95: środek pierwszego przycisku lokalizacji około 61,5% wysokości.
                    clicked = tapAt(b.centerX(), b.top + (int) (b.height() * 0.615f));
                }
            }

            if (clicked) {
                markClicked();
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                runtimePermissionsClicked++;
                retryCurrentPermissionWindow();
            }
        }, 1400);
    }

    private boolean isPm95AlwaysLocationScreen(String screenText) {
        String text = normalize(screenText);
        return text.contains("lokalizacja - dostep")
                && text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");
    }

    private void schedulePm95AlwaysLocationClick() {
        long now = System.currentTimeMillis();
        if (now - lastAlwaysLocationSchedule < 3000) return;
        lastAlwaysLocationSchedule = now;
        waitingForAlwaysLocation = true;

        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String text = normalize(collectText(root));
            if (!isPm95AlwaysLocationScreen(text)) return;

            boolean clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Zawsze zezwalaj", "Allow all the time", "Always allow"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Pełny screen PM95: radio pierwszej opcji około x=9,3%, y=51,5%.
                    clicked = tapAt(b.left + (int) (b.width() * 0.093f),
                            b.top + (int) (b.height() * 0.515f));
                }
            }

            if (!clicked) return;
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();

            handler.postDelayed(() -> verifyPm95AlwaysLocationAndBack(0), 1200);
        }, 1500);
    }

    private void verifyPm95AlwaysLocationAndBack(int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            waitingForAlwaysLocation = false;
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                scheduleRuntimeFlowFinishCheck();
            }, 800);
            return;
        }

        if (attempt >= 2) return;
        Rect b = new Rect();
        root.getBoundsInScreen(b);
        if (b.isEmpty()) return;

        // Naprzemiennie klik w tekst/wiersz i radio.
        float xRatio = attempt == 0 ? 0.35f : 0.093f;
        tapAt(b.left + (int) (b.width() * xRatio),
                b.top + (int) (b.height() * 0.515f));
        markClicked();
        handler.postDelayed(() -> verifyPm95AlwaysLocationAndBack(attempt + 1), 1200);
    }

    private boolean handlePm95LocationAbsolute(String screenText) {
        String text = normalize(screenText);

        boolean firstLocation = text.contains("podczas uzywania aplikacji")
                && text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz") || (text.contains("dokladna") && text.contains("przyblizona")));

        boolean finalLocation = text.contains("zawsze zezwalaj")
                && text.contains("zezwalaj tylko podczas uzywania aplikacji")
                && text.contains("zawsze pytaj")
                && text.contains("nie zezwalaj");

        if (!firstLocation && !finalLocation) return false;

        long now = System.currentTimeMillis();
        if (now - lastPm95LocationTap < 1300) return true;
        lastPm95LocationTap = now;

        if (firstLocation) {
            // Pełny screenshot PM95: środek pierwszego przycisku około 50% szerokości i 60,5% wysokości.
            handler.postDelayed(() -> tapAbsoluteScreenRatio(0.50f, 0.605f), 450);
            handler.postDelayed(() -> tapAbsoluteScreenRatio(0.50f, 0.605f), 1700);
            return true;
        }

        waitingForAlwaysLocation = true;
        // Pełny screenshot PM95: radio Zawsze zezwalaj około 15% szerokości i 52,9% wysokości.
        handler.postDelayed(() -> tapAbsoluteScreenRatio(0.15f, 0.529f), 500);
        // Druga próba w tekst/środek tego samego wiersza.
        handler.postDelayed(() -> tapAbsoluteScreenRatio(0.42f, 0.529f), 1800);
        handler.postDelayed(this::verifyAbsoluteAlwaysLocation, 3000);
        return true;
    }

    private boolean tapAbsoluteScreenRatio(float xRatio, float yRatio) {
        if (!isMode(MODE_OPEN_TMS)) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0) return false;
        boolean clicked = tapAt((int) (width * xRatio), (int) (height * yRatio));
        if (clicked) markClicked();
        return clicked;
    }

    private void verifyAbsoluteAlwaysLocation() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (isAlwaysLocationAlreadyChecked(root)) {
            enablePreciseLocationIfVisible(root);
            waitingForAlwaysLocation = false;
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastRuntimePermissionActionTime = System.currentTimeMillis();
                scheduleRuntimeFlowFinishCheck();
            }, 900);
            return;
        }

        // Ostatnia próba bez zgadywania bounds roota.
        tapAbsoluteScreenRatio(0.15f, 0.529f);
        handler.postDelayed(() -> {
            AccessibilityNodeInfo check = getRootInActiveWindow();
            if (check != null && isAlwaysLocationAlreadyChecked(check)) {
                enablePreciseLocationIfVisible(check);
                waitingForAlwaysLocation = false;
                performGlobalAction(GLOBAL_ACTION_BACK);
                scheduleRuntimeFlowFinishCheck();
            }
        }, 1400);
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
        String text = normalize(screenText);
        return packageName.contains("settings")
                && containsTmsText(text)
                && (text.contains("otwieraj obslugiwane linki")
                || text.contains("open supported links")
                || text.contains("linki otwierane w tej aplikacji")
                || text.contains("0 zweryfikowanych linkow"));
    }

    private void goBackFromWrongScreen() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 700) return;
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
                || screenText.contains("dostep do zdjec")
                || screenText.contains("dostęp do zdjęć")
                || screenText.contains("dostep do muzyki")
                || screenText.contains("dostęp do muzyki")
                || screenText.contains("rowniez bedzie mozliwy")
                || screenText.contains("również będzie możliwy");
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
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            runtimePermissionsClicked++;
            scheduleRuntimeFlowFinishCheck();
        }
    }

    private boolean isRuntimePermissionDialog(String packageName, String screenText) {
        String text = normalize(screenText);
        boolean controller = packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller");
        boolean dialogChoice = text.contains("podczas uzywania aplikacji")
                || text.contains("tylko tym razem")
                || text.contains("nie zezwalaj")
                || text.contains("while using the app")
                || text.contains("only this time")
                || text.contains("dont allow")
                || text.contains("don't allow")
                || text.contains("zezwol")
                || text.contains("allow");
        return controller && dialogChoice;
    }

    private void handleRuntimePermissionDialog(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        String text = normalize(screenText);
        boolean camera = text.contains("aparat") || text.contains("camera")
                || text.contains("robienie zdjec") || text.contains("record video");
        boolean location = text.contains("lokalizacja") || text.contains("location")
                || text.contains("dokladna") || text.contains("precise");

        boolean clicked;
        if (camera || location) {
            clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Podczas używania aplikacji", "Podczas uzywania aplikacji", "While using the app"});

            if (!clicked) {
                Rect b = new Rect();
                root.getBoundsInScreen(b);
                if (!b.isEmpty()) {
                    // Aparat: pierwszy przycisk ok. 51,5%. Lokalizacja z grafikami: ok. 61,5%.
                    float yRatio = camera ? 0.515f : 0.615f;
                    clicked = tapAt(b.left + b.width() / 2,
                            b.top + (int) (b.height() * yRatio));
                }
            }
        } else {
            clicked = tapRuntimeChoiceExact(root,
                    new String[]{"Zezwól", "Zezwol", "Zezwalaj", "Allow"});
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            runtimePermissionsClicked++;
            if (camera) {
                schedulePm95InitialLocationAfterCamera();
            }
            retryCurrentPermissionWindow();
            scheduleRuntimeFlowFinishCheck();
        }
    }

    private void scheduleRuntimeFlowFinishCheck() {
        handler.postDelayed(() -> {
            if (!isMode(MODE_OPEN_TMS)) return;
            if (waitingForAlwaysLocation) return;

            long quietFor = System.currentTimeMillis() - lastRuntimePermissionActionTime;
            if (runtimePermissionsClicked > 0 && quietFor >= 4500) {
                setFlowMode(MODE_IDLE);
                hideAutomationOverlay();
                Toast.makeText(this, "Gotowe. Uprawnienia TMS zostały nadane.", Toast.LENGTH_LONG).show();
            }
        }, 5000);
    }

    private boolean tapRuntimeChoiceExact(AccessibilityNodeInfo root, String[] labels) {
        if (root == null || labels == null) return false;

        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            String wanted = normalize(label);

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                String visible = normalize(getNodeVisibleText(node));
                if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

                // Najpierw natywny ACTION_CLICK na najmniejszym klikalnym rodzicu.
                AccessibilityNodeInfo current = node;
                for (int i = 0; i < 5 && current != null; i++) {
                    Rect r = new Rect();
                    current.getBoundsInScreen(r);
                    if (current.isEnabled() && current.isClickable() && !r.isEmpty()
                            && r.height() >= 45 && r.height() <= 350
                            && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                    current = current.getParent();
                }

                // PM95: gesture dokładnie w środek widocznego napisu/przycisku.
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty() && tapAt(r.centerX(), r.centerY())) return true;
            }
        }
        return false;
    }

    private void retryCurrentPermissionWindow() {
        handler.postDelayed(this::handleCurrentPermissionWindow, 1300);
        handler.postDelayed(this::handleCurrentPermissionWindow, 2400);
    }

    private void handleCurrentPermissionWindow() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String packageName = root.getPackageName() == null
                ? "" : root.getPackageName().toString().toLowerCase();
        String screenText = normalize(collectText(root));

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
        if (isLocationPermissionScreen(packageName, screenText)) {
            handleLocationScreen(root);
        }
    }

    private void schedulePm95InitialLocationAfterCamera() {
        if (initialLocationSequenceScheduled) return;
        initialLocationSequenceScheduled = true;

        // Lokalizacja pojawia się po zamknięciu dialogu aparatu.
        handler.postDelayed(() -> tapCurrentWindowRatio(0.50f, 0.615f), 1700);
        handler.postDelayed(() -> tapCurrentWindowRatio(0.50f, 0.615f), 3000);
    }

    private void schedulePm95AlwaysLocationAfterUpdateSettings() {
        if (alwaysLocationSequenceScheduled) return;
        alwaysLocationSequenceScheduled = true;
        waitingForAlwaysLocation = true;

        // Ustawienia potrzebują czasu na narysowanie ekranu Lokalizacja - dostęp.
        handler.postDelayed(() -> tapCurrentWindowRatio(0.35f, 0.515f), 2100);
        handler.postDelayed(() -> tapCurrentWindowRatio(0.093f, 0.515f), 3400);
        handler.postDelayed(this::verifyPm95AlwaysLocationAndReturn, 4700);
    }

    private boolean tapCurrentWindowRatio(float xRatio, float yRatio) {
        if (!isMode(MODE_OPEN_TMS)) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        Rect b = new Rect();
        root.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        boolean result = tapAt(b.left + (int) (b.width() * xRatio),
                b.top + (int) (b.height() * yRatio));
        if (result) markClicked();
        return result;
    }

    private void verifyPm95AlwaysLocationAndReturn() {
        if (!isMode(MODE_OPEN_TMS)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String text = normalize(collectText(root));
        boolean stillOnLocationSettings = text.contains("lokalizacja - dostep")
                || text.contains("zawsze zezwalaj")
                || text.contains("zezwalaj tylko podczas uzywania aplikacji");

        if (stillOnLocationSettings && !isAlwaysLocationAlreadyChecked(root)) {
            tapCurrentWindowRatio(0.093f, 0.515f);
            handler.postDelayed(this::verifyPm95AlwaysLocationAndReturn, 1300);
            return;
        }

        enablePreciseLocationIfVisible(root);
        waitingForAlwaysLocation = false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastRuntimePermissionActionTime = System.currentTimeMillis();
        scheduleRuntimeFlowFinishCheck();
    }

    private boolean isTmsLocationPopup(String text) {
        String value = normalize(text);
        return containsTmsText(value)
                && (value.contains("dostep do lokalizacji")
                || value.contains("zaktualizuj ustawienia")
                || value.contains("aktualizuj ustawienia")
                || value.contains("location access")
                || value.contains("update settings"));
    }

    private boolean clickTmsPermissionInfo(AccessibilityNodeInfo root) {
        if (!canClickNow()) return false;

        boolean clicked = clickByTextAllowDanger(root, "ZAKTUALIZUJ USTAWIENIA")
                || clickByTextAllowDanger(root, "Zaktualizuj ustawienia")
                || clickByTextAllowDanger(root, "AKTUALIZUJ USTAWIENIA")
                || clickByTextAllowDanger(root, "Aktualizuj ustawienia")
                || clickByTextAllowDanger(root, "UPDATE SETTINGS")
                || clickByTextAllowDanger(root, "Update settings");

        if (clicked) {
            waitingForAlwaysLocation = true;
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            schedulePm95AlwaysLocationAfterUpdateSettings();
            retryCurrentPermissionWindow();
        }
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
        if (now - lastAppInfoTapTime < 900) return;
        lastAppInfoTapTime = now;

        if (tapAppInfoPermissionsRow(root)
                || tapExactVisibleText(root, "Uprawnienia")
                || tapExactVisibleText(root, "Permissions")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnień")
                || tapContainsVisibleText(root, "Brak przyznanych uprawnien")) {
            markClicked();
        }
    }

    private boolean tapPm95PermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (rootRect.isEmpty()) return false;

        // Dla układu ze screena PM90/PM95 wiersz Uprawnienia jest około 66% wysokości aktywnego okna.
        int x = rootRect.left + (rootRect.width() / 2);
        int y = rootRect.top + (int) (rootRect.height() * 0.66f);
        return tapAt(x, y);
    }

    private boolean tapExactVisibleText(AccessibilityNodeInfo root, String wantedText) {
        if (root == null || wantedText == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectExactNodes(root, normalize(wantedText), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapContainsVisibleText(AccessibilityNodeInfo root, String wantedPart) {
        if (root == null || wantedPart == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectContainsNodes(root, normalize(wantedPart), nodes);
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapAppInfoPermissionsRow(AccessibilityNodeInfo root) {
        if (root == null) return false;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectExactNodes(root, "uprawnienia", candidates);
        if (candidates.isEmpty()) collectExactNodes(root, "permissions", candidates);
        if (candidates.isEmpty()) collectExactNodes(root, "zezwolenia", candidates);

        // Na PM90/PM95 czasem główny tekst nie jest klikalny osobno, ale subtekst jest widoczny.
        if (candidates.isEmpty()) collectContainsNodes(root, "brak przyznanych uprawnien", candidates);
        if (candidates.isEmpty()) collectContainsNodes(root, "no permissions granted", candidates);

        for (AccessibilityNodeInfo node : candidates) {
            if (node == null) continue;
            Rect textRect = new Rect();
            node.getBoundsInScreen(textRect);
            if (textRect.isEmpty()) continue;

            AccessibilityNodeInfo row = findBestPermissionsRowParent(node, textRect.centerY());
            if (row != null) {
                Rect rowRect = new Rect();
                row.getBoundsInScreen(rowRect);
                if (!rowRect.isEmpty()) {
                    return tapAt(rowRect.centerX(), rowRect.centerY());
                }
            }

            return tapAt(textRect.centerX(), textRect.centerY());
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

    private void collectContainsNodes(AccessibilityNodeInfo node, String wantedPart, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && normalize(text.toString()).contains(wantedPart)) out.add(node);
        CharSequence desc = node.getContentDescription();
        if (desc != null && normalize(desc.toString()).contains(wantedPart)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectContainsNodes(node.getChild(i), wantedPart, out);
    }

    private AccessibilityNodeInfo findBestPermissionsRowParent(AccessibilityNodeInfo node, int expectedY) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = null;
        int bestHeight = Integer.MAX_VALUE;

        for (int i = 0; i < 8 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            String text = normalize(collectText(current));

            boolean containsPermissionRow = text.contains("uprawnienia")
                    || text.contains("permissions")
                    || text.contains("zezwolenia")
                    || text.contains("brak przyznanych uprawnien")
                    || text.contains("no permissions granted");

            boolean containsDefaultOpen = text.contains("otwieraj domyslnie")
                    || text.contains("otwieraj domyślnie")
                    || text.contains("open by default")
                    || text.contains("obslugiwane linki")
                    || text.contains("obsługiwane linki")
                    || text.contains("supported links");

            if (!rect.isEmpty()) {
                int height = rect.height();
                boolean yInside = expectedY >= rect.top && expectedY <= rect.bottom;
                boolean rowSized = height >= 36 && height <= 260;

                if (current.isClickable()
                        && current.isEnabled()
                        && yInside
                        && rowSized
                        && containsPermissionRow
                        && !containsDefaultOpen) {
                    if (height < bestHeight) {
                        best = current;
                        bestHeight = height;
                    }
                }
            }
            current = current.getParent();
        }
        return best;
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
        String text = normalize(screenText);
        return packageName.contains("settings")
                && containsTmsText(text)
                && (text.contains("uprawnienia aplikacji")
                || text.contains("app permissions")
                || text.contains("maja dostep")
                || text.contains("nie maja dostepu")
                || text.contains("dozwolone")
                || text.contains("niedozwolone")
                || text.contains("nie zezwolono")
                || text.contains("brak dostepu")
                || text.contains("allowed")
                || text.contains("not allowed"));
    }

    private void handlePermissionsList(AccessibilityNodeInfo root, String screenText) {
        if (!canClickNow()) return;

        // Najpierw klikamy pozycje wykryte pod nagłówkiem sekcji odmówionych.
        for (String permission : permissionRows) {
            if (isPermissionInDeniedSection(screenText, permission)
                    && tapPermissionRowByText(root, permission)) {
                markClicked();
                return;
            }
        }

        // Fallback dla wersji Ustawień bez czytelnego nagłówka sekcji.
        // Klikamy pierwszy wymagany wiersz, który jest widoczny i nie wygląda na już zaznaczony.
        for (String permission : permissionRows) {
            if (tapVisibleUncheckedPermissionRow(root, permission)) {
                markClicked();
                return;
            }
        }

        String text = normalize(screenText);
        boolean definitelyPermissionList = text.contains("uprawnienia aplikacji")
                || text.contains("app permissions")
                || text.contains("maja dostep")
                || text.contains("dozwolone")
                || text.contains("allowed");

        boolean stillHasDeniedSection = text.contains("nie maja dostepu")
                || text.contains("not allowed")
                || text.contains("niedozwolone")
                || text.contains("nie zezwolono")
                || text.contains("brak dostepu");

        if (definitelyPermissionList && !stillHasDeniedSection) {
            finishPermissionFlowAndCloseSettings();
        }
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
        String text = normalize(screenText);
        return packageName.contains("settings")
                && (text.contains("lokalizacja - dostep")
                || text.contains("location access")
                || text.contains("zawsze zezwalaj")
                || text.contains("allow all the time"));
    }

    private void handleLocationScreen(AccessibilityNodeInfo root) {
        if (!canClickNow()) return;

        boolean clicked = tapRuntimeChoiceExact(root,
                new String[]{"Zawsze zezwalaj", "Zezwalaj cały czas", "Zezwalaj caly czas",
                        "Allow all the time", "Always allow"});

        if (!clicked) {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            if (!b.isEmpty()) {
                // Pełny screenshot PM95 1024x2048: radio Zawsze zezwalaj ~ 9,3% x i 51,5% y.
                clicked = tapAt(b.left + (int) (b.width() * 0.093f),
                        b.top + (int) (b.height() * 0.515f));
            }
        }

        if (clicked) {
            markClicked();
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            retryCurrentPermissionWindow();

            // Druga kontrolowana próba w środek tekstu/wiersza, po czym powrót do TMS.
            handler.postDelayed(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                if (current != null && !isAlwaysLocationAlreadyChecked(current)) {
                    Rect b = new Rect();
                    current.getBoundsInScreen(b);
                    if (!b.isEmpty()) {
                        tapAt(b.left + (int) (b.width() * 0.35f),
                                b.top + (int) (b.height() * 0.515f));
                    }
                }

                handler.postDelayed(() -> {
                    AccessibilityNodeInfo verified = getRootInActiveWindow();
                    if (verified != null) enablePreciseLocationIfVisible(verified);
                    waitingForAlwaysLocation = false;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastRuntimePermissionActionTime = System.currentTimeMillis();
                    scheduleRuntimeFlowFinishCheck();
                }, 1000);
            }, 900);
        }
    }

    private void finishAlwaysLocationAndReturnToTms() {
        if (!waitingForAlwaysLocation) return;
        waitingForAlwaysLocation = false;

        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            scheduleRuntimeFlowFinishCheck();
        }, 650);
    }

    private boolean clickLocationOptionRow(AccessibilityNodeInfo root, String optionText) {
        if (root == null || optionText == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(optionText);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(optionText);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo current = node;
            for (int i = 0; i < 6 && current != null; i++) {
                Rect rect = new Rect();
                current.getBoundsInScreen(rect);
                if (!rect.isEmpty() && current.isEnabled() && current.isClickable()
                        && rect.height() >= 45 && rect.height() <= 260) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
                current = current.getParent();
            }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return tapAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private boolean tapPm95AlwaysAllowCoordinates(AccessibilityNodeInfo root, boolean radioOnly) {
        if (root == null) return false;
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        float xRatio = radioOnly ? 0.093f : 0.35f;
        int x = bounds.left + (int) (bounds.width() * xRatio);
        int y = bounds.top + (int) (bounds.height() * 0.515f);
        return tapAt(x, y);
    }

    private void verifyAlwaysLocationThenReturn() {
        handler.postDelayed(() -> {
            AccessibilityNodeInfo firstCheck = getRootInActiveWindow();
            if (firstCheck == null) return;

            if (isAlwaysLocationAlreadyChecked(firstCheck)) {
                enablePreciseLocationIfVisible(firstCheck);
                finishAlwaysLocationAndReturnToTms();
                return;
            }

            // Druga próba dokładnie w radio po lewej stronie wiersza.
            if (tapPm95AlwaysAllowCoordinates(firstCheck, true)) {
                markClicked();
            }

            handler.postDelayed(() -> {
                AccessibilityNodeInfo secondCheck = getRootInActiveWindow();
                if (secondCheck != null && isAlwaysLocationAlreadyChecked(secondCheck)) {
                    enablePreciseLocationIfVisible(secondCheck);
                    finishAlwaysLocationAndReturnToTms();
                }
            }, 900);
        }, 900);
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
        } catch (Exception ignored) {
        }
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
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 900);
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

        String[] deniedHeaders = new String[] {
                "nie maja dostepu",
                "not allowed",
                "niedozwolone",
                "nie zezwolono",
                "brak dostepu"
        };

        int deniedIndex = -1;
        for (String header : deniedHeaders) {
            int idx = text.indexOf(header);
            if (idx >= 0 && (deniedIndex < 0 || idx < deniedIndex)) {
                deniedIndex = idx;
            }
        }

        if (deniedIndex < 0) return false;
        return text.indexOf(permission, deniedIndex) > deniedIndex;
    }

    private boolean tapPermissionRowByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo row = findPermissionClickableParent(node);
            if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty() && tapAt(rect.centerX(), rect.centerY())) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findPermissionClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 7 && current != null; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            String text = normalize(collectText(current));

            boolean wrongRow = text.contains("otwieraj domyslnie")
                    || text.contains("open by default")
                    || text.contains("wyczysc dane")
                    || text.contains("odinstaluj");

            if (!wrongRow
                    && !rect.isEmpty()
                    && rect.height() >= 35
                    && rect.height() <= 300
                    && current.isEnabled()
                    && current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean tapVisibleUncheckedPermissionRow(AccessibilityNodeInfo root, String permission) {
        if (root == null || permission == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(permission);
        if (nodes == null || nodes.isEmpty()) return false;

        String wanted = normalize(permission);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            String visible = normalize(getNodeVisibleText(node));
            if (!visible.equals(wanted) && !visible.contains(wanted)) continue;

            AccessibilityNodeInfo row = findPermissionClickableParent(node);
            if (row == null) continue;

            String rowText = normalize(collectText(row));
            boolean alreadyAllowed = rowText.contains("dozwolone")
                    || rowText.contains("zezwolono")
                    || rowText.contains("allowed")
                    || rowText.contains("zawsze zezwalaj")
                    || rowText.contains("podczas uzywania");

            if (!alreadyAllowed && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        return false;
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
        hideAutomationOverlay();
    }

    private void showAutomationOverlay() {
        hideAutomationOverlay();
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
        if (MODE_OPEN_TMS.equals(mode)) {
            initialLocationSequenceScheduled = false;
            alwaysLocationSequenceScheduled = false;
            runtimePermissionsClicked = 0;
            lastRuntimePermissionActionTime = System.currentTimeMillis();
            waitingForAlwaysLocation = false;
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
        return isMode(MODE_GRANT_TMS_PERMISSIONS) || isMode(MODE_OPEN_TMS);
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
