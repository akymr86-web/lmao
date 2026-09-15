package com.simplebrowser.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;
    private EditText addressBar;
    private LinearLayout addressBarContainer;
    private LinearLayout bottomToolbar;
    private FrameLayout fullscreenContainer;
    private View customView;
    private BrowserChromeClient chromeClient;

    private SharedPreferences prefs;
    private Uri downloadTreeUri;
    private boolean barsHiddenByGesture = false;

    private static final int REQ_PICK_FOLDER = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        String savedTree = prefs.getString("download_tree_uri", null);
        if (savedTree != null) downloadTreeUri = Uri.parse(savedTree);

        setContentView(buildLayout());

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                addressBar.setText(url);
            }
        });

        chromeClient = new BrowserChromeClient();
        webView.setWebChromeClient(chromeClient);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimetype));

        applyToolbarVisibility();
        webView.loadUrl("https://www.google.com");
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        addressBarContainer = new LinearLayout(this);
        addressBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        addressBarContainer.setPadding(8, 8, 8, 8);

        ImageButton backBtn = iconButton(android.R.drawable.ic_media_previous, v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        ImageButton fwdBtn = iconButton(android.R.drawable.ic_media_next, v -> {
            if (webView.canGoForward()) webView.goForward();
        });

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setHint("Search or type URL");
        addressBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            loadUrlFromAddressBar();
            return true;
        });

        ImageButton reloadBtn = iconButton(android.R.drawable.ic_popup_sync, v -> webView.reload());
        ImageButton menuBtn = iconButton(android.R.drawable.ic_menu_more, this::showPopupMenu);

        addressBarContainer.addView(backBtn);
        addressBarContainer.addView(fwdBtn);
        addressBarContainer.addView(addressBar);
        addressBarContainer.addView(reloadBtn);
        addressBarContainer.addView(menuBtn);

        GestureDetector swipeUpDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 != null && e2 != null && (e1.getY() - e2.getY() > 80) && Math.abs(vY) > 200) {
                    hideBarsTemporarily();
                    return true;
                }
                return false;
            }
        });
        addressBarContainer.setOnTouchListener((v, event) -> swipeUpDetector.onTouchEvent(event));

        View topEdge = new View(this);
        GestureDetector restoreDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (barsHiddenByGesture && e1 != null && e2 != null && (e2.getY() - e1.getY() > 40)) {
                    restoreBars();
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (barsHiddenByGesture) { restoreBars(); return true; }
                return false;
            }
        });
        topEdge.setOnTouchListener((v, event) -> restoreDetector.onTouchEvent(event));

        FrameLayout webFrame = new FrameLayout(this);
        webView = new WebView(this);
        webFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setVisibility(View.GONE);
        webFrame.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webFrame.addView(topEdge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40));

        bottomToolbar = new LinearLayout(this);
        bottomToolbar.setOrientation(LinearLayout.HORIZONTAL);
        bottomToolbar.setPadding(8, 8, 8, 8);
        bottomToolbar.addView(iconButton(android.R.drawable.ic_menu_myplaces,
                v -> webView.loadUrl("https://www.google.com")));
        bottomToolbar.addView(iconButton(android.R.drawable.stat_sys_download, v -> pickDownloadFolder()));

        root.addView(addressBarContainer);
        root.addView(webFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomToolbar);
        return root;
    }

    private ImageButton iconButton(int drawableRes, View.OnClickListener listener) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawableRes);
        btn.setBackgroundColor(0);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void loadUrlFromAddressBar() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            input = (input.contains(".") && !input.contains(" "))
                    ? "https://" + input
                    : "https://www.google.com/search?q=" + Uri.encode(input);
        }
        webView.loadUrl(input);
    }

    private void hideBarsTemporarily() {
        addressBarContainer.setVisibility(View.GONE);
        bottomToolbar.setVisibility(View.GONE);
        barsHiddenByGesture = true;
        hideSystemUI();
    }

    private void restoreBars() {
        addressBarContainer.setVisibility(View.VISIBLE);
        applyToolbarVisibility();
        barsHiddenByGesture = false;
        showSystemUI();
    }

    private void applyToolbarVisibility() {
        bottomToolbar.setVisibility(prefs.getBoolean("toolbar_visible", true) ? View.VISIBLE : View.GONE);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        final int idToggleToolbar = 1, idDownloadFolder = 2, idShare = 3, idCustomize = 4;

        popup.getMenu().add(0, idToggleToolbar, 0,
                prefs.getBoolean("toolbar_visible", true) ? "Hide Tool Bar" : "Show Tool Bar");
        if (prefs.getBoolean("menu_show_downloads", true)) {
            popup.getMenu().add(0, idDownloadFolder, 0, "Choose Download Folder");
        }
        if (prefs.getBoolean("menu_show_share", true)) {
            popup.getMenu().add(0, idShare, 0, "Share Page");
        }
        popup.getMenu().add(0, idCustomize, 0, "Customize Menu");

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == idToggleToolbar) {
                prefs.edit().putBoolean("toolbar_visible", !prefs.getBoolean("toolbar_visible", true)).apply();
                applyToolbarVisibility();
            } else if (id == idDownloadFolder) {
                pickDownloadFolder();
            } else if (id == idShare) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, webView.getUrl());
                startActivity(Intent.createChooser(share, "Share via"));
            } else if (id == idCustomize) {
                showCustomizeMenuDialog();
            }
            return true;
        });
        popup.show();
    }

    private void showCustomizeMenuDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        CheckBox cbDownloads = new CheckBox(this);
        cbDownloads.setText("Show 'Choose Download Folder'");
        cbDownloads.setChecked(prefs.getBoolean("menu_show_downloads", true));

        CheckBox cbShare = new CheckBox(this);
        cbShare.setText("Show 'Share Page'");
        cbShare.setChecked(prefs.getBoolean("menu_show_share", true));

        layout.addView(cbDownloads);
        layout.addView(cbShare);

        new AlertDialog.Builder(this)
                .setTitle("Customize Menu")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> prefs.edit()
                        .putBoolean("menu_show_downloads", cbDownloads.isChecked())
                        .putBoolean("menu_show_share", cbShare.isChecked())
                        .apply())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickDownloadFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                downloadTreeUri = treeUri;
                prefs.edit().putString("download_tree_uri", treeUri.toString()).apply();
                Toast.makeText(this, "Download folder set", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        if (downloadTreeUri == null) {
            Toast.makeText(this, "Pick a download folder first (menu \u2192 Choose Download Folder)", Toast.LENGTH_LONG).show();
            pickDownloadFolder();
            return;
        }
        String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
        String cookie = CookieManager.getInstance().getCookie(url);

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                conn.connect();

                DocumentFile dir = DocumentFile.fromTreeUri(this, downloadTreeUri);
                DocumentFile outFile = dir.createFile(
                        mimetype != null ? mimetype : "application/octet-stream", filename);

                try (InputStream in = conn.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(outFile.getUri())) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
                }
                runOnUiThread(() -> Toast.makeText(this, "Downloaded: " + filename, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (barsHiddenByGesture) {
            restoreBars();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private class BrowserChromeClient extends WebChromeClient {
        private CustomViewCallback callback;

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) {
                cb.onCustomViewHidden();
                return;
            }
            customView = view;
            callback = cb;
            addressBarContainer.setVisibility(View.GONE);
            bottomToolbar.setVisibility(View.GONE);
            webView.setVisibility(View.GONE);
            fullscreenContainer.setVisibility(View.VISIBLE);
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            fullscreenContainer.removeView(customView);
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            addressBarContainer.setVisibility(View.VISIBLE);
            applyToolbarVisibility();
            customView = null;
            if (callback != null) callback.onCustomViewHidden();
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            showSystemUI();
        }
    }
}
