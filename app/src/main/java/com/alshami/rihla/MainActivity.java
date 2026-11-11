package com.alshami.rihla;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false, warned = false;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private static final int REQ_FILE = 11, REQ_MIC = 12;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#1b1f5e"));
        getWindow().setNavigationBarColor(Color.parseColor("#3a1c71"));

        // محرك النطق العربي
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ar"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                    r = tts.setLanguage(new Locale("ar", "SA"));
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(0.95f);
            }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1b1f5e"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // اختيار صورة الطفل
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try { startActivityForResult(Intent.createChooser(i, "اختر صورة"), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
            // الميكروفون لتسجيل صوت الأهل
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q");
            else if (!warned) { warned = true; runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "لتفعيل النطق: ثبّت الصوت العربي من إعدادات الجهاز (تحويل النص إلى كلام)", Toast.LENGTH_LONG).show()); }
        }
        @JavascriptInterface
        public void stopSpeak() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public boolean hasTTS() { return ttsReady; }

        // حفظ الشهادة في الصور
        @JavascriptInterface
        public void saveImage(String b64, String name) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29)
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/رحلة الأذكياء");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "✔ حُفظت الشهادة في الصور", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT < 29) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 13);
                    Toast.makeText(MainActivity.this, "تعذّر الحفظ، اسمح بالوصول للصور ثم حاول مجددًا", Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC && pendingPerm != null) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? appBack() : false", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
