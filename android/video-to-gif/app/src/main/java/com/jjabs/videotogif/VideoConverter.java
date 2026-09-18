package com.jjabs.videotogif;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class VideoConverter {
    interface ProgressListener {
        void onProgress(int percent);
    }

    private VideoConverter() {
    }

    static void convert(
            Context context,
            Uri inputUri,
            OutputStream output,
            int targetWidth,
            int fps,
            ProgressListener progressListener) throws Exception {

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        ImageReader imageReader = null;
        HandlerThread imageThread = null;

        try {
            extractor.setDataSource(context, inputUri, null);

            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = candidate;
                    break;
                }
            }

            if (videoTrack < 0 || format == null) {
                throw new IllegalArgumentException("No video track found.");
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IllegalArgumentException("Video codec could not be identified.");
            }

            int sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            int sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? normalizeRotation(format.getInteger(MediaFormat.KEY_ROTATION))
                    : 0;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION)
                    : 0L;

            int colorStandard = format.containsKey(MediaFormat.KEY_COLOR_STANDARD)
                    ? format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                    : MediaFormat.COLOR_STANDARD_BT709;
            int colorRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE)
                    ? format.getInteger(MediaFormat.KEY_COLOR_RANGE)
                    : MediaFormat.COLOR_RANGE_LIMITED;

            int displayWidth = (rotation == 90 || rotation == 270) ? sourceHeight : sourceWidth;
            int displayHeight = (rotation == 90 || rotation == 270) ? sourceWidth : sourceHeight;
            int outWidth = Math.max(1, Math.min(targetWidth, displayWidth));
            int outHeight = Math.max(1, Math.round(displayHeight * (outWidth / (float) displayWidth)));

            final ArrayBlockingQueue<Image> images = new ArrayBlockingQueue<>(3);
            imageReader = ImageReader.newInstance(
                    sourceWidth,
                    sourceHeight,
                    ImageFormat.YUV_420_888,
                    3);

            imageThread = new HandlerThread("gif-frame-reader");
            imageThread.start();
            Handler imageHandler = new Handler(imageThread.getLooper());

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireNextImage();
                    if (image != null && !images.offer(image)) {
                        image.close();
                    }
                } catch (Exception e) {
                    if (image != null) {
                        image.close();
                    }
                }
            }, imageHandler);

            extractor.selectTrack(videoTrack);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, imageReader.getSurface(), null, 0);
            decoder.start();

            GifEncoder encoder = new GifEncoder(
                    output,
                    outWidth,
                    outHeight,
                    Math.max(1, Math.round(100f / fps)));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long captureIntervalUs = Math.max(1L, 1_000_000L / fps);
            long nextCaptureUs = 0L;
            long lastActivityNs = System.nanoTime();
            int encodedFrames = 0;

            while (!outputDone) {
                if (System.nanoTime() - lastActivityNs > TimeUnit.SECONDS.toNanos(15)) {
                    throw new IllegalStateException("Video decoder stopped responding.");
                }

                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IllegalStateException("Could not access the decoder input buffer.");
                        }

                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0);
                            extractor.advance();
                        }
                        lastActivityNs = System.nanoTime();
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                        colorStandard = outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                        colorRange = outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE);
                    }
                    lastActivityNs = System.nanoTime();
                    continue;
                }
                if (outputIndex < 0) {
                    continue;
                }

                boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean hasFrame = info.size > 0 && !codecConfig;
                boolean capture = hasFrame && info.presentationTimeUs >= nextCaptureUs;

                decoder.releaseOutputBuffer(outputIndex, capture);
                lastActivityNs = System.nanoTime();

                if (capture) {
                    Image image = images.poll(4, TimeUnit.SECONDS);
                    if (image == null) {
                        throw new IllegalStateException("Decoded frame was not delivered by Android.");
                    }

                    try {
                        Bitmap bitmap = yuvImageToBitmap(
                                image,
                                rotation,
                                outWidth,
                                outHeight,
                                colorStandard,
                                colorRange);
                        encoder.addFrame(bitmap);
                        bitmap.recycle();
                        encodedFrames++;
                    } finally {
                        image.close();
                    }

                    do {
                        nextCaptureUs += captureIntervalUs;
                    } while (nextCaptureUs <= info.presentationTimeUs);

                    if (durationUs > 0) {
                        int percent = Math.max(0, Math.min(
                                100,
                                Math.round((info.presentationTimeUs * 100f) / durationUs)));
                        progressListener.onProgress(percent);
                    }
                }

                if (endOfStream) {
                    outputDone = true;
                }
            }

            if (encodedFrames == 0) {
                throw new IllegalStateException("Android decoded no usable video frames.");
            }

            encoder.finish();
            progressListener.onProgress(100);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (imageReader != null) {
                try {
                    imageReader.close();
                } catch (Exception ignored) {
                }
            }
            if (imageThread != null) {
                imageThread.quitSafely();
            }
        }
    }

    private static Bitmap yuvImageToBitmap(
            Image image,
            int rotation,
            int outWidth,
            int outHeight,
            int colorStandard,
            int colorRange) {

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            throw new IllegalArgumentException("Unsupported decoded YUV layout.");
        }

        Rect crop = image.getCropRect();
        int sourceWidth = crop.width();
        int sourceHeight = crop.height();
        int displayWidth = (rotation == 90 || rotation == 270) ? sourceHeight : sourceWidth;
        int displayHeight = (rotation == 90 || rotation == 270) ? sourceWidth : sourceHeight;

        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int yBase = yBuffer.position();
        int uBase = uBuffer.position();
        int vBase = vBuffer.position();

        float crToR;
        float cbToG;
        float crToG;
        float cbToB;

        if (colorStandard == MediaFormat.COLOR_STANDARD_BT601_NTSC
                || colorStandard == MediaFormat.COLOR_STANDARD_BT601_PAL) {
            crToR = 1.402f;
            cbToG = 0.344136f;
            crToG = 0.714136f;
            cbToB = 1.772f;
        } else if (colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
            crToR = 1.4746f;
            cbToG = 0.164553f;
            crToG = 0.571353f;
            cbToB = 1.8814f;
        } else {
            crToR = 1.5748f;
            cbToG = 0.187324f;
            crToG = 0.468124f;
            cbToB = 1.8556f;
        }

        boolean fullRange = colorRange == MediaFormat.COLOR_RANGE_FULL;
        int[] pixels = new int[outWidth * outHeight];
        int p = 0;

        for (int oy = 0; oy < outHeight; oy++) {
            int dy = Math.min(
                    displayHeight - 1,
                    (int) ((oy * (long) displayHeight) / outHeight));

            for (int ox = 0; ox < outWidth; ox++) {
                int dx = Math.min(
                        displayWidth - 1,
                        (int) ((ox * (long) displayWidth) / outWidth));

                int sx;
                int sy;

                switch (rotation) {
                    case 90:
                        sx = dy;
                        sy = sourceHeight - 1 - dx;
                        break;
                    case 180:
                        sx = sourceWidth - 1 - dx;
                        sy = sourceHeight - 1 - dy;
                        break;
                    case 270:
                        sx = sourceWidth - 1 - dy;
                        sy = dx;
                        break;
                    default:
                        sx = dx;
                        sy = dy;
                        break;
                }

                sx += crop.left;
                sy += crop.top;

                int yIndex = yBase
                        + sy * yPlane.getRowStride()
                        + sx * yPlane.getPixelStride();

                int chromaX = sx / 2;
                int chromaY = sy / 2;

                int uIndex = uBase
                        + chromaY * uPlane.getRowStride()
                        + chromaX * uPlane.getPixelStride();

                int vIndex = vBase
                        + chromaY * vPlane.getRowStride()
                        + chromaX * vPlane.getPixelStride();

                float y = yBuffer.get(yIndex) & 0xFF;
                float cb = (uBuffer.get(uIndex) & 0xFF) - 128f;
                float cr = (vBuffer.get(vIndex) & 0xFF) - 128f;

                if (!fullRange) {
                    y = (y - 16f) * (255f / 219f);
                    cb *= (255f / 224f);
                    cr *= (255f / 224f);
                }

                int r = clamp(Math.round(y + crToR * cr));
                int g = clamp(Math.round(y - cbToG * cb - crToG * cr));
                int b = clamp(Math.round(y + cbToB * cb));

                pixels[p++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(
                outWidth,
                outHeight,
                Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight);
        return bitmap;
    }

    private static int normalizeRotation(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        if (normalized < 45 || normalized >= 315) {
            return 0;
        }
        if (normalized < 135) {
            return 90;
        }
        if (normalized < 225) {
            return 180;
        }
        return 270;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
