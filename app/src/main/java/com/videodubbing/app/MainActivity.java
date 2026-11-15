package com.videodubbing.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Environment;

import java.io.IOException;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;

    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;

    private static final int MEDIA_PICKER_REQUEST = 2001;
    private ValueCallback<android.net.Uri[]> filePathCallback;

    private Uri selectedMediaUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(
                        Locale.forLanguageTag("ta-IN")
                );

                ttsReady =
                        result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(
                new NativeBridge(),
                "AndroidBridge"
        );

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<android.net.Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent =
                        fileChooserParams.createIntent();

                try {
                    startActivityForResult(
                            intent,
                            MEDIA_PICKER_REQUEST
                    );
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "Cannot open file picker",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }

                return true;
            }
        });

        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void synthesizeTamilToFile(String text) {

        if (!ttsReady || textToSpeech == null) {
            Toast.makeText(
                    this,
                    "Tamil TTS is not ready",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        java.io.File outputFile =
                new java.io.File(
                        getExternalFilesDir(null),
                        "tamil_tts_test.wav"
                );

        Bundle params = new Bundle();

        int result = textToSpeech.synthesizeToFile(
                text,
                params,
                outputFile,
                "tamil_tts_test"
        );

        String message;

        if (result == TextToSpeech.SUCCESS) {
            message =
                    "TTS OK\\n"
                    + "File: " + outputFile.getAbsolutePath()
                    + "\\nExists: " + outputFile.exists()
                    + "\\nSize: " + outputFile.length() + " bytes";
        } else {
            message = "TTS FAILED\\nResult: " + result;
        }

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    private void openMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            startActivityForResult(intent, MEDIA_PICKER_REQUEST);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Picker error: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String getFileName(Uri uri) {

        String name = null;

        Cursor cursor = null;

        try {

            cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            );

            if (cursor != null &&
                    cursor.moveToFirst()) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );

                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }

        if (name == null) {
            name = uri.getLastPathSegment();
        }

        return name != null ? name : "Selected media";
    }

    private String inspectMedia(Uri uri) {

        MediaExtractor extractor =
                new MediaExtractor();

        try {

            extractor.setDataSource(
                    this,
                    uri,
                    null
            );

            int trackCount =
                    extractor.getTrackCount();

            StringBuilder result =
                    new StringBuilder();

            result.append(
                    "Media opened successfully\n"
            );

            result.append(
                    "Tracks: "
            ).append(trackCount).append("\n");

            for (int i = 0;
                    i < trackCount;
                    i++) {

                MediaFormat format =
                        extractor.getTrackFormat(i);

                String mime =
                        format.getString(
                                MediaFormat.KEY_MIME
                        );

                result.append(
                        "Track "
                ).append(i)
                 .append(": ")
                 .append(mime)
                 .append("\n");

                if (format.containsKey(
                        MediaFormat.KEY_DURATION)) {

                    long duration =
                            format.getLong(
                                    MediaFormat.KEY_DURATION
                            );

                    result.append(
                            "Duration: "
                    ).append(
                            duration / 1000000L
                    ).append(
                            " sec\n"
                    );
                }
            }

            return result.toString();

        } catch (Exception e) {

            return "MEDIA_ERROR: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage();

        } finally {

            extractor.release();
        }
    }

    private void sendToJavaScript(
            String function,
            String value) {

        if (webView == null) {
            return;
        }

        String escaped =
                value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");

        webView.post(() ->
                webView.evaluateJavascript(
                        function
                                + "(\""
                                + escaped
                                + "\")",
                        null
                )
        );
    }

    private class NativeBridge {

        @JavascriptInterface
        public void openMediaPicker() {

            runOnUiThread(() ->
                    openMediaPicker()
            );
        }

        @JavascriptInterface
        public String getSelectedMediaName() {

            if (selectedMediaUri == null) {
                return "";
            }

            return getFileName(
                    selectedMediaUri
            );
        }

        @JavascriptInterface
        public String inspectSelectedMedia() {

            if (selectedMediaUri == null) {
                return "No media selected";
            }

            return inspectMedia(
                    selectedMediaUri
            );
        }

        @JavascriptInterface
        public boolean hasSelectedMedia() {

            return selectedMediaUri != null;
        }

        @JavascriptInterface
        public void synthesizeTamil(String text) {

            runOnUiThread(() ->
                    synthesizeTamilToFile(text)
            );
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == MEDIA_PICKER_REQUEST &&
                filePathCallback != null) {

            Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {

                if (data.getClipData() != null) {

                    int count =
                            data.getClipData().getItemCount();

                    results = new Uri[count];

                    for (int i = 0; i < count; i++) {
                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results = new Uri[] {
                            data.getData()
                    };
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }

        if (requestCode != MEDIA_PICKER_REQUEST) {
            return;
        }

        if (resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        selectedMediaUri = data.getData();

        try {
            getContentResolver()
                    .takePersistableUriPermission(
                            selectedMediaUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
        } catch (Exception ignored) {
        }

        String name =
                getFileName(selectedMediaUri);

        sendToJavaScript(
                "nativeMediaSelected",
                name
        );
    }

    @Override
    public void onBackPressed() {

        if (webView != null &&
                webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
}
