package com.videodubbing.app;

import android.app.Activity;
import org.json.JSONObject;
import android.content.Intent;
import org.json.JSONObject;
import android.database.Cursor;
import org.json.JSONObject;
import android.net.Uri;
import org.json.JSONObject;
import android.os.Bundle;
import org.json.JSONObject;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import android.webkit.WebChromeClient;
import org.json.JSONObject;
import android.webkit.ValueCallback;
import org.json.JSONObject;
import android.webkit.WebSettings;
import org.json.JSONObject;
import android.webkit.WebView;
import org.json.JSONObject;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import android.widget.Toast;
import org.json.JSONObject;

import android.media.MediaExtractor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import org.json.JSONObject;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import org.json.JSONObject;
import android.speech.tts.TextToSpeech;
import org.json.JSONObject;
import android.speech.tts.UtteranceProgressListener;
import org.json.JSONObject;
import android.os.Environment;
import org.json.JSONObject;

import java.io.IOException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import org.json.JSONObject;
import java.io.DataOutputStream;
import org.json.JSONObject;
import java.io.File;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import org.json.JSONObject;
import java.net.URL;
import org.json.JSONObject;
import java.util.Locale;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView webView;
    private File modelDirectory;
    private static final String MODEL_DIR = "ai_models";

    private static final String INDIC_EN_MODEL_DIR = "indic_en";
    private static final String[] INDIC_EN_MODEL_FILES = {
            "encoder_model.onnx",
            "encoder_model.onnx.data",
            "decoder_model.onnx",
            "decoder_shared.onnx.data",
            "decoder_with_past_model.onnx",
            "tokenizer_src.json",
            "tokenizer_tgt.json",
            "tokenizer_meta.json",
            "tokenizer_config.json",
            "dict.SRC.json",
            "dict.TGT.json",
            "model.SRC",
            "model.TGT",
            "config.json",
            "generation_config.json"
    };

    private static final String[] EN_INDIC_MODEL_FILES = {
            "encoder_model.onnx",
            "decoder_model.onnx",
            "decoder_shared.onnx.data",
            "decoder_with_past_model.onnx",
            "tokenizer_src.json",
            "tokenizer_tgt.json",
            "tokenizer_meta.json",
            "tokenizer_config.json",
            "special_tokens_map.json",
            "dict.SRC.json",
            "dict.TGT.json",
            "model.SRC",
            "model.TGT",
            "config.json",
            "generation_config.json"
    };

    private static final String EN_INDIC_MODEL_DIR = "en_indic";

    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;

    private static final int MEDIA_PICKER_REQUEST = 2001;
    private ValueCallback<android.net.Uri[]> filePathCallback;

    private Uri selectedMediaUri;

    private File getModelFile(String modelDir, String fileName) {
        File directory = new File(modelDirectory, modelDir);

        if (!directory.exists()) {
            directory.mkdirs();
        }

        return new File(directory, fileName);
    }

    private boolean isModelDownloaded(String modelDir, String fileName) {
        File file = getModelFile(modelDir, fileName);
        return file.exists() && file.length() > 0;
    }

    private boolean isModelSetReady(String modelDir, String[] modelFiles) {
        for (String fileName : modelFiles) {
            if (!isModelDownloaded(modelDir, fileName)) {
                return false;
            }
        }
        return true;
    }

    private void downloadModel(String modelUrl, String modelDir, String fileName) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream input = null;
            BufferedOutputStream output = null;

            try {
                File targetFile = getModelFile(modelDir, fileName);

                URL url = new URL(modelUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(300000);
                connection.setRequestMethod("GET");
                connection.connect();

                int responseCode = connection.getResponseCode();

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + responseCode);
                }

                input = new BufferedInputStream(connection.getInputStream());
                output = new BufferedOutputStream(
                        new java.io.FileOutputStream(targetFile)
                );

                byte[] buffer = new byte[8192];
                int count;

                long totalSize = connection.getContentLengthLong();
                long downloadedSize = 0;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloadedSize += count;

                    if (totalSize > 0) {
                        int percent = (int) ((downloadedSize * 100L) / totalSize);

                        final int progress = percent;
                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "modelDownloadProgress(" + progress + ")",
                                        null
                                )
                        );
                    }
                }

                output.flush();

                if (!targetFile.exists() || targetFile.length() == 0) {
                    throw new IOException("Downloaded model file is empty");
                }

                runOnUiThread(() -> {
                        Toast.makeText(
                            MainActivity.this,
                            "Model downloaded: " + fileName,
                            Toast.LENGTH_SHORT
                        ).show();

                        if (webView != null) {
                            webView.evaluateJavascript(
                                "modelDownloadComplete(" +
                                JSONObject.quote(modelDir) + "," +
                                JSONObject.quote(fileName) +
                                ")",
                                null
                            );
                        }
                    });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Model download failed: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show()
                );

            } finally {
                try {
                    if (input != null) input.close();
                } catch (Exception ignored) {}

                try {
                    if (output != null) output.close();
                } catch (Exception ignored) {}

                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        modelDirectory = new File(getFilesDir(), MODEL_DIR);
        if (!modelDirectory.exists()) {
            modelDirectory.mkdirs();
        }

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

    private String getAudioMimeType(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();

        try {
            extractor.setDataSource(this, uri, null);

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);

                if (!format.containsKey(MediaFormat.KEY_MIME)) {
                    continue;
                }

                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {
                    return mime;
                }
            }

            return null;

        } catch (Exception e) {
            return null;

        } finally {
            extractor.release();
        }
    }

    private File extractAudioToWav(Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();

        try {
            extractor.setDataSource(this, uri, null);

            int audioTrack = -1;
            MediaFormat inputFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);

                if (!format.containsKey(MediaFormat.KEY_MIME)) {
                    continue;
                }

                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = format;
                    break;
                }
            }

            if (audioTrack < 0 || inputFormat == null) {
                throw new IOException("No audio track found");
            }

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(audioTrack);

            MediaCodec decoder =
                    MediaCodec.createDecoderByType(mime);

            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            File wavFile = new File(
                    getCacheDir(),
                    "dubbing_audio_" +
                            System.currentTimeMillis() +
                            ".wav"
            );

            FileOutputStream fos =
                    new FileOutputStream(wavFile);

            int decodedSampleRate =
                    inputFormat.containsKey(
                            MediaFormat.KEY_SAMPLE_RATE)
                            ? inputFormat.getInteger(
                                    MediaFormat.KEY_SAMPLE_RATE)
                            : 16000;

            int decodedChannels =
                    inputFormat.containsKey(
                            MediaFormat.KEY_CHANNEL_COUNT)
                            ? inputFormat.getInteger(
                                    MediaFormat.KEY_CHANNEL_COUNT)
                            : 1;

            writeWavHeader(
                    fos,
                    decodedSampleRate,
                    decodedChannels,
                    16,
                    0
            );

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean outputDone = false;

            long totalPcmBytes = 0;

            while (!outputDone) {

                if (!inputDone) {
                    int inputIndex =
                            decoder.dequeueInputBuffer(10000);

                    if (inputIndex >= 0) {

                        ByteBuffer inputBuffer =
                                decoder.getInputBuffer(
                                        inputIndex);

                        if (inputBuffer == null) {
                            throw new IOException(
                                    "Decoder input buffer is null");
                        }

                        inputBuffer.clear();

                        int sampleSize =
                                extractor.readSampleData(
                                        inputBuffer,
                                        0);

                        if (sampleSize < 0) {

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            inputDone = true;

                        } else {

                            long sampleTime =
                                    extractor.getSampleTime();

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    sampleTime,
                                    0
                            );

                            extractor.advance();
                        }
                    }
                }

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            decoder.getOutputBuffer(
                                    outputIndex);

                    if (outputBuffer != null &&
                            info.size > 0) {

                        byte[] pcm =
                                new byte[info.size];

                        outputBuffer.position(
                                info.offset);

                        outputBuffer.limit(
                                info.offset + info.size);

                        outputBuffer.get(pcm);

                        fos.write(pcm);

                        totalPcmBytes += pcm.length;
                    }

                    decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if ((info.flags &
                            MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        outputDone = true;
                    }

                } else if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat outputFormat =
                            decoder.getOutputFormat();

                    if (outputFormat.containsKey(
                            MediaFormat.KEY_SAMPLE_RATE)) {

                        decodedSampleRate =
                                outputFormat.getInteger(
                                        MediaFormat.KEY_SAMPLE_RATE);
                    }

                    if (outputFormat.containsKey(
                            MediaFormat.KEY_CHANNEL_COUNT)) {

                        decodedChannels =
                                outputFormat.getInteger(
                                        MediaFormat.KEY_CHANNEL_COUNT);
                    }

                    android.util.Log.d(
                            "VideoDubbing",
                            "Decoded audio: "
                                    + decodedSampleRate
                                    + " Hz, "
                                    + decodedChannels
                                    + " channels"
                    );
                }
            }

            decoder.stop();
            decoder.release();
            fos.close();

            if (totalPcmBytes <= 0) {
                throw new IOException(
                        "Decoded audio is empty");
            }

            RandomAccessFile wavRandom =
                    new RandomAccessFile(
                            wavFile,
                            "rw");

            wavRandom.seek(0);

            byte[] finalHeader =
                    buildWavHeader(
                            decodedSampleRate,
                            decodedChannels,
                            16,
                            totalPcmBytes
                    );

            wavRandom.write(finalHeader);
            wavRandom.close();

            return wavFile;

        } finally {
            extractor.release();
        }
    }

    private byte[] buildWavHeader(
            int sampleRate,
            int channels,
            int bitsPerSample,
            long dataLength
    ) {
        long byteRate =
                (long) sampleRate
                        * channels
                        * bitsPerSample
                        / 8;

        int blockAlign =
                channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        writeIntLE(
                header,
                4,
                (int) (36 + dataLength)
        );

        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';

        writeIntLE(header, 16, 16);
        writeShortLE(header, 20, (short) 1);
        writeShortLE(header, 22, (short) channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, (int) byteRate);
        writeShortLE(header, 32, (short) blockAlign);
        writeShortLE(header, 34, (short) bitsPerSample);

        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        writeIntLE(
                header,
                40,
                (int) dataLength
        );

        return header;
    }

    private void writeWavHeader(
            FileOutputStream out,
            int sampleRate,
            int channels,
            int bitsPerSample,
            long dataLength
    ) throws IOException {

        long byteRate =
                (long) sampleRate
                        * channels
                        * bitsPerSample
                        / 8;

        int blockAlign =
                channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        writeIntLE(
                header,
                4,
                (int) (36 + dataLength)
        );

        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';

        writeIntLE(header, 16, 16);
        writeShortLE(header, 20, (short) 1);
        writeShortLE(header, 22, (short) channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, (int) byteRate);
        writeShortLE(header, 32, (short) blockAlign);
        writeShortLE(header, 34, (short) bitsPerSample);

        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        writeIntLE(
                header,
                40,
                (int) dataLength
        );

        out.write(header);
    }

    private void writeIntLE(
            byte[] data,
            int offset,
            int value
    ) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] =
                (byte) ((value >> 8) & 0xff);
        data[offset + 2] =
                (byte) ((value >> 16) & 0xff);
        data[offset + 3] =
                (byte) ((value >> 24) & 0xff);
    }

    private void writeShortLE(
            byte[] data,
            int offset,
            short value
    ) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] =
                (byte) ((value >> 8) & 0xff);
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
        public boolean isModelDownloaded(String modelDir, String fileName) {
            return MainActivity.this.isModelDownloaded(modelDir, fileName);
        }

        @JavascriptInterface
        public void downloadModel(String modelUrl, String modelDir, String fileName) {
            MainActivity.this.downloadModel(modelUrl, modelDir, fileName);
        }

        @JavascriptInterface
        public boolean isIndicEnglishModelReady() {
            return MainActivity.this.isModelSetReady(
                    INDIC_EN_MODEL_DIR,
                    MainActivity.INDIC_EN_MODEL_FILES
            );
        }

        @JavascriptInterface
        public boolean isEnglishIndicModelReady() {
            return MainActivity.this.isModelSetReady(
                    EN_INDIC_MODEL_DIR,
                    MainActivity.EN_INDIC_MODEL_FILES
            );
        }

        @JavascriptInterface
        public void uploadSelectedVideo() {

            if (selectedMediaUri == null) {
                webView.post(() ->
                        webView.evaluateJavascript(
                                "nativeUploadError(" +
                                        JSONObjectQuote(
                                                "No video selected"
                                        ) +
                                        ")",
                                null
                        )
                );
                return;
            }

            runOnUiThread(() ->
                    webView.evaluateJavascript(
                            "setDubbingProgress(" +
                                    JSONObjectQuote(
                                            "Extracting audio..."
                                    ) +
                                    ",10)",
                            null
                    )
            );

            new Thread(() -> {

                HttpURLConnection conn = null;

                try {

                    // STEP 1: Extract audio locally
                    File wavFile =
                            extractAudioToWav(
                                    selectedMediaUri
                            );

                    runOnUiThread(() ->
                            webView.evaluateJavascript(
                                    "setDubbingProgress(" +
                                            JSONObjectQuote(
                                                    "Audio extracted"
                                            ) +
                                            ",30)",
                                    null
                            )
                    );

                    // STEP 2: Upload WAV to Whisper endpoint
                    URL url = new URL(
                            "https://video-dubbing-apk.onrender.com/transcribe"
                    );

                    String boundary =
                            "----VideoDubbingBoundary" +
                                    System.currentTimeMillis();

                    conn =
                            (HttpURLConnection)
                                    url.openConnection();

                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(600000);
                    conn.setUseCaches(false);

                    conn.setRequestProperty(
                            "Content-Type",
                            "multipart/form-data; boundary=" +
                                    boundary
                    );

                    DataOutputStream out =
                            new DataOutputStream(
                                    new BufferedOutputStream(
                                            conn.getOutputStream()
                                    )
                            );

                    out.writeBytes(
                            "--" + boundary + "\r\n"
                    );

                    out.writeBytes(
                            "Content-Disposition: form-data; " +
                                    "name=\"audio\"; " +
                                    "filename=\"dubbing_audio.wav\"\r\n"
                    );

                    out.writeBytes(
                            "Content-Type: audio/wav\r\n\r\n"
                    );

                    FileInputStream fileInput =
                            new FileInputStream(wavFile);

                    byte[] buffer =
                            new byte[8192];

                    int count;

                    long total =
                            wavFile.length();

                    long sent = 0;

                    while ((count =
                            fileInput.read(buffer)) != -1) {

                        out.write(
                                buffer,
                                0,
                                count
                        );

                        sent += count;

                        if (total > 0) {
                            int progress =
                                    30 +
                                    (int)
                                            ((sent * 50)
                                                    / total);

                            runOnUiThread(() ->
                                    webView.evaluateJavascript(
                                            "setDubbingProgress(" +
                                                    JSONObjectQuote(
                                                            "Uploading audio..."
                                                    ) +
                                                    "," +
                                                    progress +
                                                    ")",
                                            null
                                    )
                            );
                        }
                    }

                    fileInput.close();

                    out.writeBytes(
                            "\r\n--" +
                                    boundary +
                                    "--\r\n"
                    );

                    out.flush();
                    out.close();

                    runOnUiThread(() ->
                            webView.evaluateJavascript(
                                    "setDubbingProgress(" +
                                            JSONObjectQuote(
                                                    "Transcribing..."
                                            ) +
                                            ",85)",
                                    null
                            )
                    );

                    int code =
                            conn.getResponseCode();

                    InputStream responseStream =
                            code >= 400
                                    ? conn.getErrorStream()
                                    : conn.getInputStream();

                    String responseText = "";

                    if (responseStream != null) {

                        java.util.Scanner scanner =
                                new java.util.Scanner(
                                        responseStream,
                                        "UTF-8"
                                ).useDelimiter("\\A");

                        responseText =
                                scanner.hasNext()
                                        ? scanner.next()
                                        : "";

                        scanner.close();
                    }

                    final String response =
                            responseText;

                    if (code >= 200 &&
                            code < 300) {

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "nativeUploadComplete(" +
                                                JSONObjectQuote(
                                                        response
                                                ) +
                                                ")",
                                        null
                                )
                        );

                    } else {

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "nativeUploadError(" +
                                                JSONObjectQuote(
                                                        "HTTP " +
                                                                code +
                                                                ": " +
                                                                response
                                                ) +
                                                ")",
                                        null
                                )
                        );
                    }

                    if (wavFile.exists()) {
                        wavFile.delete();
                    }

                } catch (Exception e) {

                    final String error =
                            e.getClass().getSimpleName() +
                                    ": " +
                                    e.getMessage();

                    webView.post(() ->
                            webView.evaluateJavascript(
                                    "nativeUploadError(" +
                                            JSONObjectQuote(
                                                    error
                                            ) +
                                            ")",
                                    null
                            )
                    );

                } finally {

                    if (conn != null) {
                        conn.disconnect();
                    }
                }

            }).start();
        }

        private String JSONObjectQuote(String value) {
            if (value == null) {
                value = "";
            }

            return "\"" +
                    value.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r") +
                    "\"";
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

            if (results != null && results.length > 0) {
                selectedMediaUri = results[0];

                try {
                    getContentResolver().takePersistableUriPermission(
                            selectedMediaUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception ignored) {
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
