package com.jjabs.videotogif;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GifTimeline {
    static final class Frame {
        final int delayCs;
        final int startTimeMs;

        Frame(int delayCs, int startTimeMs) {
            this.delayCs = delayCs;
            this.startTimeMs = startTimeMs;
        }
    }

    final int width;
    final int height;
    final List<Frame> frames;
    final int durationMs;

    private GifTimeline(
            int width,
            int height,
            List<Frame> frames,
            int durationMs) {
        this.width = width;
        this.height = height;
        this.frames = Collections.unmodifiableList(frames);
        this.durationMs = durationMs;
    }

    static GifTimeline parse(InputStream input) throws IOException {
        byte[] header = readBytes(input, 6);
        String signature = new String(header, StandardCharsets.US_ASCII);

        if (!"GIF87a".equals(signature) && !"GIF89a".equals(signature)) {
            throw new IOException("This file is not a valid GIF.");
        }

        int width = readUnsignedShort(input);
        int height = readUnsignedShort(input);

        int packed = readByte(input);
        readByte(input); // background index
        readByte(input); // pixel aspect ratio

        if ((packed & 0x80) != 0) {
            int entries = 1 << ((packed & 0x07) + 1);
            skipFully(input, entries * 3L);
        }

        List<Frame> frames = new ArrayList<>();
        int pendingDelayCs = 10;
        int elapsedMs = 0;

        while (true) {
            int marker = input.read();
            if (marker < 0 || marker == 0x3B) {
                break;
            }

            if (marker == 0x21) {
                int label = readByte(input);

                if (label == 0xF9) {
                    int blockSize = readByte(input);
                    byte[] gce = readBytes(input, blockSize);

                    if (blockSize >= 3) {
                        int delay = (gce[1] & 0xFF) | ((gce[2] & 0xFF) << 8);
                        pendingDelayCs = Math.max(1, delay);
                    } else {
                        pendingDelayCs = 10;
                    }

                    // Graphic Control Extension ends with a zero-length block.
                    int terminator = readByte(input);
                    if (terminator != 0) {
                        skipBlockRemainder(input, terminator);
                    }
                } else {
                    skipSubBlocks(input);
                }

                continue;
            }

            if (marker == 0x2C) {
                byte[] descriptor = readBytes(input, 9);
                int imagePacked = descriptor[8] & 0xFF;

                if ((imagePacked & 0x80) != 0) {
                    int entries = 1 << ((imagePacked & 0x07) + 1);
                    skipFully(input, entries * 3L);
                }

                readByte(input); // LZW minimum code size
                skipSubBlocks(input);

                int delayCs = Math.max(1, pendingDelayCs);
                frames.add(new Frame(delayCs, elapsedMs));
                elapsedMs += delayCs * 10;
                pendingDelayCs = 10;
                continue;
            }

            // Be tolerant of padding bytes some encoders insert.
            if (marker == 0x00) {
                continue;
            }

            throw new IOException(
                    "Unsupported GIF block 0x" + Integer.toHexString(marker));
        }

        if (frames.isEmpty()) {
            throw new IOException("The GIF contains no animation frames.");
        }

        return new GifTimeline(width, height, frames, elapsedMs);
    }

    int frameCount() {
        return frames.size();
    }

    int frameTimeMs(int index) {
        int safe = Math.max(0, Math.min(index, frames.size() - 1));
        return frames.get(safe).startTimeMs;
    }

    int frameDelayCs(int index) {
        int safe = Math.max(0, Math.min(index, frames.size() - 1));
        return frames.get(safe).delayCs;
    }

    int selectionDurationMs(int startFrame, int endFrame) {
        int start = Math.max(0, Math.min(startFrame, frames.size() - 1));
        int end = Math.max(start, Math.min(endFrame, frames.size() - 1));
        int total = 0;

        for (int i = start; i <= end; i++) {
            total += frames.get(i).delayCs * 10;
        }

        return total;
    }

    private static void skipSubBlocks(InputStream input) throws IOException {
        while (true) {
            int size = readByte(input);
            if (size == 0) {
                return;
            }
            skipFully(input, size);
        }
    }

    private static void skipBlockRemainder(InputStream input, int firstSize)
            throws IOException {
        skipFully(input, firstSize);
        skipSubBlocks(input);
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        int low = readByte(input);
        int high = readByte(input);
        return low | (high << 8);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of GIF.");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;

        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of GIF.");
            }
            offset += read;
        }

        return data;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;

        while (remaining > 0) {
            long skipped = input.skip(remaining);

            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }

            if (input.read() < 0) {
                throw new EOFException("Unexpected end of GIF.");
            }

            remaining--;
        }
    }
}
