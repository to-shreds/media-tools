package com.jjabs.videotogif;

import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GifTrimActivity extends Activity {
    private static final int REQUEST_PICK_GIF = 2001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri gifUri;
    private String gifName = "animation.gif";
    private GifTimeline timeline;
    private Movie previewMovie;
    private Bitmap previewBitmap;

    private int currentFrame;
    private int startFrame;
    private int endFrame;

    private Button loadButton;
    private Button previousButton;
    private Button nextButton;
    private Button setStartButton;
    private Button setEndButton;
    private Button saveButton;
    private SeekBar frameSlider;
    private ImageView preview;
    private TextView fileLabel;
    private TextView frameLabel;
    private TextView selectionLabel;
    private TextView statusLabel;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GIF Trim");
        buildUi();
    }

    private void buildUi() {
        int pad = dp(18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        scroll.addView(root);

        Button back = new Button(this);
        back.setText("Back to video converter");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, fullWidth());

        TextView title = new TextView(this);
        title.setText("Trim an existing GIF");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(24, 28, 36));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.topMargin = dp(14);
        root.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("Scrub frame by frame, mark the first and last frames you want, then save that section as its own GIF.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(85, 91, 103));
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
        fileLabel.setTextColor(Color.rgb(90, 96, 108));
        LinearLayout.LayoutParams fileParams = fullWidth();
        fileParams.topMargin = dp(7);
        fileParams.bottomMargin = dp(14);
        root.addView(fileLabel, fileParams);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.rgb(225, 228, 234));
        preview.setMinimumHeight(dp(220));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(preview, previewParams);

        frameLabel = new TextView(this);
        frameLabel.setText("Frame -");
        frameLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        frameLabel.setTextSize(15);
        frameLabel.setTextColor(Color.rgb(45, 51, 61));
        LinearLayout.LayoutParams frameLabelParams = fullWidth();
        frameLabelParams.topMargin = dp(10);
        root.addView(frameLabel, frameLabelParams);

        frameSlider = new SeekBar(this);
        frameSlider.setMax(0);
        frameSlider.setEnabled(false);
        frameSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (timeline == null) {
                    return;
                }
                currentFrame = Math.max(0, Math.min(progress, timeline.frameCount() - 1));
                updateFrameLabel();
                renderCurrentFrame();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(frameSlider, fullWidth());

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);

        previousButton = new Button(this);
        previousButton.setText("Previous");
        previousButton.setAllCaps(false);
        previousButton.setEnabled(false);
        previousButton.setOnClickListener(v -> stepFrame(-1));

        nextButton = new Button(this);
        nextButton.setText("Next");
        nextButton.setAllCaps(false);
        nextButton.setEnabled(false);
        nextButton.setOnClickListener(v -> stepFrame(1));

        navRow.addView(previousButton, weighted());
        navRow.addView(nextButton, weighted());

        LinearLayout.LayoutParams navParams = fullWidth();
        navParams.topMargin = dp(4);
        root.addView(navRow, navParams);

        LinearLayout markRow = new LinearLayout(this);
        markRow.setOrientation(LinearLayout.HORIZONTAL);

        setStartButton = new Button(this);
        setStartButton.setText("Set Start");
        setStartButton.setAllCaps(false);
        setStartButton.setEnabled(false);
        setStartButton.setOnClickListener(v -> {
            startFrame = currentFrame;
            if (startFrame > endFrame) {
                endFrame = startFrame;
            }
            updateSelectionLabel();
        });

        setEndButton = new Button(this);
        setEndButton.setText("Set End");
        setEndButton.setAllCaps(false);
        setEndButton.setEnabled(false);
        setEndButton.setOnClickListener(v -> {
            endFrame = currentFrame;
            if (endFrame < startFrame) {
                startFrame = endFrame;
            }
            updateSelectionLabel();
        });

        markRow.addView(setStartButton, weighted());
        markRow.addView(setEndButton, weighted());

        LinearLayout.LayoutParams markParams = fullWidth();
        markParams.topMargin = dp(6);
        root.addView(markRow, markParams);

        selectionLabel = new TextView(this);
        selectionLabel.setText("Selection: -");
        selectionLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        selectionLabel.setTextSize(14);
        selectionLabel.setTextColor(Color.rgb(65, 72, 84));
        LinearLayout.LayoutParams selectionParams = fullWidth();
        selectionParams.topMargin = dp(10);
        selectionParams.bottomMargin = dp(12);
        root.addView(selectionLabel, selectionParams);

        saveButton = new Button(this);
        saveButton.setText("Save selected frames as GIF");
        saveButton.setAllCaps(false);
        saveButton.setTextSize(16);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveSelection());
        root.addView(saveButton, fullWidth());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
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
        statusLabel.setTextColor(Color.rgb(75, 82, 94));
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.topMargin = dp(8);
        root.addView(statusLabel, statusParams);

        setContentView(scroll);
    }

    private void pickGif() {
        // ACTION_GET_CONTENT deliberately avoids forcing the user through
        // DocumentsUI/DocumentsProvider. A real file-manager app such as
        // Samsung My Files can satisfy this intent and return any readable URI
        // it exposes, even when that file never appears in OPEN_DOCUMENT.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(intent, "Choose a GIF");
        startActivityForResult(chooser, REQUEST_PICK_GIF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_GIF
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }

        loadGif(uri);
    }

    private void loadGif(Uri uri) {
        setBusy(true);
        preview.setImageDrawable(null);
        fileLabel.setText(queryDisplayName(uri));
        statusLabel.setText("Reading GIF...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

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

                Movie movie;

                try (InputStream input = new BufferedInputStream(
                        getContentResolver().openInputStream(uri))) {
                    if (input == null) {
                        throw new IllegalStateException("Could not reopen the GIF.");
                    }
                    movie = Movie.decodeStream(input);
                }

                if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
                    throw new IllegalStateException("Android could not decode this GIF.");
                }

                String name = queryDisplayName(uri);

                runOnUiThread(() -> {
                    gifUri = uri;
                    gifName = name;
                    timeline = parsed;
                    previewMovie = movie;
                    currentFrame = 0;
                    startFrame = 0;
                    endFrame = parsed.frameCount() - 1;

                    frameSlider.setMax(Math.max(0, parsed.frameCount() - 1));
                    frameSlider.setProgress(0);
                    frameSlider.setEnabled(parsed.frameCount() > 1);

                    previousButton.setEnabled(parsed.frameCount() > 1);
                    nextButton.setEnabled(parsed.frameCount() > 1);
                    setStartButton.setEnabled(true);
                    setEndButton.setEnabled(true);
                    saveButton.setEnabled(true);

                    fileLabel.setText(String.format(
                            Locale.US,
                            "%s  •  %d frames  •  %.2f s",
                            name,
                            parsed.frameCount(),
                            parsed.durationMs / 1000f));

                    updateFrameLabel();
                    updateSelectionLabel();
                    renderCurrentFrame();

                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("");
                    setBusy(false);
                });
            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("Could not load GIF: " + message);
                    clearLoadedGif();
                    setBusy(false);
                });
            }
        });
    }

    private void stepFrame(int amount) {
        if (timeline == null) {
            return;
        }

        int next = Math.max(
                0,
                Math.min(currentFrame + amount, timeline.frameCount() - 1));
        frameSlider.setProgress(next);
    }

    private void updateFrameLabel() {
        if (timeline == null) {
            frameLabel.setText("Frame -");
            return;
        }

        frameLabel.setText(String.format(
                Locale.US,
                "Frame %d of %d  •  %.2f s",
                currentFrame + 1,
                timeline.frameCount(),
                timeline.frameTimeMs(currentFrame) / 1000f));
    }

    private void updateSelectionLabel() {
        if (timeline == null) {
            selectionLabel.setText("Selection: -");
            return;
        }

        int frameCount = endFrame - startFrame + 1;
        float seconds = timeline.selectionDurationMs(startFrame, endFrame) / 1000f;

        selectionLabel.setText(String.format(
                Locale.US,
                "Selection: frames %d–%d  •  %d frame%s  •  %.2f s",
                startFrame + 1,
                endFrame + 1,
                frameCount,
                frameCount == 1 ? "" : "s",
                seconds));

        saveButton.setText(String.format(
                Locale.US,
                "Save frames %d–%d as GIF",
                startFrame + 1,
                endFrame + 1));
    }

    private void renderCurrentFrame() {
        if (timeline == null || previewMovie == null) {
            return;
        }

        try {
            Bitmap bitmap = renderMovieFrame(
                    previewMovie,
                    timeline.frameTimeMs(currentFrame),
                    720);

            Bitmap old = previewBitmap;
            previewBitmap = bitmap;
            preview.setImageBitmap(bitmap);

            if (old != null && old != bitmap && !old.isRecycled()) {
                old.recycle();
            }
        } catch (Exception e) {
            statusLabel.setText("Could not render frame: " + safeMessage(e));
        }
    }

    private Bitmap renderMovieFrame(Movie movie, int timeMs, int maxWidth) {
        int sourceWidth = movie.width();
        int sourceHeight = movie.height();

        int width = sourceWidth;
        int height = sourceHeight;

        if (maxWidth > 0 && width > maxWidth) {
            float scale = maxWidth / (float) width;
            width = maxWidth;
            height = Math.max(1, Math.round(height * scale));
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);

        Canvas canvas = new Canvas(bitmap);
        float scaleX = width / (float) sourceWidth;
        float scaleY = height / (float) sourceHeight;
        canvas.scale(scaleX, scaleY);

        int duration = movie.duration();
        int safeTime = timeMs;

        if (duration > 0) {
            safeTime = Math.max(0, Math.min(timeMs, duration - 1));
        }

        movie.setTime(safeTime);
        movie.draw(canvas, 0f, 0f);

        return bitmap;
    }

    private void saveSelection() {
        if (gifUri == null || timeline == null) {
            return;
        }

        final Uri sourceUri = gifUri;
        final String sourceName = gifName;
        final GifTimeline sourceTimeline = timeline;
        final int first = startFrame;
        final int last = endFrame;

        setBusy(true);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        statusLabel.setText("Preparing clip...");

        executor.execute(() -> {
            OutputTarget target = null;

            try {
                target = createOutputTarget(
                        sourceUri,
                        makeClipOutputName(sourceName));

                if (target == null || target.uri == null) {
                    throw new IllegalStateException("Could not create the output GIF.");
                }

                Movie movie;

                try (InputStream input = new BufferedInputStream(
                        getContentResolver().openInputStream(sourceUri))) {
                    if (input == null) {
                        throw new IllegalStateException("Could not reopen the GIF.");
                    }
                    movie = Movie.decodeStream(input);
                }

                if (movie == null) {
                    throw new IllegalStateException("Android could not decode the GIF for export.");
                }

                int width = movie.width();
                int height = movie.height();
                int total = last - first + 1;

                try (OutputStream output = getContentResolver().openOutputStream(target.uri, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("Could not open the output GIF.");
                    }

                    GifEncoder encoder = new GifEncoder(output, width, height, 10);

                    for (int frame = first; frame <= last; frame++) {
                        Bitmap bitmap = renderMovieFrame(
                                movie,
                                sourceTimeline.frameTimeMs(frame),
                                0);

                        try {
                            encoder.addFrame(
                                    bitmap,
                                    sourceTimeline.frameDelayCs(frame));
                        } finally {
                            bitmap.recycle();
                        }

                        int done = frame - first + 1;
                        int percent = Math.round(done * 100f / total);
                        int shownFrame = frame + 1;

                        runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            statusLabel.setText(String.format(
                                    Locale.US,
                                    "Saving frame %d of %d  •  %d%%",
                                    shownFrame - first,
                                    total,
                                    percent));
                        });
                    }

                    encoder.finish();
                }

                completeOutput(target);

                boolean fallback = target.fallback;

                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    statusLabel.setText(
                            fallback
                                    ? "Clip saved in Downloads/VideoToGif."
                                    : "Clip saved beside the original GIF.");
                    Toast.makeText(
                            this,
                            "GIF clip saved",
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
                    statusLabel.setText("Could not save clip: " + message);
                    setBusy(false);
                });
            }
        });
    }

    private OutputTarget createOutputTarget(Uri sourceUri, String displayName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = resolveRelativePath(sourceUri);
            String volumeName = resolveVolumeName(sourceUri);

            if (relativePath != null) {
                Uri sameFolder = insertGif(
                        volumeName,
                        relativePath,
                        displayName);

                if (sameFolder != null) {
                    return new OutputTarget(sameFolder, false, true);
                }
            }

            Uri fallback = insertGif(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    "Download/VideoToGif/",
                    displayName);

            if (fallback != null) {
                return new OutputTarget(fallback, true, true);
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");

        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);

        return uri == null
                ? null
                : new OutputTarget(uri, true, false);
    }

    private Uri insertGif(
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
            Set<String> available = MediaStore.getExternalVolumeNames(this);

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
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    normalizeRelativePath(relativePath));
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            return getContentResolver().insert(
                    MediaStore.Images.Media.getContentUri(volume),
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
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(target.uri, values, null, null);
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

        return "animation.gif";
    }

    private String makeClipOutputName(String inputName) {
        String base = inputName == null ? "animation" : inputName;
        int dot = base.lastIndexOf('.');

        if (dot > 0) {
            base = base.substring(0, dot);
        }

        return base + "_clip.gif";
    }

    private void deleteQuietly(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private void clearLoadedGif() {
        gifUri = null;
        timeline = null;
        previewMovie = null;
        preview.setImageDrawable(null);

        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
        frameSlider.setMax(0);
        frameSlider.setProgress(0);
        frameSlider.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        setStartButton.setEnabled(false);
        setEndButton.setEnabled(false);
        saveButton.setEnabled(false);
        frameLabel.setText("Frame -");
        selectionLabel.setText("Selection: -");
    }

    private void setBusy(boolean busy) {
        loadButton.setEnabled(!busy);
        frameSlider.setEnabled(!busy && timeline != null && timeline.frameCount() > 1);
        previousButton.setEnabled(!busy && timeline != null && timeline.frameCount() > 1);
        nextButton.setEnabled(!busy && timeline != null && timeline.frameCount() > 1);
        setStartButton.setEnabled(!busy && timeline != null);
        setEndButton.setEnabled(!busy && timeline != null);
        saveButton.setEnabled(!busy && timeline != null);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
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
