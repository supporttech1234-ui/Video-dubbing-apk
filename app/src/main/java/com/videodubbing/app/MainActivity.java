package com.videodubbing.app;

import android.app.Activity;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Intent;
import org.json.JSONObject;
import android.content.ContentUris;
import android.database.Cursor;
import java.io.ByteArrayOutputStream;
import android.util.Base64;
import android.media.MediaMetadataRetriever;
import android.media.AudioManager;
import android.graphics.Bitmap;
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
import android.util.Log;
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
import android.content.ContentValues;
import android.provider.MediaStore;
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
import java.io.OutputStream;
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
    private Uri savedDubbedVideoUri;

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
        public String getSavedDubbedVideos() {
            JSONArray result = new JSONArray();

            Cursor cursor = null;

            try {
                Uri collection =
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

                String[] projection = {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_ADDED
                };

                String selection =
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?"
                        + " AND "
                        + MediaStore.Video.Media.IS_PENDING + " = 0";

                String[] selectionArgs = {
                        Environment.DIRECTORY_MOVIES + "/VideoDubbing/%"
                };

                cursor = getContentResolver().query(
                        collection,
                        projection,
                        selection,
                        selectionArgs,
                        MediaStore.Video.Media.DATE_ADDED + " DESC"
                );

                if (cursor != null) {
                    int idIndex =
                            cursor.getColumnIndexOrThrow(
                                    MediaStore.Video.Media._ID
                            );

                    int nameIndex =
                            cursor.getColumnIndexOrThrow(
                                    MediaStore.Video.Media.DISPLAY_NAME
                            );

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIndex);

                        Uri videoUri =
                                ContentUris.withAppendedId(
                                        collection,
                                        id
                                );

                        JSONObject item = new JSONObject();

                        item.put(
                                "uri",
                                videoUri.toString()
                        );

                        item.put(
                                "name",
                                cursor.getString(nameIndex)
                        );

                        // MX Player style video details
                        try {
                            MediaMetadataRetriever detailsRetriever =
                                    new MediaMetadataRetriever();

                            detailsRetriever.setDataSource(
                                    MainActivity.this,
                                    videoUri
                            );

                            String durationValue =
                                    detailsRetriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_DURATION
                                    );

                            String widthValue =
                                    detailsRetriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                                    );

                            String heightValue =
                                    detailsRetriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                                    );

                            long durationMs = 0;

                            if (durationValue != null) {
                                durationMs = Long.parseLong(durationValue);
                            }

                            long totalSeconds =
                                    durationMs / 1000;

                            long hours =
                                    totalSeconds / 3600;

                            long minutes =
                                    (totalSeconds % 3600) / 60;

                            long seconds =
                                    totalSeconds % 60;

                            String durationText;

                            if (hours > 0) {
                                durationText = String.format(
                                        java.util.Locale.US,
                                        "%d:%02d:%02d",
                                        hours,
                                        minutes,
                                        seconds
                                );
                            } else {
                                durationText = String.format(
                                        java.util.Locale.US,
                                        "%02d:%02d",
                                        minutes,
                                        seconds
                                );
                            }

                            item.put(
                                    "duration",
                                    durationText
                            );

                            if (widthValue != null &&
                                    heightValue != null) {

                                item.put(
                                        "resolution",
                                        widthValue + "x" + heightValue
                                );

                            } else {

                                item.put(
                                        "resolution",
                                        ""
                                );
                            }

                            detailsRetriever.release();

                        } catch (Exception detailsError) {

                            item.put("duration", "");
                            item.put("resolution", "");

                            Log.e(
                                    "VideoDubbing",
                                    "Video details creation failed",
                                    detailsError
                            );
                        }

                        // Create a small thumbnail from the actual saved video
                        try {
                            MediaMetadataRetriever retriever =
                                    new MediaMetadataRetriever();

                            retriever.setDataSource(
                                    MainActivity.this,
                                    videoUri
                            );

                            Bitmap frame =
                                    retriever.getFrameAtTime(
                                            1000000,
                                            MediaMetadataRetriever
                                                    .OPTION_CLOSEST_SYNC
                                    );

                            if (frame != null) {
                                Bitmap thumb =
                                        Bitmap.createScaledBitmap(
                                                frame,
                                                240,
                                                135,
                                                true
                                        );

                                ByteArrayOutputStream out =
                                        new ByteArrayOutputStream();

                                thumb.compress(
                                        Bitmap.CompressFormat.JPEG,
                                        75,
                                        out
                                );

                                String base64 =
                                        Base64.encodeToString(
                                                out.toByteArray(),
                                                Base64.NO_WRAP
                                        );

                                item.put(
                                        "thumbnail",
                                        "data:image/jpeg;base64," + base64
                                );

                                thumb.recycle();
                                frame.recycle();
                                out.close();
                            }

                            retriever.release();

                        } catch (Exception thumbnailError) {

                            Log.e(
                                    "VideoDubbing",
                                    "Thumbnail creation failed",
                                    thumbnailError
                            );
                        }

                        result.put(item);
                    }
                }

            } catch (Exception e) {

                Log.e(
                        "VideoDubbing",
                        "getSavedDubbedVideos failed",
                        e
                );

            } finally {

                if (cursor != null) {
                    cursor.close();
                }
            }

            return result.toString();
        }



        @JavascriptInterface
        public boolean renameSavedVideo(String uriString, String newName) {
            try {
                if (newName == null || newName.trim().isEmpty()) {
                    return false;
                }

                Uri uri = Uri.parse(uriString);
                String cleanName = newName.trim();

                if (!cleanName.toLowerCase(java.util.Locale.US).endsWith(".mp4")) {
                    cleanName += ".mp4";
                }

                android.content.ContentValues values =
                        new android.content.ContentValues();

                values.put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        cleanName
                );

                int updated =
                        getContentResolver().update(
                                uri,
                                values,
                                null,
                                null
                        );

                return updated > 0;

            } catch (Exception e) {
                Log.e("VideoDubbing", "Rename failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean deleteSavedVideo(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);

                int deleted =
                        getContentResolver().delete(
                                uri,
                                null,
                                null
                        );

                return deleted > 0;

            } catch (Exception e) {
                Log.e("VideoDubbing", "Delete failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public void shareSavedVideo(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);

                android.content.Intent shareIntent =
                        new android.content.Intent(
                                android.content.Intent.ACTION_SEND
                        );

                shareIntent.setType("video/mp4");
                shareIntent.putExtra(
                        android.content.Intent.EXTRA_STREAM,
                        uri
                );

                shareIntent.addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                );

                startActivity(
                        android.content.Intent.createChooser(
                                shareIntent,
                                "Share Dubbed Video"
                        )
                );

            } catch (Exception e) {
                Log.e("VideoDubbing", "Share failed", e);
            }
        }

        @JavascriptInterface
        public boolean downloadSavedVideo(String uriString, String fileName) {
            android.net.Uri outputUri = null;

            try {
                Uri sourceUri = Uri.parse(uriString);

                String cleanName =
                        (fileName == null || fileName.trim().isEmpty())
                                ? "Dubbed Video.mp4"
                                : fileName.trim();

                if (!cleanName.toLowerCase(java.util.Locale.US).endsWith(".mp4")) {
                    cleanName += ".mp4";
                }

                android.content.ContentResolver resolver =
                        getContentResolver();

                android.content.ContentValues values =
                        new android.content.ContentValues();

                values.put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        cleanName
                );

                values.put(
                        MediaStore.Video.Media.MIME_TYPE,
                        "video/mp4"
                );

                if (android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.Q) {

                    values.put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                                    + "/VideoDubbing/"
                    );

                    values.put(
                            MediaStore.Video.Media.IS_PENDING,
                            1
                    );

                    outputUri =
                            resolver.insert(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                    values
                            );

                    if (outputUri == null) {
                        return false;
                    }

                    try (
                        java.io.InputStream in =
                                resolver.openInputStream(sourceUri);

                        java.io.OutputStream out =
                                resolver.openOutputStream(outputUri)
                    ) {
                        if (in == null || out == null) {
                            resolver.delete(
                                    outputUri,
                                    null,
                                    null
                            );
                            return false;
                        }

                        byte[] buffer = new byte[8192];
                        int length;

                        while ((length = in.read(buffer)) != -1) {
                            out.write(buffer, 0, length);
                        }

                        out.flush();
                    }

                    android.content.ContentValues done =
                            new android.content.ContentValues();

                    done.put(
                            MediaStore.Video.Media.IS_PENDING,
                            0
                    );

                    resolver.update(
                            outputUri,
                            done,
                            null,
                            null
                    );

                    return true;

                } else {

                    java.io.File downloads =
                            android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                            );

                    java.io.File folder =
                            new java.io.File(
                                    downloads,
                                    "VideoDubbing"
                            );

                    if (!folder.exists() && !folder.mkdirs()) {
                        return false;
                    }

                    java.io.File outputFile =
                            new java.io.File(
                                    folder,
                                    cleanName
                            );

                    try (
                        java.io.InputStream in =
                                resolver.openInputStream(sourceUri);

                        java.io.OutputStream out =
                                new java.io.FileOutputStream(outputFile)
                    ) {
                        if (in == null) {
                            return false;
                        }

                        byte[] buffer = new byte[8192];
                        int length;

                        while ((length = in.read(buffer)) != -1) {
                            out.write(buffer, 0, length);
                        }

                        out.flush();
                    }

                    return true;
                }

            } catch (Exception e) {

                if (outputUri != null) {
                    try {
                        getContentResolver().delete(
                                outputUri,
                                null,
                                null
                        );
                    } catch (Exception ignored) {
                    }
                }

                Log.e(
                        "VideoDubbing",
                        "Download failed",
                        e
                );

                return false;
            }
        }

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
        public void saveDubbedMovie() {

            if (savedDubbedVideoUri == null) {
                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Dubbed video is not available",
                                Toast.LENGTH_SHORT
                        ).show()
                );
                return;
            }

            try {
                Intent intent = new Intent(
                        Intent.ACTION_VIEW,
                        savedDubbedVideoUri
                );

                intent.setDataAndType(
                        savedDubbedVideoUri,
                        "video/mp4"
                );

                intent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );

                startActivity(intent);

            } catch (Exception e) {
                Log.e(
                        "VideoDubbing",
                        "Could not open saved dubbed video",
                        e
                );

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Cannot open saved dubbed video",
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }
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
        public void uploadSelectedVideo(
                String originalLanguage,
                String dubLanguage,
                String role
        ) {

            Log.d("VideoDubbing", "UPLOAD_SELECTED_VIDEO CALLED");

            runOnUiThread(() ->
                    webView.evaluateJavascript(
                            "setDubbingProgress(" +
                                    JSONObjectQuote("Starting audio processing...") +
                                    ",5)",
                            null
                    )
            );

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

                    // Upload original video alongside audio
                    out.writeBytes(
                            "--" + boundary + "\r\n"
                    );

                    out.writeBytes(
                            "Content-Disposition: form-data; " +
                                    "name=\"video\"; " +
                                    "filename=\"original_video.mp4\"\r\n"
                    );

                    out.writeBytes(
                            "Content-Type: video/mp4\r\n\r\n"
                    );

                    InputStream videoInput =
                            getContentResolver().openInputStream(
                                    selectedMediaUri
                            );

                    if (videoInput == null) {
                        throw new IOException(
                                "Could not open selected video"
                        );
                    }

                    byte[] videoBuffer =
                            new byte[8192];

                    int videoCount;

                    while ((videoCount =
                            videoInput.read(videoBuffer)) != -1) {

                        out.write(
                                videoBuffer,
                                0,
                                videoCount
                        );
                    }

                    videoInput.close();

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

                        try {
                            JSONObject transcriptionJson =
                                    new JSONObject(response);

                            String originalSrtUrl =
        transcriptionJson.optString(
                "srt_url",
                ""
        ).trim();

String originalSrtContent =
        transcriptionJson.optString(
                "srt_content",
                ""
        );

                            Log.d(
                                    "VideoDubbing",
                                    "ORIGINAL SRT URL: " + originalSrtUrl
                            );

                            if (!originalSrtUrl.isEmpty()) {
                                try {
                                    saveSrtToDownloads(
                                            originalSrtUrl,
                                            "Original_Subtitles.srt"
                                    );
                                    Log.d(
                                            "VideoDubbing",
                                            "ORIGINAL SRT SAVED TO DOWNLOADS"
                                    );
                                } catch (Exception srtSaveError) {
                                    Log.e(
                                            "VideoDubbing",
                                            "ORIGINAL SRT SAVE FAILED",
                                            srtSaveError
                                    );
                                }
                            }

                            String transcript =
                                    transcriptionJson.optString(
                                            "transcript",
                                            ""
                                    ).trim();

                            JSONArray segments =
                                    transcriptionJson.optJSONArray("segments");

                            if (segments == null) {
                                segments = new JSONArray();
                            }

                            if (segments.length() == 0 &&
                                    !transcript.isEmpty()) {

                                JSONObject fallbackSegment =
                                        new JSONObject();

                                fallbackSegment.put(
                                        "id",
                                        0
                                );

                                fallbackSegment.put(
                                        "start",
                                        0.0
                                );

                                fallbackSegment.put(
                                        "end",
                                        999999.0
                                );

                                fallbackSegment.put(
                                        "text",
                                        transcript
                                );

                                segments.put(
                                        fallbackSegment
                                );

                                Log.d(
                                        "VideoDubbing",
                                        "TRANSCRIBE: no timestamped segments; created fallback segment"
                                );
                            }

                            if (transcript.isEmpty()) {
                                throw new Exception(
                                        "Transcript is empty"
                                );
                            }

                            final int segmentCount =
                                    segments.length();

                            runOnUiThread(() ->
                                    webView.evaluateJavascript(
                                            "setDubbingProgress(" +
                                                    JSONObjectQuote(
                                                            "Transcription ready — " +
                                                            segmentCount +
                                                            " segments. Translating..."
                                                    ) +
                                                    ",87)",
                                            null
                                    )
                            );

                            String videoFile =
                                    transcriptionJson.optString(
                                            "video_file",
                                            ""
                                    ).trim();

                            if (videoFile.isEmpty()) {
                                throw new Exception(
                                        "Video file missing from transcription response"
                                );
                            }

                            requestTranslationTts(
                                    transcript,
                                    segments,
                                    originalLanguage,
                                    dubLanguage,
                                    videoFile,
                                    role,
                                    originalSrtContent
                            );

                        } catch (Exception parseError) {

                            final String error =
                                    "Transcript response error: " +
                                            parseError.getMessage();

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
                        }

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


        private Uri saveSrtToDownloads(String srtUrl, String fileName)
                throws Exception {

            String fullUrl = srtUrl.startsWith("http")
                    ? srtUrl
                    : "https://video-dubbing-apk.onrender.com" + srtUrl;

            HttpURLConnection conn = (HttpURLConnection)
                    new URL(fullUrl).openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("SRT download HTTP " + code);
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip");

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/VideoDubbing"
                );
                values.put(MediaStore.Downloads.IS_PENDING, 1);
            }

            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
            );

            if (uri == null) {
                throw new Exception("Could not create SRT download entry");
            }

            try {
                InputStream input = conn.getInputStream();
                OutputStream output = getContentResolver().openOutputStream(uri);

                if (output == null) {
                    throw new Exception("Could not open SRT output stream");
                }

                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }

                output.flush();
                output.close();
                input.close();

                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            } finally {
                conn.disconnect();
            }

            Log.d("VideoDubbing", "SRT SAVED: " + fileName);
            return uri;
        }

        private String downloadSrtContent(String srtUrl)
                throws Exception {

            String fullUrl = srtUrl.startsWith("http")
                    ? srtUrl
                    : "https://video-dubbing-apk.onrender.com" + srtUrl;

            HttpURLConnection conn =
                    (HttpURLConnection) new URL(fullUrl).openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new Exception(
                        "SRT content download HTTP " + code
                );
            }

            InputStream input = conn.getInputStream();

            java.util.Scanner scanner =
                    new java.util.Scanner(
                            input,
                            "UTF-8"
                    ).useDelimiter("\\A");

            String content =
                    scanner.hasNext()
                            ? scanner.next()
                            : "";

            scanner.close();
            input.close();
            conn.disconnect();

            return content;
        }

        private File downloadTtsAudio(String ttsUrl)
                throws Exception {

            String fullUrl;

            if (ttsUrl.startsWith("http://") ||
                    ttsUrl.startsWith("https://")) {
                fullUrl = ttsUrl;
            } else {
                fullUrl =
                        "https://video-dubbing-apk.onrender.com" +
                                ttsUrl;
            }

            URL url = new URL(fullUrl);

            HttpURLConnection conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);

            int code = conn.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new Exception(
                        "TTS download HTTP " + code
                );
            }

            File output =
                    new File(
                            getCacheDir(),
                            "dubbed_tts.mp3"
                    );

            InputStream input =
                    conn.getInputStream();

            FileOutputStream fos =
                    new FileOutputStream(output);

            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }

            fos.flush();
            fos.close();
            input.close();
            conn.disconnect();

            if (!output.exists() ||
                    output.length() == 0) {
                throw new Exception(
                        "Downloaded TTS file is empty"
                );
            }

            Log.d(
                    "VideoDubbing",
                    "TTS DOWNLOADED: " +
                            output.getAbsolutePath() +
                            " (" +
                            output.length() +
                            " bytes)"
            );

            return output;
        }


        private File downloadDubbedVideo(String videoUrl)
                throws Exception {

            String fullUrl;

            if (videoUrl.startsWith("http://") ||
                    videoUrl.startsWith("https://")) {
                fullUrl = videoUrl;
            } else {
                fullUrl =
                        "https://video-dubbing-apk.onrender.com" +
                                videoUrl;
            }

            URL url = new URL(fullUrl);

            HttpURLConnection conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);

            int code = conn.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new Exception(
                        "Dubbed video download HTTP " + code
                );
            }

            File output =
                    new File(
                            getCacheDir(),
                            "dubbed_video.mp4"
                    );

            InputStream input =
                    conn.getInputStream();

            FileOutputStream fos =
                    new FileOutputStream(output);

            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }

            fos.flush();
            fos.close();
            input.close();
            conn.disconnect();

            if (!output.exists() ||
                    output.length() == 0) {

                throw new Exception(
                        "Downloaded dubbed video is empty"
                );
            }

            Log.d(
                    "VideoDubbing",
                    "DUBBED VIDEO DOWNLOADED: " +
                            output.getAbsolutePath() +
                            " (" +
                            output.length() +
                            " bytes)"
            );

            return output;
        }

        private Uri saveDubbedVideoToDownloads(File videoFile)
                throws Exception {

            String fileName =
                    "Dubbed_Video_" +
                            System.currentTimeMillis() +
                            ".mp4";

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
            );

            values.put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
            );

            if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.Q) {

                values.put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES +
                                "/VideoDubbing"
                );

                values.put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                );
            }

            Uri collection;

            if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.Q) {

                collection =
                        MediaStore.Video.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY
                        );

            } else {

                collection =
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }

            Uri uri =
                    getContentResolver().insert(
                            collection,
                            values
                    );

            if (uri == null) {
                throw new Exception(
                        "Could not create Downloads video entry"
                );
            }

            try {

                OutputStream output =
                        getContentResolver()
                                .openOutputStream(uri);

                if (output == null) {
                    throw new Exception(
                            "Could not open output stream"
                    );
                }

                InputStream input =
                        new FileInputStream(videoFile);

                byte[] buffer = new byte[8192];
                int read;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }

                output.flush();
                output.close();
                input.close();

                if (android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.Q) {

                    ContentValues complete =
                            new ContentValues();

                    complete.put(
                            MediaStore.Video.Media.IS_PENDING,
                            0
                    );

                    getContentResolver().update(
                            uri,
                            complete,
                            null,
                            null
                    );
                }

                Log.d(
                        "VideoDubbing",
                        "DUBBED VIDEO SAVED: " +
                                uri.toString()
                );

                return uri;

            } catch (Exception e) {

                getContentResolver().delete(
                        uri,
                        null,
                        null
                );

                throw e;
            }
        }

        private void logTtsFormat(File ttsFile) {
            MediaExtractor extractor = new MediaExtractor();

            try {
                extractor.setDataSource(
                        ttsFile.getAbsolutePath()
                );

                for (int i = 0;
                     i < extractor.getTrackCount();
                     i++) {

                    MediaFormat format =
                            extractor.getTrackFormat(i);

                    String mime =
                            format.containsKey(
                                    MediaFormat.KEY_MIME)
                                    ? format.getString(
                                            MediaFormat.KEY_MIME)
                                    : null;

                    if (mime != null &&
                            mime.startsWith("audio/")) {

                        String details =
                                "TTS format: " +
                                        mime +
                                        ", sampleRate=" +
                                        (format.containsKey(
                                                MediaFormat.KEY_SAMPLE_RATE)
                                                ? format.getInteger(
                                                        MediaFormat.KEY_SAMPLE_RATE)
                                                : -1) +
                                        ", channels=" +
                                        (format.containsKey(
                                                MediaFormat.KEY_CHANNEL_COUNT)
                                                ? format.getInteger(
                                                        MediaFormat.KEY_CHANNEL_COUNT)
                                                : -1);

                        Log.d(
                                "VideoDubbing",
                                details
                        );

                        return;
                    }
                }

                Log.d(
                        "VideoDubbing",
                        "TTS audio track not found"
                );

            } catch (Exception e) {

                Log.e(
                        "VideoDubbing",
                        "TTS format check failed",
                        e
                );

            } finally {
                extractor.release();
            }
        }


        private void requestTranslationTts(
                String transcript,
                JSONArray segments,
                String originalLanguage,
                String dubLanguage,
                String videoFile,
                String role,
                String originalSrtContent
        ) {

            new Thread(() -> {

                HttpURLConnection translationConn = null;

                try {

                    URL url = new URL(
                            "https://video-dubbing-apk.onrender.com/translate-tts"
                    );

                    translationConn =
                            (HttpURLConnection)
                                    url.openConnection();

                    translationConn.setRequestMethod("POST");
                    translationConn.setDoOutput(true);
                    translationConn.setDoInput(true);
                    translationConn.setConnectTimeout(30000);
                    translationConn.setReadTimeout(600000);

                    translationConn.setRequestProperty(
                            "Content-Type",
                            "application/json; charset=UTF-8"
                    );

                    JSONObject request =
                            new JSONObject();

                    request.put(
                            "transcript",
                            transcript
                    );

                    request.put(
                            "segments",
                            segments
                    );

                    request.put(
                            "srt_content",
                            originalSrtContent
                    );

                    Log.d(
                            "VideoDubbing",
                            "TRANSLATE-TTS SEGMENTS COUNT: " +
                                    segments.length()
                    );

                    Log.d(
                            "VideoDubbing",
                            "TRANSLATE-TTS SEGMENTS DATA: " +
                                    segments.toString()
                    );

                    String direction;

                    if ("ta".equalsIgnoreCase(originalLanguage)
                            && "en".equalsIgnoreCase(dubLanguage)) {

                        direction = "ta-en";

                    } else if ("en".equalsIgnoreCase(originalLanguage)
                            && "ta".equalsIgnoreCase(dubLanguage)) {

                        direction = "en-ta";

                    } else if ("ta".equalsIgnoreCase(dubLanguage)) {

                        direction = "en-ta";

                    } else {

                        direction = "ta-en";
                    }

                    request.put(
                            "direction",
                            direction
                    );

                    request.put(
                            "role",
                            role
                    );

                    String requestJson = request.toString();

                    Log.d(
                            "VideoDubbing",
                            "TRANSLATE-TTS REQUEST SIZE: " +
                                    requestJson.length()
                    );

                    Log.d(
                            "VideoDubbing",
                            "TRANSLATE-TTS REQUEST PREVIEW: " +
                                    requestJson.substring(
                                            0,
                                            Math.min(500, requestJson.length())
                                    )
                    );

                    byte[] body =
                            requestJson.getBytes("UTF-8");

                    OutputStream output =
                            translationConn.getOutputStream();

                    output.write(body);
                    output.flush();
                    output.close();

                    int code =
                            translationConn.getResponseCode();

                    InputStream stream =
                            code >= 400
                                    ? translationConn.getErrorStream()
                                    : translationConn.getInputStream();

                    String responseText = "";

                    if (stream != null) {

                        java.util.Scanner scanner =
                                new java.util.Scanner(
                                        stream,
                                        "UTF-8"
                                ).useDelimiter("\\A");

                        responseText =
                                scanner.hasNext()
                                        ? scanner.next()
                                        : "";

                        scanner.close();
                    }

                    if (code >= 200 &&
                            code < 300) {

                        JSONObject result =
                                new JSONObject(
                                        responseText
                                );

                        String translatedText =
                                result.optString(
                                        "translated_text",
                                        ""
                                );

                        String translatedSrtUrl =
                                result.optString(
                                        "translated_srt_url", ""
                                ).trim();

                        Log.d(
                                "VideoDubbing",
                                "TRANSLATED SRT URL: " + translatedSrtUrl
                        );

                        if (!translatedSrtUrl.isEmpty()) {

                            try {
                                String translatedSrtContent =
                                        downloadSrtContent(
                                                translatedSrtUrl
                                        );

                                Log.d(
                                        "VideoDubbing",
                                        "TRANSLATED SRT CONTENT LENGTH: " +
                                                translatedSrtContent.length()
                                );

                                final String srtForWeb =
                                        translatedSrtContent;

                                webView.post(() ->
                                        webView.evaluateJavascript(
                                                "showTranslatedSrt(" +
                                                        JSONObjectQuote(
                                                                srtForWeb
                                                        ) +
                                                        ")",
                                                null
                                        )
                                );

                                saveSrtToDownloads(
                                        translatedSrtUrl,
                                        "Translated_Subtitles.srt"
                                );

                                Log.d(
                                        "VideoDubbing",
                                        "TRANSLATED SRT SAVED TO DOWNLOADS"
                                );

                            } catch (Exception srtSaveError) {
                                Log.e(
                                        "VideoDubbing",
                                        "TRANSLATED SRT SAVE FAILED",
                                        srtSaveError
                                );
                            }
                        }

                        String ttsUrl =
                                result.optString(
                                        "tts_url",
                                        ""
                                );

                        String ttsFileName =
                                result.optString(
                                        "tts_file",
                                        ""
                                ).trim();

                        if (translatedText.isEmpty()) {
                            throw new Exception(
                                    "Translation is empty"
                            );
                        }

                        if (ttsUrl.isEmpty()) {
                            throw new Exception(
                                    "TTS URL is empty"
                            );
                        }

                        if (ttsFileName.isEmpty()) {
                            throw new Exception(
                                    "TTS file name is empty"
                            );
                        }

                        Log.d(
                                "VideoDubbing",
                                "TRANSLATED: " +
                                        translatedText
                        );

                        Log.d(
                                "VideoDubbing",
                                "TTS URL: " +
                                        ttsUrl
                        );

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Downloading voice"
                                                ) +
                                                ",92)",
                                        null
                                )
                        );

                        File ttsFile =
                                downloadTtsAudio(ttsUrl);

                        logTtsFormat(ttsFile);

                        Log.d(
                                "VideoDubbing",
                                "TTS FILE READY: " +
                                        ttsFile.getAbsolutePath()
                        );

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Voice downloaded"
                                                ) +
                                                ",94)",
                                        null
                                )
                        );

                        // STEP 5: Merge original video + generated TTS
                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Merging dubbed video..."
                                                ) +
                                                ",96)",
                                        null
                                )
                        );

                        JSONObject mergeRequest =
                                new JSONObject();

                        mergeRequest.put(
                                "video_file",
                                videoFile
                        );

                        mergeRequest.put(
                                "tts_file",
                                ttsFileName
                        );

                        URL mergeUrl = new URL(
                                "https://video-dubbing-apk.onrender.com/merge-video-audio"
                        );

                        byte[] mergeBody =
                                mergeRequest.toString()
                                        .getBytes("UTF-8");

                        HttpURLConnection mergeConn = null;
                        int mergeCode = -1;
                        Exception lastMergeError = null;

                        for (int mergeAttempt = 1; mergeAttempt <= 3; mergeAttempt++) {
                            try {
                                if (mergeAttempt > 1) {
                                    Thread.sleep(2000);
                                }

                                mergeConn =
                                        (HttpURLConnection)
                                                mergeUrl.openConnection();

                                mergeConn.setRequestMethod("POST");
                                mergeConn.setDoOutput(true);
                                mergeConn.setDoInput(true);
                                mergeConn.setConnectTimeout(30000);
                                mergeConn.setReadTimeout(600000);
                                mergeConn.setRequestProperty(
                                        "Content-Type",
                                        "application/json; charset=UTF-8"
                                );

                                OutputStream mergeOutput =
                                        mergeConn.getOutputStream();

                                mergeOutput.write(mergeBody);
                                mergeOutput.flush();
                                mergeOutput.close();

                                mergeCode =
                                        mergeConn.getResponseCode();

                                lastMergeError = null;
                                break;

                            } catch (Exception mergeRetryError) {
                                android.util.Log.e(
                                        "VideoDubbing",
                                        "MERGE ATTEMPT " + mergeAttempt +
                                                " FAILED: " +
                                                mergeRetryError.toString(),
                                        mergeRetryError
                                );

                                lastMergeError = mergeRetryError;

                                if (mergeConn != null) {
                                    mergeConn.disconnect();
                                    mergeConn = null;
                                }
                            }
                        }

                        if (lastMergeError != null) {
                            throw lastMergeError;
                        }

                        InputStream mergeStream =
                                mergeCode >= 400
                                        ? mergeConn.getErrorStream()
                                        : mergeConn.getInputStream();

                        String mergeResponseText = "";

                        if (mergeStream != null) {
                            java.util.Scanner mergeScanner =
                                    new java.util.Scanner(
                                            mergeStream,
                                            "UTF-8"
                                    ).useDelimiter("\\A");

                            mergeResponseText =
                                    mergeScanner.hasNext()
                                            ? mergeScanner.next()
                                            : "";

                            mergeScanner.close();
                        }

                        if (mergeCode < 200 ||
                                mergeCode >= 300) {

                            throw new Exception(
                                    "Video merge HTTP " +
                                            mergeCode +
                                            ": " +
                                            mergeResponseText
                            );
                        }

                        JSONObject mergeResult =
                                new JSONObject(
                                        mergeResponseText
                                );

                        String dubbedVideoUrl =
                                mergeResult.optString(
                                        "dubbed_video_url",
                                        ""
                                ).trim();

                        if (dubbedVideoUrl.isEmpty()) {
                            throw new Exception(
                                    "Dubbed video URL is empty"
                            );
                        }

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Dubbed video created"
                                                ) +
                                                ",98)",
                                        null
                                )
                        );

                        // STEP 6: Download final dubbed MP4
                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Downloading final video..."
                                                ) +
                                                ",99)",
                                        null
                                )
                        );

                        File finalDubbedVideo =
                                downloadDubbedVideo(
                                        dubbedVideoUrl
                                );

                        // STEP 7: Save final MP4 to Movies/VideoDubbing
                        savedDubbedVideoUri =
                                saveDubbedVideoToDownloads(
                                        finalDubbedVideo
                                );

                        Log.d(
                                "VideoDubbing",
                                "FINAL VIDEO URI: " +
                                        savedDubbedVideoUri
                        );

                        webView.post(() ->
                                webView.evaluateJavascript(
                                        "setDubbingProgress(" +
                                                JSONObjectQuote(
                                                        "Dubbed video saved successfully"
                                                ) +
                                                ",100)",
                                        null
                                )
                        );

                    } else {

                        final String error =
                                "Translation/TTS HTTP " +
                                        code +
                                        ": " +
                                        responseText;

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

                    if (translationConn != null) {
                        translationConn.disconnect();
                    }
                }

            }).start();
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


            String name =
                    getFileName(selectedMediaUri);

            sendToJavaScript(
                    "nativeMediaSelected",
                    name
            );

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
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){" +
                "var s=document.getElementById('savedVideoPlayerScreen');" +
                "if(s && getComputedStyle(s).display !== 'none'){" +
                "var v=document.getElementById('savedDubbedVideoPlayer');" +
                "if(v){try{v.pause();}catch(e){}" +
                "try{v.currentTime=0;}catch(e){}" +
                "try{v.removeAttribute('src');}catch(e){}" +
                "try{v.load();}catch(e){}}" +
                "s.style.display='none';" +
                "var saved=document.getElementById('savedDubbedScreen');" +
                "if(saved){saved.style.display='block';}" +
                "return;" +
                "}" +
                "document.querySelectorAll('video,audio').forEach(function(m){" +
                "try{m.pause();}catch(e){}" +
                "});" +
                "})()",
                null
            );
            return;
        }
        super.onBackPressed();
    }



    @android.webkit.JavascriptInterface
    public int getVolumePercent() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return 50;

        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);

        if (max <= 0) return 50;
        return Math.round((current * 100f) / max);
    }

    @android.webkit.JavascriptInterface
    public void setVolumePercent(int percent) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = Math.max(0, Math.min(100, percent));
        int volume = Math.round((value / 100f) * max);

        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volume,
            0
        );
    }

    @android.webkit.JavascriptInterface
    public int getBrightnessPercent() {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();

        float b = lp.screenBrightness;

        if (b < 0) {
            b = 0.5f;
        }

        return Math.round(b * 100f);
    }

    @android.webkit.JavascriptInterface
    public void setBrightnessPercent(int percent) {
        int value = Math.max(1, Math.min(100, percent));

        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value / 100f;
        getWindow().setAttributes(lp);
    }


    @android.webkit.JavascriptInterface
    public void rotateVideoScreen() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int orientation =
                        getResources().getConfiguration().orientation;

                if (orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE) {

                    setRequestedOrientation(
                            android.content.pm.ActivityInfo
                                    .SCREEN_ORIENTATION_PORTRAIT
                    );

                } else {

                    setRequestedOrientation(
                            android.content.pm.ActivityInfo
                                    .SCREEN_ORIENTATION_LANDSCAPE
                    );
                }
            }
        });
    }

}
