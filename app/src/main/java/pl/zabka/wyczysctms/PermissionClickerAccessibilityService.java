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
    private static final long CLICK_DELAY_MS = 1500;
    private static final long BACK_DELAY_MS = 1600;
    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";
    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_REPAIR_TMS = "REPAIR_TMS_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";
    private long lastClickTime = 0;
    private long lastBackTime = 0;
    private boolean openedAppSettingsForMissingPermission = false;
    private final List<String> tmsPackages = Arrays.asList("pl.optidata.tms_android_2017", "pl.zabka.tms", "pl.zabka.tmsfalcon", "com.zabka.tms", "com.zabka.tmsfalcon");
    private final List<String> runtimeLocationButtons = Arrays.asList("Podczas używania aplikacji", "Podczas uzywania aplikacji", "Podczas używania tej aplikacji", "Podczas uzywania tej aplikacji", "Zezwól tylko podczas używania aplikacji", "Zezwol tylko podczas uzywania aplikacji", "Zezwalaj tylko podczas używania aplikacji", "Zezwalaj tylko podczas uzywania aplikacji", "While using the app", "While using this app", "Allow only while using the app");
    private final List<String> alwaysLocationButtons = Arrays.asList("Zawsze zezwalaj", "Zawsze pozwalaj", "Zezwalaj cały czas", "Zezwalaj caly czas", "Zezwalaj zawsze", "Allow all the time", "Always allow", "Allow always");
    private final List<String> allowButtons = Arrays.asList("Zezwól", "Zezwol", "Zezwalaj", "Allow", "OK", "Ok", "Włącz", "Wlacz", "Włączone", "Wlaczone", "Kontynuuj", "Dalej", "Potwierdź", "Potwierdz", "Zastosuj", "Rozumiem");
    private final List<String> installerButtons = Arrays.asList("Zainstaluj", "Aktualizuj", "Zaktualizuj", "Install", "Update", "Otwórz", "Otworz", "Open", "Gotowe", "Done");

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { if (event == null) return; handler.postDelayed(() -> handleScreen(event), 800); handler.postDelayed(() -> handleScreen(event), 2000); handler.postDelayed(() -> handleScreen(event), 3500); }
    @Override public void onInterrupt() {}

    private void handleScreen(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return;
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString().toLowerCase();
        String screenText = normalize(collectText(root) + " " + collectEventText(event));
        if (isAdminPanelText(screenText)) { setFlowMode(MODE_IDLE); return; }
        if (isOwnAppScreen(packageName, screenText)) return;
        if (isDetailsOnlyMode() || isIdleMode()) return;
        if (isBlockedAdminScreen(screenText)) return;
        if (canHandleUninstall() && isUninstallConfirmationDialog(packageName, screenText)) { handleUninstallConfirmation(root); return; }
        if (canHandleInstall() && isInstallerOrPackageScreen(packageName, screenText)) { clickInstallerButtons(root); return; }
        if (!canHandleTmsPermissions()) return;
        if (isRuntimePermissionDialog(packageName, screenText)) { clickRuntimePermission(root, screenText); return; }
        if (isTmsLocationPopup(screenText)) { clickTmsPermissionInfoScreen(root); return; }
        if (isAndroidCameraSettingsScreen(packageName, screenText)) { handleCameraSettings(root); return; }
        if (isAndroidLocationSettingsScreen(packageName, screenText)) { handleLocationSettings(root); return; }
        if (isAndroidNotificationSettingsScreen(packageName, screenText)) { handleNotificationSettings(root); return; }
        if (isTmsAppInfoScreen(packageName, screenText)) { handleTmsAppInfoScreen(root, screenText); return; }
        if (isAppPermissionsListScreen(packageName, screenText)) { handleAppPermissionsList(root, screenText); return; }
        if (isTmsPermissionInfoScreen(screenText)) { if (clickTmsPermissionInfoScreen(root)) return; openTmsAppSettingsFromMissingPermission(packageName, screenText); }
    }
    private boolean isAdminPanelText(String s){String v=normalize(s);return v.contains("panel administratora")||v.contains("aktywuj administratora urzadzenia")||v.contains("nadaj dostep do wszystkich plikow")||v.contains("nadaj zgode na instalowanie apk")||v.contains("szczegoly tms w ustawieniach")||v.contains("powrot do ekranu kierowcy");}
    private boolean isOwnAppScreen(String p,String s){String own=getPackageName().toLowerCase();String v=normalize(s);return p.equals(own)||p.contains("wyczysctms")||p.contains("wyczysc")||v.contains("wyczysc tms")||v.contains("panel administratora")||v.contains("napraw tms")||v.contains("otworz tms");}
    private boolean isUninstallConfirmationDialog(String p,String s){return (p.contains("packageinstaller")||p.contains("android")||p.contains("settings"))&&(s.contains("odinstalowac te aplikacje")||s.contains("odinstalowac aplikacje")||s.contains("uninstall this app")||s.contains("uninstall app"))&&containsTmsText(s);}
    private void handleUninstallConfirmation(AccessibilityNodeInfo r){if(!canClickNow())return;if(clickByTextForUninstall(r,"OK")||clickByTextForUninstall(r,"Ok")||clickByTextForUninstall(r,"Odinstaluj")||clickByTextForUninstall(r,"Uninstall"))markClicked();}
    private boolean isRuntimePermissionDialog(String p,String s){boolean sys=p.contains("permissioncontroller")||p.contains("packageinstaller")||p.contains("android")||p.contains("settings");boolean perm=s.contains("zezwol")||s.contains("zezwalaj")||s.contains("permission")||s.contains("allow")||s.contains("podczas uzywania")||s.contains("while using")||s.contains("lokalizacja")||s.contains("location")||s.contains("aparat")||s.contains("camera")||s.contains("kontakty")||s.contains("contacts")||s.contains("phone")||s.contains("zdjec")||s.contains("photos")||s.contains("nearby devices");return sys&&perm&&containsTmsText(s);}
    private void clickRuntimePermission(AccessibilityNodeInfo r,String s){if(!canClickNow())return;if((s.contains("lokalizacja")||s.contains("location")||s.contains("aparat")||s.contains("camera"))&&clickAnyText(r,runtimeLocationButtons)){markClicked();return;}if(clickAnyText(r,allowButtons))markClicked();}
    private boolean isTmsLocationPopup(String s){return containsTmsText(s)&&(s.contains("dostep do lokalizacji")||s.contains("dane lokalizacyjne")||s.contains("zaktualizuj ustawienia")||s.contains("aktualizuj ustawienia")||s.contains("location access")||s.contains("update settings"));}
    private boolean clickTmsPermissionInfoScreen(AccessibilityNodeInfo r){if(!canClickNow())return false;boolean c=clickByText(r,"ZAKTUALIZUJ USTAWIENIA")||clickByText(r,"Zaktualizuj ustawienia")||clickByText(r,"AKTUALIZUJ USTAWIENIA")||clickByText(r,"Aktualizuj ustawienia")||clickByText(r,"Ustawienia")||clickByText(r,"Update settings")||clickByText(r,"Settings");if(c)markClicked();return c;}
    private boolean isTmsPermissionInfoScreen(String s){return containsTmsText(s)&&(s.contains("cannot use this application without requested permission")||s.contains("requested permission")||s.contains("without requested permission")||s.contains("permission")||s.contains("lokalizacja")||s.contains("location"));}
    private void openTmsAppSettingsFromMissingPermission(String currentPackageName,String screenText){if(openedAppSettingsForMissingPermission)return;if(!containsTmsText(screenText)||!screenText.contains("permission"))return;String pkg=resolveTmsPackage(currentPackageName);if(pkg==null)return;openedAppSettingsForMissingPermission=true;try{Intent intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);intent.setData(Uri.parse("package:"+pkg));intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(intent);markClicked();}catch(Exception ignored){}}
    private String resolveTmsPackage(String currentPackageName){if(currentPackageName!=null){String p=currentPackageName.toLowerCase();if(p.contains("tms")||p.contains("falcon")||p.contains("zabka"))return currentPackageName;}PackageManager pm=getPackageManager();for(String p:tmsPackages){try{if(pm.getLaunchIntentForPackage(p)!=null)return p;}catch(Exception ignored){}}return null;}
    private boolean isAndroidCameraSettingsScreen(String p,String s){return (p.contains("settings")||p.contains("permissioncontroller"))&&containsTmsText(s)&&(s.contains("aparat")||s.contains("camera")||s.contains("robienie zdjec")||s.contains("nagrywanie filmow")||s.contains("take pictures")||s.contains("record video"));}
    private void handleCameraSettings(AccessibilityNodeInfo r){if(!canClickNow())return;if(clickAnyText(r,runtimeLocationButtons)||clickAnyText(r,allowButtons)){markClicked();goBackToPermissionsListLater();}}
    private boolean isAndroidLocationSettingsScreen(String p,String s){return p.contains("settings")&&containsTmsText(s)&&(s.contains("lokalizacja")||s.contains("location")||s.contains("zawsze zezwalaj")||s.contains("zezwalaj caly czas")||s.contains("allow all the time")||s.contains("while using")||s.contains("precise location")||s.contains("uzywaj dokladnej lokalizacji"));}
    private void handleLocationSettings(AccessibilityNodeInfo r){if(!canClickNow())return;String s=normalize(collectText(r));if(isBlockedAdminScreen(s))return;if(isAlwaysLocationAlreadyChecked(r)){enablePreciseLocationIfVisible(r);markClicked();goBackToPermissionsListLater();return;}if(clickAnyText(r,alwaysLocationButtons)){markClicked();handler.postDelayed(()->{AccessibilityNodeInfo cr=getRootInActiveWindow();if(cr!=null)enablePreciseLocationIfVisible(cr);},900);goBackToPermissionsListLater();return;}enablePreciseLocationIfVisible(r);}
    private boolean isAndroidNotificationSettingsScreen(String p,String s){return (p.contains("settings")||p.contains("permissioncontroller"))&&containsTmsText(s)&&(s.contains("powiadomienia")||s.contains("notifications")||s.contains("zezwalaj na powiadomienia")||s.contains("allow notifications"));}
    private void handleNotificationSettings(AccessibilityNodeInfo r){if(!canClickNow())return;String s=normalize(collectText(r));if(isBlockedAdminScreen(s))return;if(isNotificationAlreadyEnabled(r)){markClicked();goBackToPermissionsListLater();return;}if(clickSwitchNearText(r,"Powiadomienia")||clickSwitchNearText(r,"Zezwalaj na powiadomienia")||clickSwitchNearText(r,"Notifications")||clickSwitchNearText(r,"Allow notifications")){markClicked();goBackToPermissionsListLater();return;}if(clickAnyText(r,allowButtons)){markClicked();goBackToPermissionsListLater();}}
    private void goBackToPermissionsListLater(){long now=System.currentTimeMillis();if(now-lastBackTime<2500)return;lastBackTime=now;handler.postDelayed(()->performGlobalAction(GLOBAL_ACTION_BACK),BACK_DELAY_MS);}
    private boolean isTmsAppInfoScreen(String p,String s){return p.contains("settings")&&containsTmsText(s)&&(s.contains("o aplikacji")||s.contains("informacje o aplikacji")||s.contains("app info")||s.contains("uprawnienia")||s.contains("permissions"));}
    private void handleTmsAppInfoScreen(AccessibilityNodeInfo r,String s){if(!canClickNow()||isBlockedAdminScreen(s))return;if(clickByText(r,"Uprawnienia")||clickByText(r,"Permissions")||clickByText(r,"Zezwolenia"))markClicked();}
    private boolean isAppPermissionsListScreen(String p,String s){return p.contains("settings")&&containsTmsText(s)&&(s.contains("uprawnienia aplikacji")||s.contains("app permissions")||s.contains("maja dostep")||s.contains("nie maja dostepu")||s.contains("allowed")||s.contains("not allowed"));}
    private void handleAppPermissionsList(AccessibilityNodeInfo r,String s){if(!canClickNow()||isBlockedAdminScreen(s))return;if(isPermissionInDeniedSection(s,"aparat")||isPermissionInDeniedSection(s,"camera")){if(tapPermissionRowByText(r,"Aparat")||tapPermissionRowByText(r,"Camera")){markClicked();return;}}if(isPermissionInDeniedSection(s,"powiadomienia")||isPermissionInDeniedSection(s,"notifications")){if(tapPermissionRowByText(r,"Powiadomienia")||tapPermissionRowByText(r,"Notifications")){markClicked();return;}}if(isPermissionInDeniedSection(s,"lokalizacja")||isPermissionInDeniedSection(s,"location")){if(tapPermissionRowByText(r,"Lokalizacja")||tapPermissionRowByText(r,"Location")){markClicked();return;}}handler.postDelayed(this::openTmsApp,1200);}
    private boolean isPermissionInDeniedSection(String screenText,String permissionName){if(screenText==null||permissionName==null)return false;String text=normalize(screenText);String perm=normalize(permissionName);int denied=text.indexOf("nie maja dostepu");if(denied<0)denied=text.indexOf("not allowed");if(denied<0)return false;int idx=text.indexOf(perm,denied);return idx>denied;}
    private boolean tapPermissionRowByText(AccessibilityNodeInfo root,String text){if(root==null||text==null)return false;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);if(nodes==null||nodes.isEmpty())return false;String wanted=normalize(text);for(AccessibilityNodeInfo n:nodes){if(n==null)continue;String nt=normalize(getNodeVisibleText(n));if(!nt.equals(wanted)&&!nt.contains(wanted))continue;Rect rect=new Rect();n.getBoundsInScreen(rect);if(rect.isEmpty())continue;return tapAt(rect.centerX(),rect.centerY());}return false;}
    private boolean tapAt(int x,int y){if(x<=0||y<=0)return false;try{Path path=new Path();path.moveTo(x,y);GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,0,100);GestureDescription gesture=new GestureDescription.Builder().addStroke(stroke).build();return dispatchGesture(gesture,null,null);}catch(Exception ignored){return false;}}
    private boolean isInstallerOrPackageScreen(String p,String s){boolean inst=p.contains("packageinstaller")||p.contains("permissioncontroller")||p.contains("files")||p.contains("documentsui");boolean ok=s.contains("zainstaluj")||s.contains("aktualizuj")||s.contains("install")||s.contains("update")||s.contains("otworz")||s.contains("open")||s.contains("gotowe")||s.contains("done");boolean danger=s.contains("odinstaluj")||s.contains("uninstall")||s.contains("dezaktywuj")||s.contains("deactivate")||s.contains("wyczysc dane")||s.contains("clear data");return inst&&ok&&!danger;}
    private void clickInstallerButtons(AccessibilityNodeInfo r){if(!canClickNow())return;if(clickAnyText(r,installerButtons))markClicked();}
    private boolean isAlwaysLocationAlreadyChecked(AccessibilityNodeInfo r){return isTextOptionChecked(r,"Zawsze zezwalaj")||isTextOptionChecked(r,"Zezwalaj cały czas")||isTextOptionChecked(r,"Zezwalaj caly czas")||isTextOptionChecked(r,"Zezwalaj zawsze")||isTextOptionChecked(r,"Allow all the time")||isTextOptionChecked(r,"Always allow");}
    private boolean isNotificationAlreadyEnabled(AccessibilityNodeInfo r){return isTextOptionChecked(r,"Powiadomienia")||isTextOptionChecked(r,"Zezwalaj na powiadomienia")||isTextOptionChecked(r,"Notifications")||isTextOptionChecked(r,"Allow notifications");}
    private boolean isTextOptionChecked(AccessibilityNodeInfo root,String text){if(root==null||text==null)return false;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);if(nodes==null||nodes.isEmpty())return false;for(AccessibilityNodeInfo n:nodes){AccessibilityNodeInfo c=n;for(int i=0;i<5&&c!=null;i++){if(containsCheckedNode(c))return true;c=c.getParent();}}return false;}
    private boolean containsCheckedNode(AccessibilityNodeInfo n){if(n==null)return false;if(n.isChecked())return true;for(int i=0;i<n.getChildCount();i++){if(containsCheckedNode(n.getChild(i)))return true;}return false;}
    private void enablePreciseLocationIfVisible(AccessibilityNodeInfo r){String s=normalize(collectText(r));if(s.contains("uzywaj dokladnej lokalizacji")||s.contains("precise location")){if(isTextOptionChecked(r,"Używaj dokładnej lokalizacji")||isTextOptionChecked(r,"Uzywaj dokladnej lokalizacji")||isTextOptionChecked(r,"Precise location"))return;if(clickSwitchNearText(r,"Używaj dokładnej lokalizacji")||clickSwitchNearText(r,"Uzywaj dokladnej lokalizacji")||clickSwitchNearText(r,"Precise location"))markClicked();}}
    private void openTmsApp(){PackageManager pm=getPackageManager();for(String p:tmsPackages){try{Intent li=pm.getLaunchIntentForPackage(p);if(li!=null){li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(li);resetFlowFlagsLater();return;}}catch(Exception ignored){}}resetFlowFlagsLater();}
    private void resetFlowFlagsLater(){handler.postDelayed(()->{openedAppSettingsForMissingPermission=false;if(isMode(MODE_OPEN_TMS))setFlowMode(MODE_IDLE);},3500);}
    private boolean clickAnyText(AccessibilityNodeInfo r,List<String> texts){if(r==null||texts==null)return false;for(String t:texts){if(clickByText(r,t))return true;}return false;}
    private boolean clickByText(AccessibilityNodeInfo r,String text){if(r==null||text==null)return false;List<AccessibilityNodeInfo> nodes=r.findAccessibilityNodeInfosByText(text);if(nodes==null||nodes.isEmpty())return false;String wanted=normalize(text);for(AccessibilityNodeInfo n:nodes){if(n==null)continue;String nt=normalize(getNodeVisibleText(n));if(isDangerousText(nt))continue;boolean exact=nt.equals(wanted);boolean contains=wanted.length()>=8&&nt.contains(wanted)&&!isDangerousText(nt);if(!exact&&!contains)continue;AccessibilityNodeInfo c=findClickableParentSafe(n);if(c!=null&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}return false;}
    private boolean clickByTextForUninstall(AccessibilityNodeInfo r,String text){if(r==null||text==null)return false;List<AccessibilityNodeInfo> nodes=r.findAccessibilityNodeInfosByText(text);if(nodes==null||nodes.isEmpty())return false;String wanted=normalize(text);for(AccessibilityNodeInfo n:nodes){if(n==null)continue;String nt=normalize(getNodeVisibleText(n));if(!nt.equals(wanted)&&!nt.contains(wanted))continue;AccessibilityNodeInfo c=findClickableParentForUninstall(n);if(c!=null&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}return false;}
    private AccessibilityNodeInfo findClickableParentSafe(AccessibilityNodeInfo n){AccessibilityNodeInfo c=n;while(c!=null){String ft=normalize(collectText(c));if(isBlockedAdminScreen(ft)||isDangerousText(ft))return null;if(c.isClickable()&&c.isEnabled())return c;c=c.getParent();}return null;}
    private AccessibilityNodeInfo findClickableParentForUninstall(AccessibilityNodeInfo n){AccessibilityNodeInfo c=n;while(c!=null){String ft=normalize(collectText(c));if(isBlockedAdminScreen(ft))return null;if(c.isClickable()&&c.isEnabled())return c;c=c.getParent();}return null;}
    private boolean clickSwitchNearText(AccessibilityNodeInfo r,String text){if(r==null||text==null)return false;List<AccessibilityNodeInfo> nodes=r.findAccessibilityNodeInfosByText(text);if(nodes==null||nodes.isEmpty())return false;for(AccessibilityNodeInfo n:nodes){AccessibilityNodeInfo p=n;for(int i=0;i<5&&p!=null;i++){if(clickFirstSwitchOrClickableChild(p))return true;p=p.getParent();}}return false;}
    private boolean clickFirstSwitchOrClickableChild(AccessibilityNodeInfo n){if(n==null)return false;String whole=normalize(collectText(n));if(isBlockedAdminScreen(whole)||isDangerousText(whole))return false;CharSequence cls=n.getClassName();if(cls!=null){String c=cls.toString().toLowerCase();if((c.contains("switch")||c.contains("checkbox"))&&n.isEnabled()&&n.isClickable()&&!n.isChecked())return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}if(n.isClickable()&&n.isEnabled()){String nt=normalize(getNodeVisibleText(n));if(nt.contains("doklad")||nt.contains("precise")||nt.contains("powiadom")||nt.contains("notification"))return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}for(int i=0;i<n.getChildCount();i++){if(clickFirstSwitchOrClickableChild(n.getChild(i)))return true;}return false;}
    private String getNodeVisibleText(AccessibilityNodeInfo n){if(n==null)return "";StringBuilder b=new StringBuilder();CharSequence t=n.getText();CharSequence d=n.getContentDescription();if(t!=null)b.append(t).append(" ");if(d!=null)b.append(d).append(" ");return b.toString().trim();}
    private boolean canClickNow(){return System.currentTimeMillis()-lastClickTime>=CLICK_DELAY_MS;}
    private void markClicked(){lastClickTime=System.currentTimeMillis();}
    private String collectText(AccessibilityNodeInfo n){StringBuilder b=new StringBuilder();collectTextRecursive(n,b);return b.toString();}
    private void collectTextRecursive(AccessibilityNodeInfo n,StringBuilder b){if(n==null)return;CharSequence t=n.getText();if(t!=null)b.append(t).append(" ");CharSequence d=n.getContentDescription();if(d!=null)b.append(d).append(" ");for(int i=0;i<n.getChildCount();i++)collectTextRecursive(n.getChild(i),b);}
    private String collectEventText(AccessibilityEvent e){if(e==null||e.getText()==null)return "";StringBuilder b=new StringBuilder();for(CharSequence t:e.getText())if(t!=null)b.append(t).append(" ");CharSequence d=e.getContentDescription();if(d!=null)b.append(d).append(" ");return b.toString();}
    private boolean isBlockedAdminScreen(String t){String v=normalize(t);return v.contains("administratorzy urzadzenia")||v.contains("aplikacje administratora urzadzenia")||v.contains("administrator urzadzenia")||v.contains("device admin")||v.contains("device administrator")||v.contains("admin apps")||v.contains("aktywuj tego administratora")||v.contains("aktywowac tego administratora")||v.contains("dezaktywuj tego administratora")||v.contains("deactivate this device admin");}
    private boolean isDangerousText(String t){String v=normalize(t);return v.contains("odinstaluj")||v.contains("uninstall")||v.contains("usun")||v.contains("delete")||v.contains("wyczysc dane")||v.contains("clear data")||v.contains("wyczysc miejsce")||v.contains("clear storage")||v.contains("resetuj")||v.contains("dezaktywuj")||v.contains("deactivate");}
    private boolean containsTmsText(String t){String v=normalize(t);return v.contains("zabka")||v.contains("tms")||v.contains("tmsfalcon")||v.contains("falcon");}
    private String getFlowMode(){return getSharedPreferences(PREFS_NAME,MODE_PRIVATE).getString(KEY_FLOW_MODE,MODE_IDLE);}
    private void setFlowMode(String m){getSharedPreferences(PREFS_NAME,MODE_PRIVATE).edit().putString(KEY_FLOW_MODE,m).apply();}
    private boolean isMode(String e){return e.equals(getFlowMode());}
    private boolean canHandleUninstall(){return isMode(MODE_UNINSTALL_TMS)||isMode(MODE_REPAIR_TMS);}
    private boolean canHandleInstall(){return isMode(MODE_INSTALL_TMS)||isMode(MODE_REPAIR_TMS);}
    private boolean canHandleTmsPermissions(){return isMode(MODE_OPEN_TMS)||isMode(MODE_REPAIR_TMS);}
    private boolean isDetailsOnlyMode(){return isMode(MODE_DETAILS_ONLY);}
    private boolean isIdleMode(){return isMode(MODE_IDLE);}
    private String normalize(String t){if(t==null)return "";return t.toLowerCase().replace("ą","a").replace("ć","c").replace("ę","e").replace("ł","l").replace("ń","n").replace("ó","o").replace("ś","s").replace("ż","z").replace("ź","z").trim();}
}
