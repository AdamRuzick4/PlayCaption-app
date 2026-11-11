package cz.titulky.tv

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Telefon: obraz z dekodéru přes grafický čip (OpenGL) → barvy, které jdou přečíst.
 * Telefony (Xiaomi aj.) neumí dekódovat do YUV pro ImageReader ani převést svůj vnitřní
 * formát přes Bitmap – přes SurfaceTexture a glReadPixels to jde na všech zařízeních.
 *
 * [priSnimku] se volá na vlákně GL po každém novém snímku; uvnitř lze zavolat [precti].
 */
class GlSnimac(val sirka: Int, val vyska: Int, private val priSnimku: (GlSnimac) -> Unit) {

    private val vlakno = HandlerThread("gl-snimac").apply { start() }
    private val h = Handler(vlakno.looper)

    lateinit var surface: Surface
        private set

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var st: SurfaceTexture
    private var textura = 0
    private var program = 0
    private val matice = FloatArray(16)
    @Volatile private var konec = false

    private val vrcholy: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v – celý obdélník
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    /** Připraví OpenGL a vrátí plochu (Surface) pro dekodér. */
    fun start(): Surface {
        val hotovo = CountDownLatch(1)
        var chyba: Throwable? = null
        h.post {
            try { pripravGl() } catch (e: Throwable) { chyba = e }
            hotovo.countDown()
        }
        hotovo.await()
        chyba?.let { throw it }
        return surface
    }

    private fun pripravGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val verze = IntArray(2)
        check(EGL14.eglInitialize(display, verze, 0, verze, 1)) { "eglInitialize" }
        val atributy = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val konfigurace = arrayOfNulls<EGLConfig>(1)
        val pocet = IntArray(1)
        check(EGL14.eglChooseConfig(display, atributy, 0, konfigurace, 0, 1, pocet, 0) && pocet[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(display, konfigurace[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, konfigurace[0],
            intArrayOf(EGL14.EGL_WIDTH, sirka, EGL14.EGL_HEIGHT, vyska, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textura = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textura)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = vytvorProgram(
            """
            attribute vec4 aPoz; attribute vec4 aTex; uniform mat4 uMat; varying vec2 vTex;
            void main(){ gl_Position = aPoz; vTex = (uMat * aTex).xy; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
            void main(){ gl_FragColor = texture2D(sTex, vTex); }
            """.trimIndent()
        )

        st = SurfaceTexture(textura)
        st.setDefaultBufferSize(sirka, vyska)
        st.setOnFrameAvailableListener({ noveSnimek() }, h)
        surface = Surface(st)
    }

    private fun noveSnimek() {
        if (konec) return
        runCatching {
            st.updateTexImage()
            st.getTransformMatrix(matice)
            kresli()
            priSnimku(this)
        }
    }

    private fun kresli() {
        GLES20.glViewport(0, 0, sirka, vyska)
        GLES20.glUseProgram(program)
        val aPoz = GLES20.glGetAttribLocation(program, "aPoz")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        vrcholy.position(0)
        GLES20.glVertexAttribPointer(aPoz, 2, GLES20.GL_FLOAT, false, 16, vrcholy)
        GLES20.glEnableVertexAttribArray(aPoz)
        vrcholy.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vrcholy)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMat"), 1, false, matice, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textura)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Přečte obdélník z posledního snímku (souřadnice jako na obrazovce: 0,0 = levý horní roh).
     * Vrací barvy ARGB po řádcích shora dolů. Volat jen uvnitř [priSnimku].
     */
    fun precti(x: Int, y: Int, w: Int, v: Int): IntArray {
        val buf = ByteBuffer.allocateDirect(w * v * 4).order(ByteOrder.nativeOrder())
        // OpenGL počítá řádky zdola
        GLES20.glReadPixels(x, vyska - y - v, w, v, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bajty = ByteArray(w * v * 4)
        buf.get(bajty)
        val vysledek = IntArray(w * v)
        for (radek in 0 until v) {
            val zdroj = (v - 1 - radek) * w * 4   // otočit vzhůru nohama
            val cil = radek * w
            for (i in 0 until w) {
                val o = zdroj + i * 4
                val r = bajty[o].toInt() and 0xFF
                val g = bajty[o + 1].toInt() and 0xFF
                val b = bajty[o + 2].toInt() and 0xFF
                vysledek[cil + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return vysledek
    }

    fun zavri() {
        konec = true
        h.post {
            runCatching { surface.release() }
            runCatching { st.release() }
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            vlakno.quitSafely()
        }
    }

    private fun vytvorProgram(vs: String, fs: String): Int {
        fun shader(typ: Int, kod: String): Int {
            val s = GLES20.glCreateShader(typ)
            GLES20.glShaderSource(s, kod)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
