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

    private static final long CLICK_DELAY_MS = 750;

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

        /*
         * Bardzo ważne:
         * Jeśli aktywna jest sama aplikacja Wyczyść TMS, czyli panel kierowcy albo panel admina,
         * to nie klikamy absolutnie nic.
         */
        if (isOwnAppPackage(pack*geName)) {
            return;
   *    }

        String screenText =*normalize(collectText(root) + " " * collectEventText(event));

      * if (isDetailsOnlyMode() || isIdle*ode()) {
            return;
     *  }

        if (isBlockedAdminScr*en(screenText)) {
            retu*n;
        }

        if (canHandl*Uninstall() && isUninstallConfirma*ionDialog(packageName, screenText)* {
            handleUninstallConf*rmation(root);
            return;*        }

        if (canHandleIn*tall() && isInstallerOrPackageScre*n(packageName, screenText)) {
    *       clickInstallerButtons(root)*
            return;
        }

  *     if (!canHandleTmsPermissions(*) {
            return;
        }
*        if (isTmsLocationPopup(scr*enText)) {
            handleTmsLo*ationPopup(root);
            retu*n;
        }

        if (isAndroi*CameraSettingsScreen(packageName, *creenText)) {
            handleCa*eraSettings(root);
            ret*rn;
        }

        if (isAndro*dLocationSettingsScreen(packageNam*, screenText)) {
            handl*LocationSettings(root);
          * return;
        }

        if (is*ndroidNotificationSettingsScreen(p*ckageName, screenText)) {
        *   handleNotificationSettings(root*;
            return;
        }

 *      if (isTmsAppInfoScreen(packa*eName, screenText)) {
            *andleTmsAppInfoScreen(root, screen*ext);
            return;
        *

        if (isAppPermissionsList*creen(packageName, screenText)) {
*           handleAppPermissionsLis*(root, screenText);
            re*urn;
        }

        if (isRunt*mePermissionDialog(packageName, sc*eenText)) {
            clickRunti*ePermission(root, screenText);
   *        return;
        }

       *if (isTmsPermissionInfoScreen(scre*nText)) {
            if (clickTms*ermissionInfoScreen(root, screenTe*t)) {
                return;
    *       }

            openTmsAppSe*tingsFromMissingPermission(package*ame, screenText);
        }
    }
*    private boolean isOwnAppPackag*(String packageName) {
        if *packageName == null) {
           *return false;
        }

        r*turn packageName.equals(getPackage*ame().toLowerCase())
             *  || packageName.contains("pl.zabk*.wyczysctms");
    }

    private *oolean isUninstallConfirmationDial*g(String packageName, String scree*Text) {
        boolean isSystemPa*kage =
                packageName*contains("packageinstaller")
     *                  || packageName.c*ntains("android")
                *       || packageName.contains("se*tings");

        boolean contains*ninstallQuestion =
               *screenText.contains("odinstalowac *e aplikacje")
                    *   || screenText.contains("odinsta*owac aplikacje")
                 *      || screenText.contains("unin*tall this app")
                  *     || screenText.contains("unins*all app");

        return isSyste*Package && containsUninstallQuesti*n && containsTmsText(screenText);
*   }

    private void handleUnins*allConfirmation(AccessibilityNodeI*fo root) {
        if (!canClickNo*()) {
            return;
        *

        boolean clicked =
      *         clickByTextForUninstall(r*ot, "OK")
                        *| clickByTextForUninstall(root, "O*")
                        || clic*ByTextForUninstall(root, "Odinstal*j")
                        || cli*kByTextForUninstall(root, "Uninsta*l");

        if (clicked) {
     *      markClicked();
        }
   *}

    private boolean isTmsLocati*nPopup(String screenText) {
      * boolean containsLocationPopup =
 *              screenText.contains(*dostep do lokalizacji")
          *             || screenText.contain*("dane lokalizacyjne")
           *            || screenText.contains*"zaktualizuj ustawienia")
        *               || screenText.conta*ns("aktualizuj ustawienia")
      *                 || screenText.con*ains("location access")
          *             || screenText.contain*("update settings");

        retu*n containsLocationPopup && contain*TmsText(screenText);
    }

    pr*vate void handleTmsLocationPopup(A*cessibilityNodeInfo root) {
      * if (!canClickNow()) {
           *return;
        }

        boolean*clicked =
                clickByT*xt(root, "ZAKTUALIZUJ USTAWIENIA")*                        || clickBy*ext(root, "Zaktualizuj ustawienia"*
                        || clickB*Text(root, "AKTUALIZUJ USTAWIENIA"*
                        || clickB*Text(root, "Aktualizuj ustawienia"*
                        || clickB*Text(root, "Ustawienia")
         *              || clickByText(root,*"Update settings")
               *        || clickByText(root, "Sett*ngs");

        if (clicked) {
   *        returnedFromSettings = fal*e;
            markClicked();
    *   }
    }

    private boolean is*untimePermissionDialog(String pack*geName, String screenText) {
     *  boolean isSystem =
             *  packageName.contains("permission*ontroller")
                      * || packageName.contains("packagei*staller")
                        *| packageName.contains("android")
*                       || packageN*me.contains("settings");

        *oolean containsPermission =
      *         screenText.contains("zezw*l")
                        || scr*enText.contains("zezwalaj")
      *                 || screenText.con*ains("permission")
               *        || screenText.contains("al*ow")
                        || sc*eenText.contains("podczas uzywania*)
                        || scree*Text.contains("while using")
     *                  || screenText.co*tains("lokalizacja")
             *          || screenText.contains("*ocation")
                        *| screenText.contains("aparat")
  *                     || screenText*contains("camera")
               *        || screenText.contains("ko*takty")
                        ||*screenText.contains("contacts")
  *                     || screenText*contains("phone")
                *       || screenText.contains("zdj*c")
                        || scr*enText.contains("photos")
        *               || screenText.conta*ns("nearby devices");

        ret*rn isSystem && containsPermission *& containsTmsText(screenText);
   *}

    private void clickRuntimePe*mission(AccessibilityNodeInfo root* String screenText) {
        if (*canClickNow()) {
            retur*;
        }

        if (screenTex*.contains("lokalizacja")
         *      || screenText.contains("loca*ion")
                || screenTex*.contains("aparat")
              * || screenText.contains("camera"))*{

            if (clickAnyText(ro*t, runtimeLocationButtons)) {
    *           markClicked();
        *       return;
            }
     *  }

        if (clickAnyText(root* allowButtons)) {
            mark*licked();
        }
    }

    pri*ate boolean isTmsPermissionInfoScr*en(String screenText) {
        bo*lean containsPermissionProblem =
 *              screenText.contains(*cannot use this application withou* requested permission")
          *             || screenText.contain*("requested permission")
         *              || screenText.contai*s("without requested permission")
*                       || screenTe*t.contains("permission")
         *              || screenText.contai*s("lokalizacja")
                 *      || screenText.contains("loca*ion");

        return containsTms*ext(screenText) && containsPermiss*onProblem;
    }

    private bool*an clickTmsPermissionInfoScreen(Ac*essibilityNodeInfo root, String sc*eenText) {
        if (!canClickNo*()) {
            return false;
  *     }

        boolean clicked =
*               clickByText(root, "*AKTUALIZUJ USTAWIENIA")
          *             || clickByText(root, *Zaktualizuj ustawienia")
         *              || clickByText(root,*"AKTUALIZUJ USTAWIENIA")
         *              || clickByText(root,*"Aktualizuj ustawienia")
         *              || clickByText(root,*"Ustawienia")
                    *   || clickByText(root, "Settings"*;

        if (clicked) {
        *   returnedFromSettings = false;
 *          markClicked();
         *  return true;
        }

        *eturn false;
    }

    private vo*d openTmsAppSettingsFromMissingPer*ission(String currentPackageName, *tring screenText) {
        if (op*nedAppSettingsForMissingPermission* {
            return;
        }

*       if (!containsTmsText(screen*ext) || !screenText.contains("perm*ssion")) {
            return;
   *    }

        String tmsPackage =*resolveTmsPackage(currentPackageNa*e);

        if (tmsPackage == nul*) {
            return;
        }
*        openedAppSettingsForMissin*Permission = true;
        returne*FromSettings = false;

        try*{
            Intent intent = new *ntent(Settings.ACTION_APPLICATION_*ETAILS_SETTINGS);
            inte*t.setData(Uri.parse("package:" + t*sPackage));
            intent.add*lags(Intent.FLAG_ACTIVITY_NEW_TASK*;
            startActivity(intent*;
            markClicked();
     *  } catch (Exception ignored) {
  *     }
    }

    private String r*solveTmsPackage(String currentPack*geName) {
        if (currentPacka*eName != null) {
            Strin* pkg = currentPackageName.toLowerC*se();

            if (pkg.contain*("tms") || pkg.contains("falcon") *| pkg.contains("zabka")) {
       *        return currentPackageName;*            }
        }

        P*ckageManager pm = getPackageManage*
