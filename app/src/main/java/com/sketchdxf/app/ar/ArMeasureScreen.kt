package com.sketchdxf.app.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Tap real-world points through the live camera (ARCore's motion tracking + hit-testing, not
 * just a static photo) — each tap after the first shows the distance from the previous one, and
 * "Add to drawing" hands the tapped sequence (as a flat horizontal x/z plan, dropping height) to
 * SketchEditorScreen as a connected line chain, via [PendingArTrace].
 */
@Composable
fun ArMeasureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) { if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var arError by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<Session?>(null) }
    val points = remember { mutableStateOf<List<AnchoredPoint>>(emptyList()) }
    var lastDistanceM by remember { mutableStateOf<Float?>(null) }
    var tracking by remember { mutableStateOf(false) }
    val pendingTap = remember { AtomicReference<FloatArray?>(null) }

    // Session lifecycle: created once permission is granted, resumed/paused with the host
    // Activity's own lifecycle (ARCore requires this — a Session left resumed while the app is
    // backgrounded holds the camera open and eventually throws).
    DisposableEffect(hasCameraPermission, lifecycleOwner) {
        if (!hasCameraPermission) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            val s = session
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (s == null) {
                        val act = activity
                        if (act == null) {
                            arError = "Couldn't start AR: no host activity"
                            return@LifecycleEventObserver
                        }
                        try {
                            when (ArCoreApk.getInstance().requestInstall(act, true)) {
                                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return@LifecycleEventObserver
                                else -> {}
                            }
                            val newSession = Session(context)
                            newSession.configure(
                                Config(newSession).apply {
                                    focusMode = Config.FocusMode.AUTO
                                    depthMode = if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                                }
                            )
                            newSession.resume()
                            session = newSession
                            arError = null
                        } catch (e: UnavailableException) {
                            arError = "ARCore isn't available on this device: ${e.message}"
                        } catch (e: Exception) {
                            arError = "Couldn't start AR: ${e.message}"
                        }
                    } else {
                        try { s.resume(); arError = null } catch (e: CameraNotAvailableException) {
                            arError = "Camera not available"
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> s?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session?.close()
            session = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        preserveEGLContextOnPause = true
                        setEGLContextClientVersion(2)
                        setRenderer(
                            ArRenderer(
                                sessionProvider = { session },
                                pendingTap = pendingTap,
                                displayRotationProvider = { currentDisplayRotation(ctx) },
                                onFrameResult = { pts, dist, isTracking ->
                                    points.value = pts
                                    lastDistanceM = dist
                                    tracking = isTracking
                                }
                            )
                        )
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                pendingTap.set(floatArrayOf(event.x, event.y))
                            }
                            true
                        }
                    }
                }
            )
        }

        // Everything anchored to the top instead of the bottom — a bottom-aligned panel sits
        // right where gesture-nav/3-button nav bars live on most phones and gets rendered
        // underneath them, making the buttons unreachable.
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }

            Column(
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f)).padding(16.dp)
            ) {
                if (!hasCameraPermission) {
                    Text("Camera permission is needed for AR Measure.", color = Color.White)
                } else if (arError != null) {
                    Text(arError ?: "", color = Color(0xFFFF8A80))
                } else {
                    Text(
                        if (tracking) "${points.value.size} point(s) tapped" else "Move your phone slowly to find surfaces…",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium
                    )
                    lastDistanceM?.let { d ->
                        Text(
                            "Last segment: ${"%.2f".format(d)} m (${"%.0f".format(d * 1000)} mm)",
                            color = Color.White, style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            // Only the renderer's own point list (rebuilt from live hit-tests on the
                            // GL thread) is authoritative — this just queues the same way a tap does,
                            // rather than touching the Compose-side mirror directly, which the very
                            // next frame's onFrameResult would otherwise just overwrite right back.
                            onClick = { pendingTap.set(UNDO_SENTINEL) },
                            enabled = points.value.isNotEmpty()
                        ) { Text("Undo point") }
                        Button(
                            onClick = {
                                val list = points.value
                                if (list.size >= 2) {
                                    val originX = list[0].worldX; val originZ = list[0].worldZ
                                    PendingArTrace.set(list.map { (it.worldX - originX) to (it.worldZ - originZ) })
                                    onBack()
                                }
                            },
                            enabled = points.value.size >= 2
                        ) { Text("Add to drawing") }
                    }
                }
            }
        }
    }
}

