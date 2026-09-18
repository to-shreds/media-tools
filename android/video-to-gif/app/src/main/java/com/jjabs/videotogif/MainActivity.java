package com.jjabs.videotogif;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1001;
    private static final int REQUEST_SAVE_GIF = 1002;

    private Uri selectedVideo;
    private String selectedName = "video";

    private Button pickButton;
    private Button convertButton;
    private Spinner widthSpinner;
    private Spinner fpsSpinner;
    private TextView fileLabel;
    private TextView statusLabel;
    private ProgressBar progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        subtitle.setText("Pick an MP4 or WebM and turn it into a looping GIF. Everything stays on your phone.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(85, 91, 103));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(22);
        root.addView(subtitle, subtitleParams);

        pickButton = new Button(this);
        pickButton.setText("Choose video");
        pickButton.setAllCaps(false);
        pickButton.setTextSize(16);
        pickButton.setOnClickListener(v -> pickVideo());
        root.addView(pickButton, fullWidth());

        fileLabel = new TextView(this);
        fileLabel.setText("No video selected");
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
        String[] rates = {"6 fps", "8 fps", "10 fps", "12 fps", "15 fps"};
        fpsSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, rates));
        fpsSpinner.setSelection(2);
        root.addView(fpsSpinner, fullWidth());

        TextView hint = new TextView(this);
        hint.setText("Tip: GIFs get large quickly. 480 px at 10 fps is a good default.");
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
        convertButton.setOnClickListener(v -> chooseOutputFile());
        root.addView(convertButton, fullWidth());

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

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    private void chooseOutputFile() {
        if (selectedVideo == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/gif");
        intent.putExtra(Intent.EXTRA_TITLE, makeOutputName(selectedName));
        startActivityForResult(intent, REQUEST_SAVE_GIF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        if (requestCode == REQUEST_PICK_VIDEO) {
            selectedVideo = uri;
            selectedName = queryDisplayName(uri);
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
            fileLabel.setText(selectedName);
            convertButton.setEnabled(true);
            statusLabel.setText("");
            progressBar.setVisibility(View.GONE);
        } else if (requestCode == REQUEST_SAVE_GIF) {
            int targetWidth = parseLeadingInt(widthSpinner.getSelectedItem().toString(), 480);
            int fps = parseLeadingInt(fpsSpinner.getSelectedItem().toString(), 10);
            convertVideo(selectedVideo, uri, targetWidth, fps);
        }
    }

    private void convertVideo(Uri inputUri, Uri outputUri, int targetWidth, int fps) {
        setBusy(true);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        statusLabel.setText("Reading video...");

        executor.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, inputUri);

                long durationMs = parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
                int sourceWidth = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                int sourceHeight = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                int rotation = (int) parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);

                if (durationMs <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
                    throw new IllegalArgumentException("Could not read the video dimensions or duration.");
                }

                if (rotation == 90 || rotation == 270) {
                    int swap = sourceWidth;
                    sourceWidth = sourceHeight;
                    sourceHeight = swap;
                }

                int outWidth = Math.min(targetWidth, sourceWidth);
                int outHeight = Math.max(1, Math.round(sourceHeight * (outWidth / (float) sourceWidth)));

                long durationUs = durationMs * 1000L;
                long stepUs = Math.max(1L, 1_000_000L / fps);
                int frameCount = Math.max(1, (int) ((durationUs + stepUs - 1) / stepUs));
                int delayCs = Math.max(1, Math.round(100f / fps));

                ContentResolver resolver = getContentResolver();
                try (OutputStream output = resolver.openOutputStream(outputUri, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("Could not open the output file.");
                    }

                    GifEncoder encoder = new GifEncoder(output, outWidth, outHeight, delayCs);
                    int encoded = 0;

                    for (int i = 0; i < frameCount; i++) {
                        long timeUs = Math.min(i * stepUs, Math.max(0, durationUs - 1));
                        Bitmap frame = retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                outWidth,
                                outHeight);

                        if (frame != null) {
                            encoder.addFrame(frame);
                            frame.recycle();
                            encoded++;
                        }

                        int progress = Math.min(100, Math.round(((i + 1) * 100f) / frameCount));
                        int shownFrame = i + 1;
                        runOnUiThread(() -> {
                            progressBar.setProgress(progress);
                            statusLabel.setText(String.format(Locale.US,
                                    "Converting... %d%%  (%d/%d frames)",
                                    progress, shownFrame, frameCount));
                        });
                    }

                    if (encoded == 0) {
                        throw new IllegalStateException("Android could not decode any frames from this video.");
                    }

                    encoder.finish();
                }

                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    statusLabel.setText("Done. GIF saved.");
                    setBusy(false);
                    Toast.makeText(this, "GIF saved", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusLabel.setText("Conversion failed: " + safeMessage(e));
                    setBusy(false);
                });
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        widthSpinner.setEnabled(!busy);
        fpsSpinner.setEnabled(!busy);
        convertButton.setEnabled(!busy && selectedVideo != null);
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

    private long parseLong(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text);
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
}
