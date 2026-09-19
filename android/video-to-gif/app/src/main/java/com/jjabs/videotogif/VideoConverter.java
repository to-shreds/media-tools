package com.jjabs.videotogif;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.OutputStream;
import java.nio.ByteBuffer;
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
        GpuFrameRenderer renderer = null;
        Bitmap pendingBitmap = null;

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

            int displayWidth = (rotation == 90 || rotation == 270)
                    ? sourceHeight
                    : sourceWidth;
            int displayHeight = (rotation == 90 || rotation == 270)
                    ? sourceWidth
                    : sourceHeight;

            int outWidth = Math.max(1, Math.min(targetWidth, displayWidth));
            int outHeight = Math.max(
                    1,
                    Math.round(displayHeight * (outWidth / (float) displayWidth)));

            renderer = new GpuFrameRenderer(
                    sourceWidth,
                    sourceHeight,
                    outWidth,
                    outHeight,
                    rotation);

            extractor.selectTrack(videoTrack);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, renderer.getSurface(), null, 0);
            decoder.start();

            GifEncoder encoder = new GifEncoder(
                    output,
                    outWidth,
                    outHeight,
                    Math.max(1, Math.round(100f / fps)));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            long captureIntervalUs = Math.max(1L, Math.round(1_000_000d / fps));
            long nextCaptureUs = 0L;
            long lastActivityNs = System.nanoTime();

            long firstCapturedPtsUs = -1L;
            long pendingPtsUs = -1L;
            int writtenTimelineCs = 0;
            int capturedFrames = 0;
            int encodedFrames = 0;

            while (!outputDone) {
                if (System.nanoTime() - lastActivityNs > TimeUnit.SECONDS.toNanos(20)) {
                    throw new IllegalStateException("Video decoder stopped responding.");
                }

                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000);

                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IllegalStateException(
                                    "Could not access the decoder input buffer.");
                        }

                        inputBuffer.clear();
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
                    lastActivityNs = System.nanoTime();
                    continue;
                }

                if (outputIndex < 0) {
                    continue;
                }

                boolean endOfStream =
                        (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean codecConfig =
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                boolean capture =
                        !codecConfig
                                && info.presentationTimeUs >= nextCaptureUs
                                && (!endOfStream
                                || info.presentationTimeUs > 0
                                || capturedFrames == 0);

                decoder.releaseOutputBuffer(outputIndex, capture);
                lastActivityNs = System.nanoTime();

                if (capture) {
                    Bitmap currentBitmap = renderer.awaitAndReadFrame();
                    long currentPtsUs = Math.max(0L, info.presentationTimeUs);

                    if (firstCapturedPtsUs < 0) {
                        firstCapturedPtsUs = currentPtsUs;
                    }

                    if (pendingBitmap != null) {
                        int targetEndCs = (int) Math.round(
                                (currentPtsUs - firstCapturedPtsUs) / 10_000d);
                        int delayCs = Math.max(1, targetEndCs - writtenTimelineCs);

                        encoder.addFrame(pendingBitmap, delayCs);
                        writtenTimelineCs += delayCs;
                        encodedFrames++;

                        pendingBitmap.recycle();
                        pendingBitmap = null;
                    }

                    pendingBitmap = currentBitmap;
                    pendingPtsUs = currentPtsUs;
                    capturedFrames++;

                    do {
                        nextCaptureUs += captureIntervalUs;
                    } while (nextCaptureUs <= info.presentationTimeUs);

                    if (durationUs > 0) {
                        int percent = Math.max(
                                0,
                                Math.min(
                                        100,
                                        Math.round(
                                                (info.presentationTimeUs * 100f)
                                                        / durationUs)));
                        progressListener.onProgress(percent);
                    }
                }

                if (endOfStream) {
                    outputDone = true;
                }
            }

            if (pendingBitmap != null) {
                long nominalEndUs = pendingPtsUs + captureIntervalUs;
                long finalEndUs = durationUs > pendingPtsUs
                        ? durationUs
                        : nominalEndUs;

                int targetEndCs = firstCapturedPtsUs >= 0
                        ? (int) Math.round(
                                (finalEndUs - firstCapturedPtsUs) / 10_000d)
                        : writtenTimelineCs + 1;

                int delayCs = Math.max(1, targetEndCs - writtenTimelineCs);

                encoder.addFrame(pendingBitmap, delayCs);
                encodedFrames++;

                pendingBitmap.recycle();
                pendingBitmap = null;
            }

            if (encodedFrames == 0) {
                throw new IllegalStateException(
                        "Android decoded no usable video frames.");
            }

            encoder.finish();
            progressListener.onProgress(100);
        } finally {
            if (pendingBitmap != null && !pendingBitmap.isRecycled()) {
                pendingBitmap.recycle();
            }

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

            if (renderer != null) {
                renderer.release();
            }
        }
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
}
