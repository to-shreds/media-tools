package com.jjabs.videotogif;

import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GifToMp4Activity extends Activity {
    private static final int REQUEST_PICK_GIF = 3001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri gifUri;
    private String gifName = "animation.gif";
    private GifTimeline timeline;

    private Button loadButton;
    private Button convertButton;
    private TextView fileLabel;
    private TextView statusLabel;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GIF to MP4");
        buildUi();
    }

    private void buildUi() {
        int pad = dp(18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(android.graphics.Color.rgb(248, 249, 252));
        scroll.addView(root);

        Button back = new Button(this);
        back.setText("Back");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, fullWidth());

        TextView title = new TextView(this);
        title.setText("GIF to MP4");
        title.setTextSize(26);
        title.setTextColor(android.graphics.Color.rgb(24, 28, 36));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.topMargin = dp(14);
        root.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Convert an animated GIF to a standard H.264 MP4 while preserving its frame timing.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(android.graphics.Color.rgb(85, 91, 103));
        LinearLayout.LayoutParams subtitleParams = fullWidth();
        subtitleParams.topMargin = dp(7);
        subtitleParams.bottomMargin = dp(18);
        root.addView(subtitle, subtitleParams);

        loadButton = new Button(this);
        loadButton.setText("Load GIF");
        loadButton.setAllCaps(false);
        loadButton.setTextSize(16);
        loadButton.setOnClickListener(v -> pickGif());
        root.addView(loadButton, fullWidth());

        fileLabel = new TextView(this);
        fileLabel.setText("No GIF loaded");
        fileLabel.setTextSize(14);
        fileLabel.setTextColor(android.graphics.Color.rgb(90, 96, 108));
        LinearLayout.LayoutParams fileParams = fullWidth();
        fileParams.topMargin = dp(8);
        fileParams.bottomMargin = dp(16);
        root.addView(fileLabel, fileParams);

        TextView note = new TextView(this);
        note.setText(
                "MP4 does not support GIF transparency, so transparent areas are rendered black.");
        note.setTextSize(13);
        note.setTextColor(android.graphics.Color.rgb(105, 111, 122));
        LinearLayout.LayoutParams noteParams = fullWidth();
        noteParams.bottomMargin = dp(18);
        root.addView(note, noteParams);

        convertButton = new Button(this);
        convertButton.setText("Convert to MP4");
        convertButton.setAllCaps(false);
        convertButton.setTextSize(16);
        convertButton.setEnabled(false);
        convertButton.setOnClickListener(v -> convertGif());
        root.addView(convertButton, fullWidth());

        progressBar = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6));
        progressParams.topMargin = dp(18);
        root.addView(progressBar, progressParams);

        statusLabel = new TextView(this);
        statusLabel.setText("");
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setTextSize(14);
        statusLabel.setTextColor(android.graphics.Color.rgb(75, 82, 94));
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.topMargin = dp(8);
        root.addView(statusLabel, statusParams);

        setContentView(scroll);
    }

    private void pickGif() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(
                Intent.createChooser(intent, "Choose a GIF"),
                REQUEST_PICK_GIF);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_GIF
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        loadGif(data.getData());
    }

    private void loadGif(Uri uri) {
        setBusy(true);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Reading GIF...");

        executor.execute(() -> {
            try {
                GifTimeline parsed;

                try (InputStream input = new BufferedInputStream(
                        getContentResolver().openInputStream(uri))) {
                    if (input == null) {
                        throw new IllegalStateException("Could not open the GIF.");
                    }

                    parsed = GifTimeline.parse(input);
                }

                String name = queryDisplayName(uri);

                runOnUiThread(() -> {
                    gifUri = uri;
                    gifName = name;
                    timeline = parsed;

                    fileLabel.setText(String.format(
                            Locale.US,
                            "%s  •  %d×%d  •  %d frames  •  %.2f s",
                            name,
                            parsed.width,
                            parsed.height,
                            parsed.frameCount(),
                            parsed.durationMs / 1000f));

                    convertButton.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("");
                    setBusy(false);
                });
            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    gifUri = null;
                    timeline = null;
                    convertButton.setEnabled(false);
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("Could not load GIF: " + message);
                    setBusy(false);
                });
            }
        });
    }

    private void convertGif() {
        if (gifUri == null || timeline == null) {
            return;
        }

        Uri sourceUri = gifUri;
        String sourceName = gifName;

        setBusy(true);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        statusLabel.setText("Preparing MP4...");

        executor.execute(() -> {
            OutputTarget target = null;

            try {
                target = createOutputTarget(
                        sourceUri,
                        makeOutputName(sourceName));

                if (target == null || target.uri == null) {
                    throw new IllegalStateException(
                            "Could not create the MP4 output.");
                }

                OutputTarget finalTarget = target;

                GifToMp4Encoder.convert(
                        this,
                        sourceUri,
                        target.uri,
                        percent -> runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            statusLabel.setText(
                                    "Converting... " + percent + "%");
                        }));

                completeOutput(finalTarget);

                boolean fallback = target.fallback;

                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    statusLabel.setText(
                            fallback
                                    ? "MP4 saved in Downloads/VideoToGif."
                                    : "MP4 saved beside the original GIF.");
                    Toast.makeText(
                            this,
                            "MP4 saved",
                            Toast.LENGTH_SHORT).show();
                    setBusy(false);
                });
            } catch (Exception e) {
                if (target != null && target.uri != null) {
                    deleteQuietly(target.uri);
                }

                String message = safeMessage(e);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("Conversion failed: " + message);
                    setBusy(false);
                });
            }
        });
    }

    private OutputTarget createOutputTarget(
            Uri sourceUri,
            String displayName) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = resolveRelativePath(sourceUri);
            String volumeName = resolveVolumeName(sourceUri);

            if (relativePath != null) {
                Uri sameFolder = insertMp4(
                        volumeName,
                        relativePath,
                        displayName);

                if (sameFolder != null) {
                    return new OutputTarget(
                            sameFolder,
                            false,
                            true);
                }
            }

            Uri fallback = insertMp4(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    "Download/VideoToGif/",
                    displayName);

            if (fallback != null) {
                return new OutputTarget(
                        fallback,
                        true,
                        true);
            }
        }

        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                displayName);
        values.put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4");

        Uri uri = getContentResolver().insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values);

        return uri == null
                ? null
                : new OutputTarget(uri, true, false);
    }

    private Uri insertMp4(
            String volumeName,
            String relativePath,
            String displayName) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        String volume = volumeName;

        if (volume == null || volume.isBlank()) {
            volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }

        try {
            Set<String> available =
                    MediaStore.getExternalVolumeNames(this);

            if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) {
                String matched = null;

                for (String candidate : available) {
                    if (candidate.equalsIgnoreCase(volume)) {
                        matched = candidate;
                        break;
                    }
                }

                volume = matched == null
                        ? MediaStore.VOLUME_EXTERNAL_PRIMARY
                        : matched;
            }

            ContentValues values = new ContentValues();
            values.put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    displayName);
            values.put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4");
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    normalizeRelativePath(relativePath));
            values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    1);

            return getContentResolver().insert(
                    MediaStore.Video.Media.getContentUri(volume),
                    values);
        } catch (Exception e) {
            return null;
        }
    }

    private void completeOutput(OutputTarget target) {
        if (target == null
                || !target.pending
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(
                    target.uri,
                    values,
                    null,
                    null);
        } catch (Exception ignored) {
        }
    }

    private String resolveRelativePath(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        String direct = queryStringColumn(
                uri,
                MediaStore.MediaColumns.RELATIVE_PATH);

        if (direct != null) {
            return normalizeRelativePath(direct);
        }

        String authority = uri.getAuthority();

        if ("com.android.providers.externalstorage.documents".equals(authority)
                && DocumentsContract.isDocumentUri(this, uri)) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                int colon = id.indexOf(':');
                String path = colon >= 0
                        ? id.substring(colon + 1)
                        : id;
                int slash = path.lastIndexOf('/');

                if (slash < 0) {
                    return "";
                }

                return normalizeRelativePath(
                        path.substring(0, slash + 1));
            } catch (Exception ignored) {
            }
        }

        Uri mediaUri = resolveMediaStoreUri(uri);

        if (mediaUri != null) {
            String path = queryStringColumn(
                    mediaUri,
                    MediaStore.MediaColumns.RELATIVE_PATH);

            if (path != null) {
                return normalizeRelativePath(path);
            }
        }

        return null;
    }

    private String resolveVolumeName(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        String direct = queryStringColumn(
                uri,
                MediaStore.MediaColumns.VOLUME_NAME);

        if (direct != null) {
            return direct;
        }

        String authority = uri.getAuthority();

        if ("com.android.providers.externalstorage.documents".equals(authority)
                && DocumentsContract.isDocumentUri(this, uri)) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                int colon = id.indexOf(':');
                String volume = colon >= 0
                        ? id.substring(0, colon)
                        : id;

                if ("primary".equalsIgnoreCase(volume)) {
                    return MediaStore.VOLUME_EXTERNAL_PRIMARY;
                }

                return volume.toLowerCase(Locale.US);
            } catch (Exception ignored) {
            }
        }

        Uri mediaUri = resolveMediaStoreUri(uri);

        if (mediaUri != null) {
            String volume = queryStringColumn(
                    mediaUri,
                    MediaStore.MediaColumns.VOLUME_NAME);

            if (volume != null) {
                return volume;
            }
        }

        return MediaStore.VOLUME_EXTERNAL_PRIMARY;
    }

    private Uri resolveMediaStoreUri(Uri uri) {
        String authority = uri.getAuthority();

        if (!"com.android.providers.media.documents".equals(authority)
                || !DocumentsContract.isDocumentUri(this, uri)) {
            return null;
        }

        try {
            String id = DocumentsContract.getDocumentId(uri);
            String[] parts = id.split(":", 2);

            if (parts.length != 2) {
                return null;
            }

            long rowId = Long.parseLong(parts[1]);
            Uri base;

            switch (parts[0]) {
                case "image":
                    base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    break;
                case "audio":
                    base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    break;
                case "video":
                default:
                    base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    break;
            }

            return ContentUris.withAppendedId(base, rowId);
        } catch (Exception e) {
            return null;
        }
    }

    private String queryStringColumn(
            Uri uri,
            String column) {

        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{column},
                null,
                null,
                null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(column);

                if (index >= 0 && !cursor.isNull(index)) {
                    String value = cursor.getString(index);

                    return value == null || value.isBlank()
                            ? null
                            : value;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String normalizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        String normalized = path.replace('\\', '/');

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (!normalized.endsWith("/")) {
            normalized += "/";
        }

        return normalized;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);

                if (index >= 0) {
                    String name = cursor.getString(index);

                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "animation.gif";
    }

    private String makeOutputName(String inputName) {
        String base = inputName == null
                ? "animation"
                : inputName;
        int dot = base.lastIndexOf('.');

        if (dot > 0) {
            base = base.substring(0, dot);
        }

        return base + ".mp4";
    }

    private void deleteQuietly(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private void setBusy(boolean busy) {
        loadButton.setEnabled(!busy);
        convertButton.setEnabled(
                !busy && gifUri != null && timeline != null);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();

        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static final class OutputTarget {
        final Uri uri;
        final boolean fallback;
        final boolean pending;

        OutputTarget(
                Uri uri,
                boolean fallback,
                boolean pending) {
            this.uri = uri;
            this.fallback = fallback;
            this.pending = pending;
        }
    }
}
