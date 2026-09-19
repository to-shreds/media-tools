package com.jjabs.videotogif;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEOS = 1001;

    private final List<InputItem> selectedVideos = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Button pickButton;
    private Button convertButton;
    private Spinner widthSpinner;
    private Spinner fpsSpinner;
    private TextView fileLabel;
    private TextView statusLabel;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Video to GIF");
        buildUi();
    }

    private void buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        TextView title = new TextView(this);
        title.setText("Video to GIF");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(24, 28, 36));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Pick one or more MP4/WebM files. GIFs save beside the originals whenever Android allows it.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(85, 91, 103));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(22);
        root.addView(subtitle, subtitleParams);

        pickButton = new Button(this);
        pickButton.setText("Choose videos");
        pickButton.setAllCaps(false);
        pickButton.setTextSize(16);
        pickButton.setOnClickListener(v -> pickVideos());
        root.addView(pickButton, fullWidth());

        fileLabel = new TextView(this);
        fileLabel.setText("No videos selected");
        fileLabel.setTextSize(14);
        fileLabel.setTextColor(Color.rgb(90, 96, 108));
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fileParams.topMargin = dp(8);
        fileParams.bottomMargin = dp(18);
        root.addView(fileLabel, fileParams);

        TextView sizeLabel = fieldLabel("GIF width");
        root.addView(sizeLabel);

        widthSpinner = new Spinner(this);
        String[] widths = {"320 px", "480 px", "640 px", "720 px"};
        widthSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, widths));
        widthSpinner.setSelection(1);
        root.addView(widthSpinner, fullWidth());

        TextView fpsLabel = fieldLabel("Frame rate");
        LinearLayout.LayoutParams fpsLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fpsLabelParams.topMargin = dp(14);
        root.addView(fpsLabel, fpsLabelParams);

        fpsSpinner = new Spinner(this);
        String[] rates = {"5 fps", "10 fps", "15 fps", "20 fps", "25 fps", "30 fps", "35 fps", "40 fps", "45 fps", "50 fps", "55 fps", "60 fps"};
        fpsSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, rates));
        fpsSpinner.setSelection(1);
        root.addView(fpsSpinner, fullWidth());

        TextView hint = new TextView(this);
        hint.setText("480 px at 10 fps is a good default. Higher frame rates can make GIFs dramatically larger.");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(105, 111, 122));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(10);
        hintParams.bottomMargin = dp(20);
        root.addView(hint, hintParams);

        convertButton = new Button(this);
        convertButton.setText("Convert to GIF");
        convertButton.setAllCaps(false);
        convertButton.setTextSize(16);
        convertButton.setEnabled(false);
        convertButton.setOnClickListener(v -> convertSelectedVideos());
        root.addView(convertButton, fullWidth());

        Button trimGifButton = new Button(this);
        trimGifButton.setText("Trim an existing GIF");
        trimGifButton.setAllCaps(false);
        trimGifButton.setTextSize(16);
        trimGifButton.setOnClickListener(v ->
                startActivity(new Intent(this, GifTrimActivity.class)));
        LinearLayout.LayoutParams trimParams = fullWidth();
        trimParams.topMargin = dp(10);
        root.addView(trimGifButton, trimParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        progressParams.topMargin = dp(22);
        root.addView(progressBar, progressParams);

        statusLabel = new TextView(this);
        statusLabel.setText("");
        statusLabel.setTextSize(14);
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setTextColor(Color.rgb(75, 82, 94));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        root.addView(statusLabel, statusParams);

        setContentView(root);
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(Color.rgb(45, 51, 61));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void pickVideos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_VIDEOS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEOS || resultCode != RESULT_OK || data == null) {
            return;
        }

        selectedVideos.clear();

        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                addSelectedVideo(clipData.getItemAt(i).getUri(), data);
            }
        } else if (data.getData() != null) {
            addSelectedVideo(data.getData(), data);
        }

        if (selectedVideos.isEmpty()) {
            fileLabel.setText("No videos selected");
            convertButton.setEnabled(false);
            return;
        }

        if (selectedVideos.size() == 1) {
            fileLabel.setText(selectedVideos.get(0).name);
        } else {
            fileLabel.setText(selectedVideos.size() + " videos selected");
        }

        convertButton.setText(selectedVideos.size() == 1
                ? "Convert to GIF"
                : "Convert " + selectedVideos.size() + " videos");
        convertButton.setEnabled(true);
        statusLabel.setText("");
        progressBar.setVisibility(View.GONE);
    }

    private void addSelectedVideo(Uri uri, Intent sourceIntent) {
        if (uri == null) {
            return;
        }

        try {
            int flags = sourceIntent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }

        selectedVideos.add(new InputItem(uri, queryDisplayName(uri)));
    }

    private void convertSelectedVideos() {
        if (selectedVideos.isEmpty()) {
            return;
        }

        final int targetWidth = parseLeadingInt(widthSpinner.getSelectedItem().toString(), 480);
        final int fps = parseLeadingInt(fpsSpinner.getSelectedItem().toString(), 10);
        final List<InputItem> work = new ArrayList<>(selectedVideos);

        setBusy(true);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        statusLabel.setText("Starting batch...");

        executor.execute(() -> {
            int successes = 0;
            int failures = 0;
            int fallbackSaves = 0;

            for (int index = 0; index < work.size(); index++) {
                InputItem item = work.get(index);
                OutputTarget target = null;

                try {
                    target = createOutputTarget(item);
                    if (target == null || target.uri == null) {
                        throw new IllegalStateException("Could not create an output file.");
                    }

                    if (target.fallback) {
                        fallbackSaves++;
                    }

                    int itemNumber = index + 1;
                    int total = work.size();
                    OutputTarget finalTarget = target;

                    runOnUiThread(() -> statusLabel.setText(
                            String.format(Locale.US,
                                    "%d of %d: %s",
                                    itemNumber,
                                    total,
                                    item.name)));

                    try (OutputStream output = getContentResolver().openOutputStream(target.uri, "w")) {
                        if (output == null) {
                            throw new IllegalStateException("Could not open the GIF output.");
                        }

                        VideoConverter.convert(
                                this,
                                item.uri,
                                output,
                                targetWidth,
                                fps,
                                itemPercent -> {
                                    int overall = Math.round(
                                            ((itemNumber - 1) + (itemPercent / 100f))
                                                    * 100f / total);
                                    runOnUiThread(() -> {
                                        progressBar.setProgress(overall);
                                        statusLabel.setText(String.format(
                                                Locale.US,
                                                "%d of %d: %s  %d%%",
                                                itemNumber,
                                                total,
                                                item.name,
                                                itemPercent));
                                    });
                                });
                    }

                    completeOutput(finalTarget);
                    successes++;
                } catch (Exception e) {
                    failures++;
                    if (target != null && target.uri != null) {
                        deleteQuietly(target.uri);
                    }

                    int itemNumber = index + 1;
                    int total = work.size();
                    String message = safeMessage(e);
                    runOnUiThread(() -> statusLabel.setText(String.format(
                            Locale.US,
                            "%d of %d failed: %s (%s)",
                            itemNumber,
                            total,
                            item.name,
                            message)));
                }
            }

            int finalSuccesses = successes;
            int finalFailures = failures;
            int finalFallbackSaves = fallbackSaves;

            runOnUiThread(() -> {
                progressBar.setProgress(100);
                setBusy(false);

                StringBuilder summary = new StringBuilder();
                summary.append(finalSuccesses)
                        .append(finalSuccesses == 1 ? " GIF saved" : " GIFs saved");

                if (finalFailures > 0) {
                    summary.append("; ")
                            .append(finalFailures)
                            .append(finalFailures == 1 ? " failed" : " failed");
                }

                if (finalFallbackSaves > 0) {
                    summary.append(". ")
                            .append(finalFallbackSaves)
                            .append(finalFallbackSaves == 1
                                    ? " had to save in Downloads/VideoToGif"
                                    : " had to save in Downloads/VideoToGif");
                }

                statusLabel.setText(summary.toString());
                Toast.makeText(this, summary.toString(), Toast.LENGTH_LONG).show();
            });
        });
    }

    private OutputTarget createOutputTarget(InputItem item) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = resolveRelativePath(item.uri);
            String volumeName = resolveVolumeName(item.uri);

            if (relativePath != null) {
                Uri sameFolder = insertGif(
                        volumeName,
                        relativePath,
                        makeOutputName(item.name));
                if (sameFolder != null) {
                    return new OutputTarget(sameFolder, false, true);
                }
            }

            Uri fallback = insertGif(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    "Download/VideoToGif/",
                    makeOutputName(item.name));
            if (fallback != null) {
                return new OutputTarget(fallback, true, true);
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, makeOutputName(item.name));
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);
        return uri == null ? null : new OutputTarget(uri, true, false);
    }

    private Uri insertGif(String volumeName, String relativePath, String displayName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        String volume = volumeName;
        if (volume == null || volume.isBlank()) {
            volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }

        try {
            Set<String> available = MediaStore.getExternalVolumeNames(this);
            if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) {
                String matched = null;
                for (String candidate : available) {
                    if (candidate.equalsIgnoreCase(volume)) {
                        matched = candidate;
                        break;
                    }
                }
                if (matched == null) {
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
                } else {
                    volume = matched;
                }
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, normalizeRelativePath(relativePath));
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            return getContentResolver().insert(
                    MediaStore.Images.Media.getContentUri(volume),
                    values);
        } catch (Exception e) {
            return null;
        }
    }

    private void completeOutput(OutputTarget target) {
        if (target == null || !target.pending || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(target.uri, values, null, null);
        } catch (Exception ignored) {
        }
    }

    private String resolveRelativePath(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        String direct = queryStringColumn(uri, MediaStore.MediaColumns.RELATIVE_PATH);
        if (direct != null) {
            return normalizeRelativePath(direct);
        }

        String authority = uri.getAuthority();

        if ("com.android.providers.externalstorage.documents".equals(authority)
                && DocumentsContract.isDocumentUri(this, uri)) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                int colon = id.indexOf(':');
                String path = colon >= 0 ? id.substring(colon + 1) : id;
                int slash = path.lastIndexOf('/');
                if (slash < 0) {
                    return "";
                }
                return normalizeRelativePath(path.substring(0, slash + 1));
            } catch (Exception ignored) {
            }
        }

        Uri mediaUri = resolveMediaStoreUri(uri);
        if (mediaUri != null) {
            String path = queryStringColumn(mediaUri, MediaStore.MediaColumns.RELATIVE_PATH);
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

        String direct = queryStringColumn(uri, MediaStore.MediaColumns.VOLUME_NAME);
        if (direct != null) {
            return direct;
        }

        String authority = uri.getAuthority();

        if ("com.android.providers.externalstorage.documents".equals(authority)
                && DocumentsContract.isDocumentUri(this, uri)) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                int colon = id.indexOf(':');
                String volume = colon >= 0 ? id.substring(0, colon) : id;
                if ("primary".equalsIgnoreCase(volume)) {
                    return MediaStore.VOLUME_EXTERNAL_PRIMARY;
                }
                return volume.toLowerCase(Locale.US);
            } catch (Exception ignored) {
            }
        }

        Uri mediaUri = resolveMediaStoreUri(uri);
        if (mediaUri != null) {
            String volume = queryStringColumn(mediaUri, MediaStore.MediaColumns.VOLUME_NAME);
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

    private String queryStringColumn(Uri uri, String column) {
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
                    return value == null || value.isBlank() ? null : value;
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

    private void deleteQuietly(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        widthSpinner.setEnabled(!busy);
        fpsSpinner.setEnabled(!busy);
        convertButton.setEnabled(!busy && !selectedVideos.isEmpty());
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "video";
    }

    private String makeOutputName(String inputName) {
        String base = inputName == null ? "video" : inputName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + ".gif";
    }

    private int parseLeadingInt(String text, int fallback) {
        try {
            String digits = text.replaceAll("[^0-9].*$", "");
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? e.getClass().getSimpleName()
                : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static final class InputItem {
        final Uri uri;
        final String name;

        InputItem(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class OutputTarget {
        final Uri uri;
        final boolean fallback;
        final boolean pending;

        OutputTarget(Uri uri, boolean fallback, boolean pending) {
            this.uri = uri;
            this.fallback = fallback;
            this.pending = pending;
        }
    }
}
