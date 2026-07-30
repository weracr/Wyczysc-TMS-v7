package pl.zabka.wyczysctms;

import android.app.Activity;
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

    private String detectedTmsPackage = DEFAULT_TMS_PACKAGE;
    private LinearLayout statusBox;
    private int failedPinAttempts = 0;

    private boolean waitingForInstallResult = false;
    private String pendingInstallPackage = DEFAULT_TMS_PACKAGE;
    private long pendingInstallVersionCode = -1;

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

        if (waitingForInstallResult) {
            if (isInstalledVersionAtLeast(pendingInstallPackage, pendingInstallVersionCode)) {
                waitingForInstallResult = false;

                Toast.makeText(
                        this,
                        "Sukces, aplikacja zainstalowana.",
                        Toast.LENGTH_LONG
                ).show();

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
        version.setText("v2.0");
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

        addAdminButton(root, "Otwórz ustawienia Dostępności", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );

        addAdminButton(root, "Aktywuj administratora urządzenia", v -> activateAdmin());

        addAdminButton(
                root,
                "Nadaj dostęp do wszystkich plików",
                v -> requestAllFilesAccess()
        );

        addAdminButton(root, "1. Odinstaluj TMS", v -> uninstallTms());
        addAdminButton(root, "2. Zainstaluj najnowszy TMS z Download", v -> installNewestTmsFromDownload());
        addAdminButton(root, "3. Otwórz TMS", v -> openTms());
        addAdminButton(root, "Szczegóły TMS w ustawieniach", v -> openTmsSettings());
        addAdminButton(root, "Odśwież status", v -> refreshStatus());
        addAdminButton(root, "Powrót do ekranu kierowcy", v -> buildDriverScreen());

        setContentView(scroll);
    }

    private void refreshStatus() {
        if (statusBox == null) {
            return;
        }

        statusBox.removeAllViews();

        File newestApk = findNewestTmsApkInDownload();

        addStatusLine(
                "Administrator urządzenia",
                isDeviceAdminActive() ? "OK" : "BRAK",
                isDeviceAdminActive()
        );

        addStatusLine(
                "Usługa pomocnicza",
                isAccessibilityEnabled() ? "OK" : "BRAK",
                isAccessibilityEnabled()
        );

        addStatusLine(
                "Dostęp do plików",
                hasAllFilesAccess() ? "OK" : "BRAK",
                hasAllFilesAccess()
        );

        addStatusLine(
                "APK TMS w Download",
                newestApk != null ? "OK" : "BRAK",
                newestApk != null
        );

        if (newestApk != null) {
            addStatusLine("Wybrany plik", newestApk.getName(), true);
        }

        addStatusLine("Pakiet TMS", detectedTmsPackage, true);

        addStatusLine(
                "TMS zainstalowany",
                isInstalled(detectedTmsPackage) ? "TAK" : "NIE",
                isInstalled(detectedTmsPackage)
        );
    }

    private void showRepairDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Naprawa TMS")
                .setMessage("Aplikacja może odinstalować TMS. Instalacja najnowszej wersji z folderu Download jest dostępna w panelu administratora.")
                .setPositiveButton("Odinstaluj TMS", (d, w) -> uninstallTms())
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
                            Toast.makeText(
                                    this,
                                    "Zbyt wiele błędnych prób. Uruchom aplikację ponownie.",
                                    Toast.LENGTH_LONG
                            ).show();
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

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabled == null) {
            return false;
        }

        return enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }

        return true;
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

            long installedVersionCode;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedVersionCode = info.getLongVersionCode();
            } else {
                installedVersionCode = info.versionCode;
            }

            if (expectedVersionCode <= 0) {
                return true;
            }

            return installedVersionCode >= expectedVersionCode;

        } catch (Exception e) {
            return false;
        }
    }

    private void activateAdmin() {
        ComponentName admin = new ComponentName(this, ResetDeviceAdminReceiver.class);

        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Włącz administratora dla aplikacji Wyczyść TMS."
        );

        startActivity(intent);
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            } else {
                Toast.makeText(
                        this,
                        "Dostęp do wszystkich plików już nadany",
                        Toast.LENGTH_LONG
                ).show();
            }
        } else {
            Toast.makeText(
                    this,
                    "Na tej wersji Androida dodatkowy dostęp nie jest wymagany",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void uninstallTms() {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private void installNewestTmsFromDownload() {
        try {
            File newestApk = findNewestTmsApkInDownload();

            if (newestApk == null) {
                Toast.makeText(
                        this,
                        "Nie znaleziono pliku TMS APK w folderze Download.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(
                    newestApk.getAbsolutePath(),
                    0
            );

            if (packageInfo != null && packageInfo.packageName != null) {
                pendingInstallPackage = packageInfo.packageName;
                detectedTmsPackage = packageInfo.packageName;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pendingInstallVersionCode = packageInfo.getLongVersionCode();
                } else {
                    pendingInstallVersionCode = packageInfo.versionCode;
                }
            } else {
                pendingInstallPackage = detectedTmsPackage;
                pendingInstallVersionCode = -1;
            }

            waitingForInstallResult = true;

            Uri apkUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    newestApk
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(intent);

        } catch (Exception e) {
            waitingForInstallResult = false;

            Toast.makeText(
                    this,
                    "Błąd instalacji TMS z Download: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

private File findNewestTmsApkInDownload() {
    File downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
    );

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

        if (
                !fileName.contains("tms") &&
                !fileName.contains("zabka") &&
                !fileName.contains("falcon")
        ) {
            continue;
        }

        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(
                file.getAbsolutePath(),
                0
        );

        if (packageInfo == null) {
            continue;
        }

        long versionCode;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionCode = packageInfo.getLongVersionCode();
        } else {
            versionCode = packageInfo.versionCode;
        }

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
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTmsPackage);

        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            Toast.makeText(
                    this,
                    "Nie znaleziono TMS: " + detectedTmsPackage,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openTmsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
