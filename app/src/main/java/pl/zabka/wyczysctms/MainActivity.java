package pl.zabka.wyczysctms;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {

    private static final String ADMIN_PIN = "1010";
    private static final String DEFAULT_TMS_PACKAGE = "pl.optidata.tms_android_2017";

    private static final String PREFS_NAME = "wyczysctms_prefs";
    private static final String KEY_FLOW_MODE = "flow_mode";

    private static final String MODE_IDLE = "IDLE";
    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";
    private static final String MODE_UNINSTALL_TMS = "UNINSTALL_TMS_FLOW";
    private static final String MODE_INSTALL_TMS = "INSTALL_TMS_FLOW";
    private static final String MODE_OPEN_TMS = "OPEN_TMS_FLOW";
    private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";
    private static final String MODE_GRANT_TMS_PERMISSIONS = "GRANT_TMS_PERMISSIONS_FLOW";

    private static final long OPEN_TMS_AUTOMATION_DELAY_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String detectedTmsPackage = DEFAULT_TMS_PACKAGE;
    private LinearLayout statusBox;
    private int failedPinAttempts = 0;

    private boolean waitingForInstallResult = false;
    private String pendingInstallPackage = DEFAULT_TMS_PACKAGE;
    private long pendingInstallVersionCode = -1;

    private boolean repairAfterUninstall = false;
    private boolean grantPermissionsAfterInstall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        detectedTmsPackage = DEFAULT_TMS_PACKAGE;
        buildDriverScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (statusBox != null) {
            refreshStatus();
        }

        if (repairAfterUninstall && !isInstalled(detectedTmsPackage)) {
            repairAfterUninstall = false;
            grantPermissionsAfterInstall = true;
            setFlowMode(MODE_FULL_REPAIR);
            showRepairInProgressScreen();
            Toast.makeText(this, "TMS odinstalowany. Uruchamiam instalację.", Toast.LENGTH_LONG).show();
            installNewestTmsFromDownload();
            return;
        }

        if (waitingForInstallResult && isInstalledVersionAtLeast(pendingInstallPackage, pendingInstallVersionCode)) {
            waitingForInstallResult = false;
            detectedTmsPackage = pendingInstallPackage;

            if (grantPermissionsAfterInstall || MODE_FULL_REPAIR.equals(getFlowMode())) {
                grantPermissionsAfterInstall = false;
                showRepairInProgressScreen();
                Toast.makeText(this, "TMS zainstalowany. Nadaję uprawnienia.", Toast.LENGTH_LONG).show();
                handler.postDelayed(this::grantTmsPermissionsAfterInstall, 1200);
            } else if (MODE_INSTALL_TMS.equals(getFlowMode())) {
                clearFlowMode();
                Toast.makeText(this, "TMS zainstalowany.", Toast.LENGTH_LONG).show();
                if (statusBox != null) {
                    refreshStatus();
                }
            }
        }
    }

    private void buildDriverScreen() {

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(36), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(root);

        TextView heroIcon = new TextView(this);
        heroIcon.setText("🧹");
        heroIcon.setTextSize(48);
        heroIcon.setGravity(Gravity.CENTER);
        root.addView(heroIcon, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Wyczyść TMS");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(16, 24, 40));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Szybka naprawa aplikacji TMS na urządzeniu");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(102, 112, 133));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(26));
        root.addView(subtitle);

        Button repair = primaryButton("Napraw TMS");
        repair.setOnClickListener(v -> showRepairDialog());
        root.addView(repair);

        Button openTms = secondaryButton("Otwórz TMS");
        openTms.setOnClickListener(v -> openTms());
        root.addView(openTms);

        TextView adminLink = new TextView(this);
        adminLink.setText("Panel administratora");
        adminLink.setTextColor(Color.rgb(71, 84, 103));
        adminLink.setGravity(Gravity.CENTER);
        adminLink.setPadding(0, dp(18), 0, dp(4));
        adminLink.setOnClickListener(v -> askAdminPin());
        root.addView(adminLink, new LinearLayout.LayoutParams(-1, -2));

        TextView version = new TextView(this);
        version.setText("v3.2 DPM permissions fallback");
        version.setTextColor(Color.rgb(152, 162, 179));
        version.setGravity(Gravity.CENTER);
        version.setTextSize(12);
        root.addView(version, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    private void buildAdminScreen() {

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Panel administratora");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(16, 24, 40));
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Konfiguracja widoczna wyłącznie dla administratora urządzenia. Kierowca powinien używać wyłącznie ekranu głównego.");
        info.setTextColor(Color.rgb(102, 112, 133));
        info.setTextSize(14);
        info.setPadding(0, dp(6), 0, dp(16));
        root.addView(info);

        statusBox = card();
        root.addView(statusBox);
        refreshStatus();

        addAdminButton(root, "Otwórz ustawienia Dostępności", v -> {
            clearFlowMode();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        addAdminButton(root, "Aktywuj administratora urządzenia", v -> {
            clearFlowMode();
            Toast.makeText(this, "Otwieram aktywację administratora urządzenia.", Toast.LENGTH_SHORT).show();
            activateAdmin();
        });

        addAdminButton(root, "Nadaj dostęp do wszystkich plików", v -> {
            clearFlowMode();
            requestAllFilesAccess();
        });

        addAdminButton(root, "Nadaj zgodę na instalowanie APK", v -> {
            clearFlowMode();
            requestInstallUnknownAppsAccess();
        });

        addAdminButton(root, "1. Odinstaluj TMS", v -> {
            setFlowMode(MODE_UNINSTALL_TMS);
            uninstallTms();
        });

        addAdminButton(root, "2. Zainstaluj najnowszy TMS z Download", v -> {
            setFlowMode(MODE_INSTALL_TMS);
            installNewestTmsFromDownload();
        });

        addAdminButton(root, "Nadaj uprawnienia TMS programowo", v -> grantTmsPermissionsAfterInstall());

        addAdminButton(root, "Nadaj uprawnienia TMS przez ustawienia", v -> grantTmsPermissionsThenOpen());

        addAdminButton(root, "3. Otwórz TMS", v -> openTms());

        addAdminButton(root, "Szczegóły TMS w ustawieniach", v -> {
            setFlowMode(MODE_DETAILS_ONLY);
            openTmsSettings();
        });

        addAdminButton(root, "STOP automatyzacji", v -> { clearFlowMode(); Toast.makeText(this, "Automatyzacja zatrzymana.", Toast.LENGTH_LONG).show(); });

        addAdminButton(root, "Odśwież status", v -> refreshStatus());

        Button backToDriver = highlightedBackButton("Powrót do ekranu kierowcy");
        backToDriver.setOnClickListener(v -> {
            clearFlowMode();
            buildDriverScreen();
        });
        root.addView(backToDriver);

        setContentView(scroll);
    }

    private void refreshStatus() {
        if (statusBox == null) {
            return;
        }

        statusBox.removeAllViews();
        File newestApk = findNewestTmsApkInDownload();

        addStatusLine("Administrator urządzenia", isDeviceAdminActive() ? "OK" : "BRAK", isDeviceAdminActive());
        addStatusLine("Device Owner / Profile Owner", canGrantRuntimePermissionsByPolicy() ? "TAK" : "NIE", canGrantRuntimePermissionsByPolicy());
        addStatusLine("Usługa pomocnicza", isAccessibilityEnabled() ? "OK" : "BRAK", isAccessibilityEnabled());
        addStatusLine("Dostęp do plików", hasAllFilesAccess() ? "OK" : "BRAK", hasAllFilesAccess());
        addStatusLine("APK TMS w Download", newestApk != null ? "OK" : "BRAK", newestApk != null);
        if (newestApk != null) {
            addStatusLine("Wybrany plik", newestApk.getName(), true);
        }
        addStatusLine("Pakiet TMS", detectedTmsPackage, true);
        addStatusLine("TMS zainstalowany", isInstalled(detectedTmsPackage) ? "TAK" : "NIE", isInstalled(detectedTmsPackage));
        addStatusLine("Tryb działania", getFlowMode(), true);
    }

    private void showRepairInProgressScreen() {
        Toast.makeText(this, "Naprawa TMS w toku.", Toast.LENGTH_LONG).show();
    }

    private void showRepairDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Naprawa TMS")
                .setMessage("Aplikacja odinstaluje TMS, zainstaluje najnowszą wersję APK z Download, ustawi lokalizację w Ustawieniach i uruchomi TMS, aby nadać pozostałe zgody.")
                .setPositiveButton("Napraw TMS", (d, w) -> repairTms())
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void askAdminPin() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(22);

        new AlertDialog.Builder(this)
                .setTitle("Panel administratora")
                .setMessage("Wpisz PIN administratora")
                .setView(input)
                .setPositiveButton("Wejdź", (d, which) -> {
                    if (ADMIN_PIN.equals(input.getText().toString())) {
                        failedPinAttempts = 0;
                        buildAdminScreen();
                    } else {
                        failedPinAttempts++;
                        if (failedPinAttempts >= 3) {
                            Toast.makeText(this, "Zbyt wiele błędnych prób. Uruchom aplikację ponownie.", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            Toast.makeText(this, "Nieprawidłowy PIN", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(234, 236, 240));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(18));
        c.setLayoutParams(lp);
        return c;
    }

    private void addStatusLine(String label, String value, boolean ok) {
        TextView t = new TextView(this);
        t.setText(label + ": " + value);
        t.setTextSize(14);
        t.setTextColor(ok ? Color.rgb(2, 122, 72) : Color.rgb(180, 35, 24));
        t.setPadding(0, dp(3), 0, dp(3));
        statusBox.addView(t);
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0, 168, 107));
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.setMargins(0, 0, 0, dp(12));
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.rgb(16, 24, 40));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(208, 213, 221));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(10));
        b.setLayoutParams(lp);
        return b;
    }

    private Button highlightedBackButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(37, 99, 235));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, dp(8), 0, dp(10));
        b.setLayoutParams(lp);
        return b;
    }

    private void addAdminButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button b = secondaryButton(text);
        b.setOnClickListener(listener);
        root.addView(b);
    }

    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, ResetDeviceAdminReceiver.class);
        return dpm != null && dpm.isAdminActive(admin);
    }

    private boolean canGrantRuntimePermissionsByPolicy() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) {
                return false;
            }
            return dpm.isDeviceOwnerApp(getPackageName()) || dpm.isProfileOwnerApp(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInstalledVersionAtLeast(String pkg, long expectedVersionCode) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            long installedVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            return expectedVersionCode <= 0 || installedVersionCode >= expectedVersionCode;
        } catch (Exception e) {
            return false;
        }
    }

    private void activateAdmin() {
        ComponentName admin = new ComponentName(this, ResetDeviceAdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Włącz administratora dla aplikacji Wyczyść TMS.");
        startActivity(intent);
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } else {
                Toast.makeText(this, "Dostęp do wszystkich plików już nadany", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Na tej wersji Androida dodatkowy dostęp nie jest wymagany", Toast.LENGTH_LONG).show();
        }
    }

    private void requestInstallUnknownAppsAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Na tej wersji Androida osobna zgoda na instalację APK nie jest wymagana.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Nie można otworzyć ustawień instalowania APK: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void repairTms() {
        closeTmsBeforeRepair();
        clearFlowMode();
        setFlowMode(MODE_FULL_REPAIR);
        grantPermissionsAfterInstall = true;
        repairAfterUninstall = false;
        waitingForInstallResult = false;
        showRepairInProgressScreen();

        File newestApk = findNewestTmsApkInDownload();
        if (newestApk == null) {
            Toast.makeText(this, "Nie znaleziono pliku TMS APK w folderze Download.", Toast.LENGTH_LONG).show();
            clearFlowMode();
            return;
        }

        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(newestApk.getAbsolutePath(), 0);
        if (packageInfo != null && packageInfo.packageName != null) {
            detectedTmsPackage = packageInfo.packageName;
            pendingInstallPackage = packageInfo.packageName;
        }

        if (isInstalled(detectedTmsPackage)) {
            repairAfterUninstall = true;
            Toast.makeText(this, "Odinstalowuję TMS. Po powrocie uruchomi się instalacja.", Toast.LENGTH_LONG).show();
            uninstallTms();
        } else {
            Toast.makeText(this, "TMS nie jest zainstalowany. Uruchamiam instalację.", Toast.LENGTH_LONG).show();
            installNewestTmsFromDownload();
        }
    }


    private void closeTmsBeforeRepair() {
        try {
            ActivityManager activityManager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.killBackgroundProcesses(detectedTmsPackage);
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this,
                "Przed naprawą aplikacja TMS powinna być zamknięta. Rozpoczynam bezpieczną naprawę.",
                Toast.LENGTH_LONG).show();
    }

    private void uninstallTms() {
        String mode = getFlowMode();
        if (!MODE_FULL_REPAIR.equals(mode)) {
            setFlowMode(MODE_UNINSTALL_TMS);
        }
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private void installNewestTmsFromDownload() {
        String mode = getFlowMode();
        if (!MODE_FULL_REPAIR.equals(mode)) {
            setFlowMode(MODE_INSTALL_TMS);
        }
        try {
            File newestApk = findNewestTmsApkInDownload();
            if (newestApk == null) {
                Toast.makeText(this, "Nie znaleziono pliku TMS APK w folderze Download.", Toast.LENGTH_LONG).show();
                clearFlowMode();
                return;
            }

            PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(newestApk.getAbsolutePath(), 0);
            if (packageInfo != null && packageInfo.packageName != null) {
                pendingInstallPackage = packageInfo.packageName;
                detectedTmsPackage = packageInfo.packageName;
                pendingInstallVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
            } else {
                pendingInstallPackage = detectedTmsPackage;
                pendingInstallVersionCode = -1;
            }

            waitingForInstallResult = true;
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", newestApk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            waitingForInstallResult = false;
            clearFlowMode();
            Toast.makeText(this, "Błąd instalacji TMS z Download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File findNewestTmsApkInDownload() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.exists()) {
            return null;
        }

        File[] files = downloadDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File newestFile = null;
        long newestVersionCode = -1;

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }

            String fileName = file.getName().toLowerCase();
            if (!fileName.endsWith(".apk")) {
                continue;
            }
            if (!fileName.contains("tms") && !fileName.contains("zabka") && !fileName.contains("falcon")) {
                continue;
            }

            PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (packageInfo == null) {
                continue;
            }

            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? packageInfo.getLongVersionCode() : packageInfo.versionCode;

            if (versionCode > newestVersionCode) {
                newestVersionCode = versionCode;
                newestFile = file;
                if (packageInfo.packageName != null) {
                    detectedTmsPackage = packageInfo.packageName;
                }
            }
        }
        return newestFile;
    }

    private void openTms() {
        clearFlowMode();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTmsPackage);
        if (launchIntent != null) {
            startActivity(launchIntent);
            Toast.makeText(this, "Uruchamiam TMS.", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> setFlowMode(MODE_OPEN_TMS), OPEN_TMS_AUTOMATION_DELAY_MS);
        } else {
            Toast.makeText(this, "Nie znaleziono TMS: " + detectedTmsPackage, Toast.LENGTH_LONG).show();
        }
    }

    private void openTmsSettings() {
        if (!MODE_GRANT_TMS_PERMISSIONS.equals(getFlowMode())) {
            setFlowMode(MODE_DETAILS_ONLY);
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(this,
                "Uruchamiam TMS i nadaję pozostałe zgody.",
                Toast.LENGTH_LONG).show();
        launchTmsForRuntimePermissions();
    }


    private void openTmsSettingsBeforeFirstLaunch() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            clearFlowMode();
            Toast.makeText(
                    this,
                    "Nie można otworzyć ustawień TMS: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void launchTmsForRuntimePermissions() {
        setFlowMode(MODE_OPEN_TMS);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTmsPackage);
        if (launchIntent == null) {
            clearFlowMode();
            Toast.makeText(this, "Nie znaleziono aplikacji TMS po instalacji.", Toast.LENGTH_LONG).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launchIntent);
    }

    private boolean grantTmsPermissionsByPolicy() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, ResetDeviceAdminReceiver.class);

        if (dpm == null) {
            return false;
        }

        if (!canGrantRuntimePermissionsByPolicy()) {
            Toast.makeText(this, "Brak Device Owner/Profile Owner. Uruchamiam nadawanie przez ustawienia.", Toast.LENGTH_LONG).show();
            return false;
        }

        boolean allOk = true;

        allOk &= grantSinglePermission(dpm, admin, Manifest.permission.CAMERA);
        allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_CONTACTS);
        allOk &= grantSinglePermission(dpm, admin, Manifest.permission.ACCESS_FINE_LOCATION);
        allOk &= grantSinglePermission(dpm, admin, Manifest.permission.ACCESS_COARSE_LOCATION);
        allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_PHONE_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.POST_NOTIFICATIONS);
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_MEDIA_IMAGES);
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_MEDIA_VIDEO);
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.READ_EXTERNAL_STORAGE);
            allOk &= grantSinglePermission(dpm, admin, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        return allOk;
    }

    private boolean grantSinglePermission(DevicePolicyManager dpm, ComponentName admin, String permission) {
        try {
            return dpm.setPermissionGrantState(
                    admin,
                    detectedTmsPackage,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            );
        } catch (Exception e) {
            return false;
        }
    }

    private void grantTmsPermissionsThenOpen() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        Toast.makeText(this, "Otwieram informacje o aplikacji TMS. Przejdę do uprawnień automatycznie.", Toast.LENGTH_LONG).show();

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Nie można otworzyć ustawień TMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    

    private void setFlowMode(String mode) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FLOW_MODE, mode).apply();
    }

    private String getFlowMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FLOW_MODE, MODE_IDLE);
    }

    private void clearFlowMode() {
        setFlowMode(MODE_IDLE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