/** windowManager.defaultDisplay is deprecated (API 30+ prefers Context.display), but this app's
 *  minSdk (26) needs the older, broadly-compatible path — @Suppress can't attach to an argument
 *  inside a call like a named parameter, only to a whole declaration, hence this being its own
 *  function rather than an inline lambda where it was originally written. */
@Suppress("DEPRECATION")
private fun currentDisplayRotation(ctx: android.content.Context): Int =
    (ctx as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0

/** One tapped, anchored real-world point. worldX/Y/Z read the anchor's pose live rather than
 *  caching it from the moment of the tap — ARCore keeps refining an anchor's estimated position
 *  as it tracks more of the scene (loop closure, better plane fits, etc.), and a cached snapshot
 *  never picks up those corrections, which is what made points visibly drift away from their real
 *  on-screen spot over time instead of staying locked to the camera image. */
internal class AnchoredPoint(val anchor: Anchor) {
    val worldX: Float get() = anchor.pose.tx()
    val worldY: Float get() = anchor.pose.ty()
    val worldZ: Float get() = anchor.pose.tz()
}

/** Sentinel the [ArRenderer]'s pending-tap queue recognizes as "remove the last point" instead of
 *  a screen coordinate to hit-test — identified by reference (===), not its (unused) contents. */
private val UNDO_SENTINEL = floatArrayOf(Float.NaN, Float.NaN)

/**
 * GLSurfaceView.Renderer driving the AR session: draws the live camera feed as a full-screen
 * background (the standard ARCore integration pattern — an OES external texture sampled by a
 * simple shader, UV-mapped through Frame.transformCoordinates2d so it stays correct across device
 * rotation), overlays tapped points/connecting lines, and performs a hit-test + anchor creation
 * for whatever screen tap (if any) is pending when each frame is drawn — hit-testing needs a live
 * Frame, which only exists on this GL thread during onDrawFrame, so taps queue through
 * [pendingTap] rather than being handled immediately on the UI thread.
 */
internal class ArRenderer(
    private val sessionProvider: () -> Session?,
    private val pendingTap: AtomicReference<FloatArray?>,
    private val displayRotationProvider: () -> Int,
    private val onFrameResult: (points: List<AnchoredPoint>, lastDistanceM: Float?, tracking: Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private var cameraTextureId = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var bgProgram = 0
    private var bgPositionAttrib = 0
    private var bgTexCoordAttrib = 0
    private var bgTexUniform = 0
    private val quadCoords = directFloatBuffer(floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f))
    private val quadTexCoords = directFloatBuffer(FloatArray(8))

    private var lineProgram = 0
    private var linePositionAttrib = 0
    private var lineColorUniform = 0
    private var lineMvpUniform = 0

    private val points = mutableListOf<AnchoredPoint>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        bgProgram = buildProgram(BG_VERTEX_SHADER, BG_FRAGMENT_SHADER)
        bgPositionAttrib = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        bgTexCoordAttrib = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        bgTexUniform = GLES20.glGetUniformLocation(bgProgram, "u_Texture")

        lineProgram = buildProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        linePositionAttrib = GLES20.glGetAttribLocation(lineProgram, "a_Position")
        lineColorUniform = GLES20.glGetUniformLocation(lineProgram, "u_Color")
        lineMvpUniform = GLES20.glGetUniformLocation(lineProgram, "u_Mvp")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width; viewportHeight = height
        sessionProvider()?.setDisplayGeometry(displayRotationProvider(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = sessionProvider() ?: return

        // Cheap enough to call every frame, and simpler/more robust than a listener-based
        // rotation-change helper — keeps the camera background's UV mapping correct even if the
        // device rotates while this screen is open.
        session.setDisplayGeometry(displayRotationProvider(), viewportWidth, viewportHeight)
        session.setCameraTextureName(cameraTextureId)
        // "Add to drawing"/Back can pop this screen (pausing the session) a frame or two before
        // the GLSurfaceView's own render thread stops calling onDrawFrame — session.update() then
        // throws SessionPausedException. Not a real error, just a lifecycle race; skip the frame.
        val frame: Frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            return
        } catch (e: SessionPausedException) {
            return
        }

        drawCameraBackground(frame)

        // Undo is handled regardless of current tracking state (removing a point never needs a
        // live hit-test), a plain tap only while actually tracking (hitTest needs it).
        val tap = pendingTap.getAndSet(null)
        if (tap === UNDO_SENTINEL) {
            if (points.isNotEmpty()) points.removeAt(points.lastIndex).anchor.detach()
        }

        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            if (tap != null && tap !== UNDO_SENTINEL) {
                val hits = frame.hitTest(tap[0], tap[1])
                // hitTest() mixes detected-plane hits, depth-sensor hits, and raw feature-point
                // hits in one list. A feature point is just a noisy triangulated speck — picking
                // one of those first is exactly what produces wild outliers (e.g. a real 10cm tap
                // resolving to a feature point 2+ metres away). Prefer the much more reliable
                // Plane/DepthPoint hits and only fall back to a feature point if neither exists.
                val hit = hits.firstOrNull { it.trackable is com.google.ar.core.Plane }
                    ?: hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
                    ?: hits.firstOrNull()
                if (hit != null) {
                    points.add(AnchoredPoint(hit.createAnchor()))
                }
            }

            val viewMatrix = FloatArray(16); val projMatrix = FloatArray(16)
            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
            val vpMatrix = FloatArray(16)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            drawPointsAndLines(vpMatrix)
        }

        val lastDist = if (points.size >= 2) {
            val a = points[points.size - 2]; val b = points[points.size - 1]
            dist3(a, b)
        } else null
        onFrameResult(points.toList(), lastDist, tracking)
    }

    private fun drawCameraBackground(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
            Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords
        )
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(bgTexUniform, 0)
        quadCoords.position(0)
        GLES20.glVertexAttribPointer(bgPositionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(bgTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(bgPositionAttrib)
        GLES20.glEnableVertexAttribArray(bgTexCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPositionAttrib)
        GLES20.glDisableVertexAttribArray(bgTexCoordAttrib)
        GLES20.glDepthMask(true)
    }

    private fun drawPointsAndLines(vpMatrix: FloatArray) {
        if (points.isEmpty()) return
        val flat = FloatArray(points.size * 3)
        points.forEachIndexed { i, p -> flat[i * 3] = p.worldX; flat[i * 3 + 1] = p.worldY; flat[i * 3 + 2] = p.worldZ }
        val buf = directFloatBuffer(flat)

        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(lineMvpUniform, 1, false, vpMatrix, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(linePositionAttrib, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(linePositionAttrib)

        GLES20.glUniform4f(lineColorUniform, 1f, 0.84f, 0f, 1f) // amber points
        GLES20.glLineWidth(8f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)

        if (points.size >= 2) {
            GLES20.glUniform4f(lineColorUniform, 0.08f, 0.4f, 0.9f, 1f) // blue connecting lines
            GLES20.glLineWidth(6f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, points.size)
        }
        GLES20.glDisableVertexAttribArray(linePositionAttrib)
    }
}

private fun dist3(a: AnchoredPoint, b: AnchoredPoint): Float {
    val dx = a.worldX - b.worldX; val dy = a.worldY - b.worldY; val dz = a.worldZ - b.worldZ
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun directFloatBuffer(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(values); position(0)
    }

private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    return program
}

private fun compileShader(type: Int, src: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, src)
    GLES20.glCompileShader(shader)
    return shader
}

private const val BG_VERTEX_SHADER = """
    attribute vec4 a_Position;
    attribute vec2 a_TexCoord;
    varying vec2 v_TexCoord;
    void main() {
        gl_Position = a_Position;
        v_TexCoord = a_TexCoord;
    }
"""

private const val BG_FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 v_TexCoord;
    uniform samplerExternalOES u_Texture;
    void main() {
        gl_FragColor = texture2D(u_Texture, v_TexCoord);
    }
"""

private const val LINE_VERTEX_SHADER = """
    uniform mat4 u_Mvp;
    attribute vec4 a_Position;
    void main() {
        gl_Position = u_Mvp * a_Position;
        gl_PointSize = 18.0;
    }
"""

private const val LINE_FRAGMENT_SHADER = """
    precision mediump float;
    uniform vec4 u_Color;
    void main() {
        gl_FragColor = u_Color;
    }
"""
