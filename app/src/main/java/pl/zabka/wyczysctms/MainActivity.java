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
        version.setText("v2.0");
        version.setTextColor(Color.rgb(152, 162, 179));
        version.setGravity(Gravity
