package com.bci.glasses.stimulus

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

class PanoramaSurfaceView(context: Context) : GLSurfaceView(context) {
    private val panoramaRenderer = PanoramaRenderer(context)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(panoramaRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setCameraPose(pose: CameraPose) {
        panoramaRenderer.setCameraPose(pose)
        requestRender()
    }
}

private class PanoramaRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val yawBits = AtomicInteger(0f.toRawBits())
    private val pitchBits = AtomicInteger(0f.toRawBits())
    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(FULLSCREEN_QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_QUAD)
            position(0)
        }

    private var program = 0
    private var texture = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    fun setCameraPose(pose: CameraPose) {
        yawBits.set(Math.toRadians(pose.yawDegrees.toDouble()).toFloat().toRawBits())
        pitchBits.set(Math.toRadians(pose.pitchDegrees.toDouble()).toFloat().toRawBits())
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.015f, 0.025f, 0.03f, 1f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        texture = loadPanoramaTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)

        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uViewport"),
            viewportWidth.toFloat(),
            viewportHeight.toFloat(),
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uYaw"),
            Float.fromBits(yawBits.get()),
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uPitch"),
            Float.fromBits(pitchBits.get()),
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uTanHalfFov"),
            tan(Math.toRadians(FOV_Y_DEGREES / 2.0)).toFloat(),
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPanorama"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun loadPanoramaTexture(): Int {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.hotel_room_panorama_360)
            ?: error("Unable to decode 360 panorama")
        val handles = IntArray(1)
        GLES20.glGenTextures(1, handles, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handles[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return handles[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { handle ->
            GLES20.glAttachShader(handle, vertexShader)
            GLES20.glAttachShader(handle, fragmentShader)
            GLES20.glLinkProgram(handle)
            val status = IntArray(1)
            GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(handle) }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { handle ->
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val status = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(handle) }
    }

    companion object {
        private const val FOV_Y_DEGREES = 48.0
        private val FULLSCREEN_QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vNdc;

            void main() {
                vNdc = aPosition;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vNdc;
            uniform sampler2D uPanorama;
            uniform vec2 uViewport;
            uniform float uYaw;
            uniform float uPitch;
            uniform float uTanHalfFov;

            const float PI = 3.14159265358979323846;

            void main() {
                float aspect = uViewport.x / uViewport.y;
                vec3 ray = normalize(vec3(vNdc.x * aspect * uTanHalfFov, vNdc.y * uTanHalfFov, 1.0));

                float cp = cos(uPitch);
                float sp = sin(uPitch);
                vec3 pitched = vec3(ray.x, cp * ray.y + sp * ray.z, -sp * ray.y + cp * ray.z);

                float cy = cos(uYaw);
                float sy = sin(uYaw);
                vec3 world = vec3(
                    cy * pitched.x + sy * pitched.z,
                    pitched.y,
                    -sy * pitched.x + cy * pitched.z
                );

                float longitude = atan(world.x, world.z);
                float latitude = asin(clamp(world.y, -1.0, 1.0));
                vec2 uv = vec2(
                    fract(0.5 + longitude / (2.0 * PI)),
                    clamp(0.5 - latitude / PI, 0.001, 0.999)
                );
                vec3 color = texture2D(uPanorama, uv).rgb;
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
