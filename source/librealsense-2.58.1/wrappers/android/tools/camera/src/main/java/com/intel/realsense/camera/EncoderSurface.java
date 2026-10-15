package com.intel.realsense.camera;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** A private GL context: camera RGB pixels go directly to the AVC encoder surface. */
final class EncoderSurface implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private final int width;
    private final int height;
    private final FloatBuffer vertices = floats(-1, -1, 1, -1, -1, 1, 1, 1);
    // RealSense rows start at the top; GL texture coordinates start at the bottom.
    private final FloatBuffer coordinates = floats(0, 1, 1, 1, 0, 0, 1, 0);
    private ByteBuffer pixels;
    private byte[] source;

    EncoderSurface(Surface surface, int width, int height) {
        this.width = width;
        this.height = height;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (!EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) fail("eglInitialize");
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    0x3142, 1, EGL14.EGL_NONE}; // EGL_RECORDABLE_ANDROID
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) fail("eglChooseConfig");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext");
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) fail("eglCreateWindowSurface");
            makeCurrent();
            int vs = shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 p; attribute vec2 uv; varying vec2 v;"
                            + "void main(){ gl_Position=vec4(p,0.0,1.0); v=uv; }");
            int fs = shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; varying vec2 v; uniform sampler2D image;"
                            + "void main(){ gl_FragColor=texture2D(image,v); }");
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    byte[] sourceBuffer(int size) {
        if (source == null || source.length != size) source = new byte[size];
        return source;
    }

    void draw(byte[] data, int stride, int channels, long presentationNs) {
        makeCurrent();
        int rowBytes = width * channels;
        if (stride < rowBytes || data.length < stride * (height - 1) + rowBytes)
            throw new IllegalArgumentException("Invalid RGB frame stride");
        int size = rowBytes * height;
        if (pixels == null || pixels.capacity() != size) pixels = ByteBuffer.allocateDirect(size);
        pixels.clear();
        for (int y = 0; y < height; y++) pixels.put(data, y * stride, rowBytes);
        pixels.flip();
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        int format = channels == 4 ? GLES20.GL_RGBA : GLES20.GL_RGB;
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0,
                format, GLES20.GL_UNSIGNED_BYTE, pixels);
        int p = GLES20.glGetAttribLocation(program, "p");
        int uv = GLES20.glGetAttribLocation(program, "uv");
        GLES20.glEnableVertexAttribArray(p);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) fail("RGB draw");
        if (!EGLExt.eglPresentationTimeANDROID(display, window, presentationNs)) fail("presentation time");
        if (!EGL14.eglSwapBuffers(display, window)) fail("eglSwapBuffers");
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, window, window, context)) fail("eglMakeCurrent");
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    private static void fail(String operation) {
        throw new IllegalStateException(operation + " failed (EGL " + EGL14.eglGetError() + ")");
    }

    @Override public void close() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        if (window != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT
                && EGL14.eglMakeCurrent(display, window, window, context)) {
            if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        display = EGL14.EGL_NO_DISPLAY;
        window = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
    }
}
