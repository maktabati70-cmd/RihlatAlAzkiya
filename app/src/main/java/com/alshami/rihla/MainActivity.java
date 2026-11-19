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

import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false, warned = false;
    private Locale arLocale = new Locale("ar");
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private static final int REQ_FILE = 11, REQ_MIC = 12, REQ_STT = 14;
    private SpeechRecognizer stt;
    private SharedPreferences prefs;
    // التحديث من GitHub
    private static final String RAW_HTML = "https://raw.githubusercontent.com/maktabati70-cmd/RihlatAlAzkiya/main/app/src/main/assets/index.html";
    private static final String API_REL = "https://api.github.com/repos/maktabati70-cmd/RihlatAlAzkiya/releases/latest";

    private int vCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }
    private String vName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
    private File webFile() { return new File(new File(getFilesDir(), "www"), "index.html"); }
    // يفتح النسخة المحدّثة إن وُجدت، وإلا النسخة المضمّنة في التطبيق
    private String startUrl() {
        File f = webFile();
        if (prefs.getInt("webFor", -1) != vCode()) {
            f.delete();
            prefs.edit().remove("etag").putInt("webFor", vCode()).apply();
        }
        return f.exists() && f.length() > 100000 ? "file://" + f.getAbsolutePath() : "file:///android_asset/index.html";
    }
    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }
    private void toast(String m) { runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show()); }
    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void checkUpdates(boolean manual) {
        new Thread(() -> {
            boolean found = false, failed = false;
            // 1) تحديث محتوى اللعبة (index.html) بدون إعادة تثبيت
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RAW_HTML).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(30000);
                String et = prefs.getString("etag", null);
                if (et != null && webFile().exists()) c.setRequestProperty("If-None-Match", et);
                int code = c.getResponseCode();
                if (code == 200) {
                    InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
                    byte[] data = bo.toByteArray(); String txt = new String(data, "UTF-8");
                    if (data.length > 100000 && txt.contains("</html>") && txt.contains("أبو عبدالله الشامي")) {
                        // لا نعيد التحميل إذا كان مطابقًا للنسخة الحالية
                        String cur = "";
                        try { InputStream ci = webFile().exists() ? new java.io.FileInputStream(webFile()) : getAssets().open("index.html");
                              ByteArrayOutputStream cb = new ByteArrayOutputStream(); while ((n = ci.read(buf)) > 0) cb.write(buf, 0, n); ci.close(); cur = cb.toString("UTF-8"); } catch (Exception ignored) {}
                        if (!cur.equals(txt)) {
                            File d = webFile().getParentFile(); d.mkdirs();
                            File tmp = new File(d, "index.tmp");
                            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }
                            tmp.renameTo(webFile());
                            found = true;
                            js("window.onAppUpdate&&onAppUpdate()");
                        }
                        prefs.edit().putString("etag", c.getHeaderField("ETag")).putInt("webFor", vCode()).apply();
                    }
                } else if (code != 304) failed = true;
                c.disconnect();
            } catch (Exception e) { failed = true; }
            // 2) إصدار APK جديد على GitHub Releases
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API_REL).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() == 200) {
                    InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
                    JSONObject o = new JSONObject(bo.toString("UTF-8"));
                    String tg = o.optString("tag_name", "").replaceAll("[^0-9]", "");
                    int rc = tg.isEmpty() ? 0 : Integer.parseInt(tg);
                    if (rc > vCode()) {
                        String url = o.optString("html_url");
                        JSONArray as = o.optJSONArray("assets");
                        if (as != null) for (int i = 0; i < as.length(); i++) {
                            String u = as.getJSONObject(i).optString("browser_download_url");
                            if (u.endsWith(".apk")) { url = u; break; }
                        }
                        found = true;
                        js("window.onApkUpdate&&onApkUpdate(" + q(o.optString("name")) + "," + q(url) + ")");
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {}
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            if (manual && !found) toast(failed ? "تعذّر الاتصال بالإنترنت، حاول لاحقًا" : "✔ لديك أحدث نسخة من اللعبة");
        }).start();
    }

    private void startListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_STT);
            js("window.onSTTErr&&onSTTErr('perm')");
            return;
        }
        if (tts != null) tts.stop();
        if (stt == null) {
            stt = SpeechRecognizer.createSpeechRecognizer(this);
            stt.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle p) {}
                public void onEvent(int t, Bundle p) {}
                public void onError(int e) { js("window.onSTTErr&&onSTTErr('" + e + "')"); }
                public void onResults(Bundle r) {
                    ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    js("window.onSTT&&onSTT(" + new JSONArray(l == null ? new ArrayList<String>() : l).toString() + ")");
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try { stt.cancel(); stt.startListening(i); } catch (Exception e) { js("window.onSTTErr&&onSTTErr('x')"); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("rihla", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.parseColor("#1b1f5e"));
        getWindow().setNavigationBarColor(Color.parseColor("#3a1c71"));

        // محرك النطق العربي
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(arLocale);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    arLocale = new Locale("ar", "SA");
                    r = tts.setLanguage(arLocale);
                }
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
        web.loadUrl(startUrl());

    }

    private class Bridge {
        @JavascriptInterface public void checkUpdate() { checkUpdates(true); }
        @JavascriptInterface public void reloadApp() { runOnUiThread(() -> web.loadUrl(startUrl())); }
        @JavascriptInterface public String appVersion() { return vName(); }
        @JavascriptInterface public void openUrl(String u) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception ignored) {}
        }
        @JavascriptInterface public boolean hasSTT() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }
        @JavascriptInterface public void listenEn() { runOnUiThread(() -> startListen()); }
        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(Math.max(5, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }
        @JavascriptInterface
        public void speakEn(String text, float rate) {
            if (tts == null) return;
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "en");
        }
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && ttsReady) { tts.setLanguage(arLocale); tts.setSpeechRate(0.95f); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q"); }
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
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/أطفال دار الوحي");
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
        if (req == REQ_STT && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            toast("✔ تم السماح بالميكروفون، اضغط «اختبر نطقي» مجددًا");
    }

    // فحص التحديثات عند كل فتح للتطبيق أو الرجوع إليه (بحد أدنى 10 دقائق بين كل فحص)
    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) > 10 * 60 * 1000L)
            web.postDelayed(() -> checkUpdates(false), 3000);
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
        if (stt != null) stt.destroy();
        super.onDestroy();
    }
}
