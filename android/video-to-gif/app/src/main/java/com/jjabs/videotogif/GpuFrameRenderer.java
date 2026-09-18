package com.jjabs.videotogif;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class GpuFrameRenderer {
    private static final float[] VERTICES = {
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    private final int rawWidth;
    private final int rawHeight;
    private final int rotation;
    private final int finalWidth;
    private final int finalHeight;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int textureId;
    private int program;
    private int positionLoc;
    private int texCoordLoc;
    private int texMatrixLoc;

    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private final Semaphore frameAvailable = new Semaphore(0);
    private final float[] texMatrix = new float[16];
    private final FloatBuffer vertexBuffer;
    private final ByteBuffer rgbaBuffer;
    private final int[] pixelBuffer;

    GpuFrameRenderer(
            int sourceWidth,
            int sourceHeight,
            int finalWidth,
            int finalHeight,
            int rotation) {

        this.rotation = rotation;
        this.finalWidth = finalWidth;
        this.finalHeight = finalHeight;

        if (rotation == 90 || rotation == 270) {
            this.rawWidth = finalHeight;
            this.rawHeight = finalWidth;
        } else {
            this.rawWidth = finalWidth;
            this.rawHeight = finalHeight;
        }

        vertexBuffer = ByteBuffer
                .allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        rgbaBuffer = ByteBuffer
                .allocateDirect(rawWidth * rawHeight * 4)
                .order(ByteOrder.nativeOrder());
        pixelBuffer = new int[rawWidth * rawHeight];

        initEgl();
        initGl();

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);
        surfaceTexture.setOnFrameAvailableListener(st -> frameAvailable.release());
        surface = new Surface(surfaceTexture);
    }

    Surface getSurface() {
        return surface;
    }

    Bitmap awaitAndReadFrame() throws Exception {
        if (!frameAvailable.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Decoded frame was not delivered by Android.");
        }

        makeCurrent();
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(texMatrix);

        GLES20.glViewport(0, 0, rawWidth, rawHeight);
        GLES20.glUseProgram(program);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(
                positionLoc,
                2,
                GLES20.GL_FLOAT,
                false,
                4 * 4,
                vertexBuffer);

        vertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texCoordLoc);
        GLES20.glVertexAttribPointer(
                texCoordLoc,
                2,
                GLES20.GL_FLOAT,
                false,
                4 * 4,
                vertexBuffer);

        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGl("draw frame");

        rgbaBuffer.clear();

        GLES20.glReadPixels(
                0,
                0,
                rawWidth,
                rawHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgbaBuffer);
        checkGl("read frame");

        int p = 0;

        // glReadPixels is bottom-up relative to Android Bitmap coordinates.
        for (int y = rawHeight - 1; y >= 0; y--) {
            int rowOffset = y * rawWidth * 4;
            for (int x = 0; x < rawWidth; x++) {
                int offset = rowOffset + x * 4;
                int r = rgbaBuffer.get(offset) & 0xFF;
                int g = rgbaBuffer.get(offset + 1) & 0xFF;
                int b = rgbaBuffer.get(offset + 2) & 0xFF;
                int a = rgbaBuffer.get(offset + 3) & 0xFF;
                pixelBuffer[p++] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap raw = Bitmap.createBitmap(
                rawWidth,
                rawHeight,
                Bitmap.Config.ARGB_8888);
        raw.setPixels(pixelBuffer, 0, rawWidth, 0, 0, rawWidth, rawHeight);

        if (rotation == 0) {
            return raw;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(
                raw,
                0,
                0,
                rawWidth,
                rawHeight,
                matrix,
                true);
        raw.recycle();

        if (rotated.getWidth() == finalWidth && rotated.getHeight() == finalHeight) {
            return rotated;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(
                rotated,
                finalWidth,
                finalHeight,
                true);
        rotated.recycle();
        return scaled;
    }

    void release() {
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception ignored) {
            }
            surface = null;
        }

        if (surfaceTexture != null) {
            try {
                surfaceTexture.release();
            } catch (Exception ignored) {
            }
            surfaceTexture = null;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                makeCurrent();
                if (textureId != 0) {
                    int[] textures = {textureId};
                    GLES20.glDeleteTextures(1, textures, 0);
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                }
            } catch (Exception ignored) {
            }

            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("Could not open EGL display.");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("Could not initialize EGL.");
        }

        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(
                eglDisplay,
                configAttrs,
                0,
                configs,
                0,
                1,
                count,
                0) || count[0] == 0) {
            throw new IllegalStateException("Could not choose EGL config.");
        }

        int[] contextAttrs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };

        eglContext = EGL14.eglCreateContext(
                eglDisplay,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttrs,
                0);

        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("Could not create EGL context.");
        }

        int[] surfaceAttrs = {
                EGL14.EGL_WIDTH, rawWidth,
                EGL14.EGL_HEIGHT, rawHeight,
                EGL14.EGL_NONE
        };

        eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay,
                configs[0],
                surfaceAttrs,
                0);

        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("Could not create EGL surface.");
        }

        makeCurrent();
    }

    private void initGl() {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        textureId = texture[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] link = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        if (link[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("Could not link video shader: " + log);
        }

        positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        checkGl("initialize renderer");
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile video shader: " + log);
        }
        return shader;
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext)) {
            throw new IllegalStateException("Could not make EGL context current.");
        }
    }

    private void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(
                    operation + " failed with OpenGL error 0x" + Integer.toHexString(error));
        }
    }
}
