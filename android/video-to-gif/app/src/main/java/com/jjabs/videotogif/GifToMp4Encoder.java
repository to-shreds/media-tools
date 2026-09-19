package com.jjabs.videotogif;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

final class GifToMp4Encoder {
    interface ProgressListener {
        void onProgress(int percent);
    }

    private GifToMp4Encoder() {
    }

    static void convert(
            Context context,
            Uri inputUri,
            Uri outputUri,
            ProgressListener progressListener) throws Exception {

        GifTimeline timeline;

        try (InputStream input = new BufferedInputStream(
                context.getContentResolver().openInputStream(inputUri))) {
            if (input == null) {
                throw new IllegalStateException("Could not open the GIF.");
            }
            timeline = GifTimeline.parse(input);
        }

        Movie movie;

        try (InputStream input = new BufferedInputStream(
                context.getContentResolver().openInputStream(inputUri))) {
            if (input == null) {
                throw new IllegalStateException("Could not reopen the GIF.");
            }
            movie = Movie.decodeStream(input);
        }

        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            throw new IllegalStateException("Android could not decode this GIF.");
        }

        int[] size = chooseOutputSize(movie.width(), movie.height());
        int width = size[0];
        int height = size[1];

        MediaCodec encoder = null;
        Surface inputSurface = null;
        BitmapSurfaceRenderer renderer = null;
        MediaMuxer muxer = null;
        ParcelFileDescriptor outputFd = null;

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height);

            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    chooseBitrate(width, height));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);

            inputSurface = encoder.createInputSurface();
            encoder.start();

            renderer = new BitmapSurfaceRenderer(
                    inputSurface,
                    width,
                    height);

            outputFd = context.getContentResolver().openFileDescriptor(
                    outputUri,
                    "rw");

            if (outputFd == null) {
                throw new IllegalStateException("Could not open the MP4 output.");
            }

            muxer = new MediaMuxer(
                    outputFd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            EncoderState state = new EncoderState();

            int frameCount = timeline.frameCount();

            for (int i = 0; i < frameCount; i++) {
                int timeMs = timeline.frameTimeMs(i);
                Bitmap frame = renderMovieFrame(
                        movie,
                        timeMs,
                        width,
                        height);

                try {
                    renderer.drawFrame(
                            frame,
                            timeMs * 1_000_000L);
                } finally {
                    frame.recycle();
                }

                drainEncoder(
                        encoder,
                        muxer,
                        state,
                        false);

                int percent = Math.max(
                        0,
                        Math.min(
                                99,
                                Math.round((i + 1) * 99f / frameCount)));

                progressListener.onProgress(percent);
            }

            // Submit the final visual state once more at the animation's real
            // end timestamp. This gives the last GIF frame its intended
            // duration in the variable-frame-rate MP4.
            int lastIndex = frameCount - 1;
            Bitmap finalFrame = renderMovieFrame(
                    movie,
                    timeline.frameTimeMs(lastIndex),
                    width,
                    height);

            try {
                renderer.drawFrame(
                        finalFrame,
                        Math.max(
                                timeline.durationMs,
                                timeline.frameTimeMs(lastIndex) + 1)
                                * 1_000_000L);
            } finally {
                finalFrame.recycle();
            }

            drainEncoder(
                    encoder,
                    muxer,
                    state,
                    false);

            encoder.signalEndOfInputStream();

            drainEncoder(
                    encoder,
                    muxer,
                    state,
                    true);

            progressListener.onProgress(100);

            if (!state.muxerStarted) {
                throw new IllegalStateException(
                        "The phone's H.264 encoder produced no MP4 track.");
            }
        } finally {
            if (renderer != null) {
                renderer.release();
            }

            if (inputSurface != null) {
                try {
                    inputSurface.release();
                } catch (Exception ignored) {
                }
            }

            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }

            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }

                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }

            if (outputFd != null) {
                try {
                    outputFd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            EncoderState state,
            boolean endOfStream) {

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idleCount = 0;

        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(
                    info,
                    endOfStream ? 10_000 : 0);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    return;
                }

                if (++idleCount > 500) {
                    throw new IllegalStateException(
                            "H.264 encoder did not finish.");
                }

                continue;
            }

            idleCount = 0;

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.muxerStarted) {
                    throw new IllegalStateException(
                            "H.264 output format changed twice.");
                }

                MediaFormat outputFormat = encoder.getOutputFormat();
                state.trackIndex = muxer.addTrack(outputFormat);
                muxer.start();
                state.muxerStarted = true;
                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);

            if (encodedData == null) {
                throw new IllegalStateException(
                        "Could not access encoded MP4 data.");
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                info.size = 0;
            }

            if (info.size > 0) {
                if (!state.muxerStarted) {
                    throw new IllegalStateException(
                            "MP4 encoder produced data before its format.");
                }

                encodedData.position(info.offset);
                encodedData.limit(info.offset + info.size);

                muxer.writeSampleData(
                        state.trackIndex,
                        encodedData,
                        info);
            }

            boolean eos =
                    (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

            encoder.releaseOutputBuffer(outputIndex, false);

            if (eos) {
                return;
            }
        }
    }

    private static Bitmap renderMovieFrame(
            Movie movie,
            int timeMs,
            int width,
            int height) {

        Bitmap bitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888);

        // MP4/H.264 has no alpha channel. Transparent GIF pixels become black.
        bitmap.eraseColor(Color.BLACK);

        Canvas canvas = new Canvas(bitmap);
        canvas.scale(
                width / (float) movie.width(),
                height / (float) movie.height());

        int duration = movie.duration();
        int safeTime = timeMs;

        if (duration > 0) {
            safeTime = Math.max(
                    0,
                    Math.min(timeMs, duration - 1));
        }

        movie.setTime(safeTime);
        movie.draw(canvas, 0f, 0f);

        return bitmap;
    }

    private static int[] chooseOutputSize(int sourceWidth, int sourceHeight) {
        int width = sourceWidth;
        int height = sourceHeight;

        int maxDimension = Math.max(width, height);

        if (maxDimension > 1920) {
            float scale = 1920f / maxDimension;
            width = Math.max(2, Math.round(width * scale));
            height = Math.max(2, Math.round(height * scale));
        }

        // AVC encoders are much more reliable with even dimensions.
        if ((width & 1) != 0) {
            width++;
        }

        if ((height & 1) != 0) {
            height++;
        }

        return new int[]{width, height};
    }

    private static int chooseBitrate(int width, int height) {
        long suggested = (long) width * height * 8L;

        return (int) Math.max(
                1_000_000L,
                Math.min(8_000_000L, suggested));
    }

    private static final class EncoderState {
        int trackIndex = -1;
        boolean muxerStarted;
    }
}
