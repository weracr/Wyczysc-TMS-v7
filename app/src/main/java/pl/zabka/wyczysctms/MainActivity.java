package pl.zabka.wyczysctms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String ADMIN_PIN = "1010";
    private static final String DEFAULT_TMS_PACKAGE = "pl.optidata.tms_android_2017";
    private static final String ASSET_TMS_APK = "tms.apk";
    private String detectedTmsPackage = DEFAULT_TMS_PACKAGE;
    private LinearLayout statusBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        detectedTmsPackage = detectPackageFromAsset();
        buildDriverScreen();
    }

    private void buildDriverScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(root);

        TextView heroIcon = new TextView(this);
        heroIcon.setText("🧹");
        heroIcon.setTextSize(46);
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
        subtitle.setPadding(0, dp(6), 0, dp(22));
        root.addView(subtitle);

        statusBox = card();
        root.addView(statusBox);
        refreshStatus();

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
        version.setText("v6.3 • PIN admina: 1010");
        version.setTextColor(Color.rgb(152, 162, 179));
        version.setGravity(Gravity.CENTER);
        version.setTextSize(12);
        root.addView(version, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    private void refreshStatus() {
        statusBox.removeAllViews();
        addStatusLine("Administrator urządzenia", isDeviceAdminActive() ? "OK" : "BRAK", isDeviceAdminActive());
        addStatusLine("Dostępność auto zgody", isAccessibilityEnabled() ? "OK" : "BRAK", isAccessibilityEnabled());
        addStatusLine("Wbudowany APK TMS", hasAssetApk() ? "OK" : "BRAK", hasAssetApk());
        addStatusLine("Pakiet TMS", detectedTmsPackage, true);
        addStatusLine("TMS zainstalowany", isInstalled(detectedTmsPackage) ? "TAK" : "NIE", isInstalled(detectedTmsPackage));
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
                    if (ADMIN_PIN.equals(input.getText().toString())) buildAdminScreen();
                    else Toast.makeText(this, "Nieprawidłowy PIN", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Anuluj", null)
                .show();
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
        info.setText("Konfiguracja widoczna tylko po PIN-ie 1010. Kierowca powinien używać wyłącznie ekranu głównego.");
        info.setTextColor(Color.rgb(102, 112, 133));
        info.setTextSize(14);
        info.setPadding(0, dp(6), 0, dp(16));
        root.addView(info);

        statusBox = card();
        root.addView(statusBox);
        refreshStatus();

        addAdminButton(root, "Otwórz ustawienia Dostępności", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addAdminButton(root, "Aktywuj administratora urządzenia", v -> activateAdmin());
        addAdminButton(root, "1. Odinstaluj TMS", v -> uninstallTms());
        addAdminButton(root, "2. Zainstaluj TMS z assets/tms.apk", v -> installTmsFromAssets());
        addAdminButton(root, "3. Otwórz TMS", v -> openTms());
        addAdminButton(root, "Szczegóły TMS w ustawieniach", v -> openTmsSettings());
        addAdminButton(root, "Odśwież status", v -> refreshStatus());
        addAdminButton(root, "Powrót do ekranu kierowcy", v -> buildDriverScreen());

        setContentView(scroll);
    }

    private void showRepairDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Naprawa TMS")
                .setMessage("Aplikacja wykona proces ręcznie krok po kroku. Najpierw odinstaluj TMS, potem wróć i użyj instalacji z panelu administratora, jeśli test tego wymaga. W wersji finalnej można to dopracować do jednego przepływu.")
                .setPositiveButton("Odinstaluj TMS", (d, w) -> uninstallTms())
                .setNegativeButton("Anuluj", null)
                .show();
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
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        return enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private boolean hasAssetApk() {
        try { InputStream in = getAssets().open(ASSET_TMS_APK); in.close(); return true; }
        catch (Exception e) { return false; }
    }

    private String detectPackageFromAsset() {
        try {
            File outFile = copyAssetToCache();
            PackageInfo info = getPackageManager().getPackageArchiveInfo(outFile.getAbsolutePath(), 0);
            if (info != null && info.packageName != null) return info.packageName;
        } catch (Exception ignored) { }
        return DEFAULT_TMS_PACKAGE;
    }

    private File copyAssetToCache() throws Exception {
        File outFile = new File(getCacheDir(), ASSET_TMS_APK);
        InputStream in = getAssets().open(ASSET_TMS_APK);
        FileOutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        out.close();
        in.close();
        return outFile;
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    private void activateAdmin() {
        ComponentName admin = new ComponentName(this, ResetDeviceAdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Włącz administratora dla aplikacji Wyczyść TMS.");
        startActivity(intent);
    }

    private void uninstallTms() {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private void installTmsFromAssets() {
        try {
            File outFile = copyAssetToCache();
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Brak app/src/main/assets/tms.apk albo błąd instalacji: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openTms() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTmsPackage);
        if (launchIntent != null) startActivity(launchIntent);
        else Toast.makeText(this, "Nie znaleziono TMS: " + detectedTmsPackage, Toast.LENGTH_LONG).show();
    }

    private void openTmsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + detectedTmsPackage));
        startActivity(intent);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
