package de.hoeltzel.vokabeltrainer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_MIC = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingLang = null;
    private int pendingMax = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Vokabelliste wählen"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        String lang = pendingLang;
        pendingLang = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && lang != null) {
            startListening(lang, pendingMax);
        } else {
            js("window.__sr&&window.__sr._error('not-allowed')");
            js("window.__sr&&window.__sr._end()");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startListening(String lang, int max) {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            private boolean finished = false;

            private void end() {
                if (finished) return;
                finished = true;
                js("window.__sr&&window.__sr._end()");
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                if (finished) return;
                String code;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        code = "no-speech"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        code = "not-allowed"; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                        code = "network"; break;
                    case SpeechRecognizer.ERROR_CLIENT:
                        code = "aborted"; break;
                    default:
                        code = "fehler-" + error;
                }
                if (!"aborted".equals(code)) js("window.__sr&&window.__sr._error(" + JSONObject.quote(code) + ")");
                end();
            }

            @Override
            public void onResults(Bundle results) {
                if (finished) return;
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] conf = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                JSONArray arr = new JSONArray();
                if (texts != null) {
                    for (int k = 0; k < texts.size(); k++) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("t", texts.get(k));
                            double c = (conf != null && k < conf.length) ? conf[k] : 0;
                            o.put("c", c < 0 ? 0 : c);
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                }
                if (arr.length() > 0) js("window.__sr&&window.__sr._result(" + arr.toString() + ")");
                else js("window.__sr&&window.__sr._error('no-speech')");
                end();
            }
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Math.max(1, max));
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(i);
    }

    private class Bridge {
        @JavascriptInterface
        public boolean srAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void srStart(String lang, int max) {
            runOnUiThread(() -> {
                if (hasMic()) {
                    startListening(lang, max);
                } else {
                    pendingLang = lang;
                    pendingMax = max;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            });
        }

        @JavascriptInterface
        public void srStop() {
            runOnUiThread(() -> { if (recognizer != null) recognizer.stopListening(); });
        }

        @JavascriptInterface
        public void speak(String text, String lang, float rate) {
            runOnUiThread(() -> {
                if (!ttsReady || tts == null) return;
                Locale loc = Locale.forLanguageTag(lang);
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vt");
            });
        }

        @JavascriptInterface
        public void ttsStop() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }
    }
}
