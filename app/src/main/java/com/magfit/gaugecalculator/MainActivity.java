package com.magfit.gaugecalculator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final long UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final int REQUEST_INSTALL_PERMISSION = 4107;
    private static final String APK_ASSET_NAME = "Plain-Gauge-Calculator.apk";
    private static final String PREFS = "plain_gauge_updates";

    private WebView webView;
    private ExecutorService executor;
    private ProgressDialog progressDialog;
    private File pendingInstallFile;
    private volatile boolean updateCheckInProgress = false;

    private static final class ReleaseInfo {
        final String version;
        final String tag;
        final String downloadUrl;

        ReleaseInfo(String version, String tag, String downloadUrl) {
            this.version = version;
            this.tag = tag;
            this.downloadUrl = downloadUrl;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();

        getWindow().setStatusBarColor(Color.rgb(232, 236, 239));
        getWindow().setNavigationBarColor(Color.rgb(248, 250, 251));

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.rgb(232, 236, 239));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");

    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForUpdatesIfDue();
    }

    private void checkForUpdatesIfDue() {
        if (BuildConfig.GITHUB_OWNER.equals("YOUR_GITHUB_USERNAME") || BuildConfig.GITHUB_OWNER.trim().isEmpty()
                || BuildConfig.GITHUB_REPO.trim().isEmpty()) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long lastCheck = prefs.getLong("last_check_ms", 0L);
        long now = System.currentTimeMillis();
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS || updateCheckInProgress) return;
        updateCheckInProgress = true;

        executor.execute(() -> {
            ReleaseInfo latest = null;
            boolean success = false;
            try {
                latest = fetchLatestRelease();
                success = true;
            } catch (Exception ignored) {
                // Update checks are best-effort. The calculator remains fully usable offline.
            }
            ReleaseInfo finalLatest = latest;
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                updateCheckInProgress = false;
                if (!finalSuccess) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("last_check_ms", System.currentTimeMillis()).apply();
                if (finalLatest != null && compareVersions(finalLatest.version, BuildConfig.VERSION_NAME) > 0) {
                    showUpdateDialog(finalLatest);
                }
            });
        });
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        String apiUrl = "https://api.github.com/repos/" + BuildConfig.GITHUB_OWNER + "/"
                + BuildConfig.GITHUB_REPO + "/releases/latest";
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Plain-Gauge-Calculator-Android");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("GitHub API returned HTTP " + status);
            StringBuilder jsonText = new StringBuilder();
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) jsonText.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
            JSONObject release = new JSONObject(jsonText.toString());
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return null;
            String tag = release.optString("tag_name", "").trim();
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            JSONArray assets = release.optJSONArray("assets");
            if (tag.isEmpty() || assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset != null && APK_ASSET_NAME.equalsIgnoreCase(asset.optString("name", ""))) {
                    String url = asset.optString("browser_download_url", "");
                    if (url.startsWith("https://")) return new ReleaseInfo(version, tag, url);
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private int compareVersions(String left, String right) {
        String[] a = left.split("[.+-]", 4);
        String[] b = right.split("[.+-]", 4);
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            long av = i < a.length ? leadingNumber(a[i]) : 0L;
            long bv = i < b.length ? leadingNumber(b[i]) : 0L;
            if (av < bv) return -1;
            if (av > bv) return 1;
        }
        return 0;
    }

    private long leadingNumber(String value) {
        int i = 0;
        while (i < value.length() && Character.isDigit(value.charAt(i))) i++;
        if (i == 0) return 0L;
        try { return Long.parseLong(value.substring(0, i)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private void showUpdateDialog(ReleaseInfo release) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Calculator update available")
                .setMessage("Version " + release.version + " is available. You have version "
                        + BuildConfig.VERSION_NAME + ". Download and install the update now?")
                .setPositiveButton("Download and install", (dialog, which) -> downloadUpdate(release))
                .setNegativeButton("Later", null)
                .show();
    }

    private void downloadUpdate(ReleaseInfo release) {
        if (progressDialog == null) {
            progressDialog = new ProgressDialog(this);
            progressDialog.setTitle("Updating calculator");
            progressDialog.setMessage("Downloading the verified release asset from GitHub…");
            progressDialog.setIndeterminate(true);
            progressDialog.setCancelable(false);
        }
        progressDialog.show();

        executor.execute(() -> {
            File apk = null;
            Exception failure = null;
            try {
                apk = downloadApk(release.downloadUrl);
            } catch (Exception e) {
                failure = e;
            }
            File finalApk = apk;
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                if (finalFailure != null || finalApk == null || !finalApk.isFile() || finalApk.length() == 0L) {
                    new AlertDialog.Builder(this).setTitle("Update failed")
                            .setMessage("Could not download the update from GitHub. Check your connection and try again later.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                beginInstall(finalApk);
            });
        });
    }

    private File downloadApk(String downloadUrl) throws Exception {
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) directory = new File(getFilesDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create update directory");
        File destination = new File(directory, APK_ASSET_NAME);
        File temporary = new File(directory, APK_ASSET_NAME + ".part");

        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Plain-Gauge-Calculator-Android");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("APK download failed");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.getFD().sync();
            }
            if (!temporary.renameTo(destination)) {
                try (InputStream in = new java.io.FileInputStream(temporary);
                     FileOutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!temporary.delete()) temporary.deleteOnExit();
            }
            return destination;
        } finally {
            connection.disconnect();
        }
    }

    private void beginInstall(File apk) {
        pendingInstallFile = apk;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow app updates")
                    .setMessage("Android requires you to allow Plain Gauge Calculator to install updates downloaded from GitHub. Enable ‘Allow from this source’ on the next screen, then return to the app.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(settings, REQUEST_INSTALL_PERMISSION);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        launchInstaller(apk);
    }

    private void launchInstaller(File apk) {
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            new AlertDialog.Builder(this).setTitle("Cannot open installer")
                    .setMessage("Open the downloaded APK from your device's file manager to install the update.")
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                if (pendingInstallFile != null && pendingInstallFile.isFile()) launchInstaller(pendingInstallFile);
            } else {
                new AlertDialog.Builder(this).setTitle("Permission not enabled")
                        .setMessage("Enable ‘Allow from this source’ to install updates downloaded by the app.")
                        .setPositiveButton("OK", null).show();
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        if (executor != null) executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
