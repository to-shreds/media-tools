package com.jjabs.videotogif;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class BitmapSurfaceRenderer {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final float[] VERTICES = {
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    private final int width;
    private final int height;
    private final FloatBuffer vertexBuffer;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int textureId;
    private int positionLoc;
    private int texCoordLoc;

    BitmapSurfaceRenderer(Surface surface, int width, int height) {
        this.width = width;
        this.height = height;

        vertexBuffer = ByteBuffer
                .allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        initEgl(surface);
        initGl();
    }

    void drawFrame(Bitmap bitmap, long presentationTimeNs) {
        makeCurrent();

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                bitmap,
                0);

        checkGl("upload GIF frame");

        EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                presentationTimeNs);

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw new IllegalStateException("Could not submit frame to video encoder.");
        }
    }

    void release() {
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

    private void initEgl(Surface surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("Could not open EGL display.");
        }

        int[] version = new int[2];

        if (!EGL14.eglInitialize(
                eglDisplay,
                version,
                0,
                version,
                1)) {
            throw new IllegalStateException("Could not initialize EGL.");
        }

        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
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
                0)
                || count[0] == 0) {
            throw new IllegalStateException("Could not choose recordable EGL config.");
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

        int[] surfaceAttrs = {EGL14.EGL_NONE};

        eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                configs[0],
                surface,
                surfaceAttrs,
                0);

        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("Could not create encoder EGL surface.");
        }

        makeCurrent();
    }

    private void initGl() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
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
            throw new IllegalStateException("Could not link MP4 shader: " + log);
        }

        positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");

        checkGl("initialize MP4 renderer");
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
            throw new IllegalStateException("Could not compile MP4 shader: " + log);
        }

        return shader;
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext)) {
            throw new IllegalStateException("Could not make encoder EGL context current.");
        }
    }

    private void checkGl(String operation) {
        int error = GLES20.glGetError();

        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(
                    operation
                            + " failed with OpenGL error 0x"
                            + Integer.toHexString(error));
        }
    }
}
