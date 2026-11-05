package com.example.camaray2s

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.min
import kotlin.math.pow

/**
 * Cámara Y2S: app de cámara con Camera2 sin dependencias externas.
 * Foto JPEG + RAW (DNG), video hasta 8K/4K/1080p a 24/30/60 fps,
 * controles manuales y selección de todas las lentes que el sistema exponga.
 */
class MainActivity : Activity() {

    private data class Lens(val id: String, val phys: String?, val label: String)

    // ---- Cámara ----
    private lateinit var cm: CameraManager
    private val lenses = mutableListOf<Lens>()
    private var lensIdx = 0
    private lateinit var capChars: CameraCharacteristics
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1920, 1080)
    private var gen = 0

    private var bgThread: HandlerThread? = null
    private lateinit var bg: Handler
    private val exec = Executor { r -> bg.post(r) }

    // ---- Estado ----
    private var videoMode = false
    private var rawOn = false
    private var rawSupported = false
    private var manual = false
    private var recording = false
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null

    private var videoSizes = listOf<Size>()
    private var videoSize = Size(1920, 1080)
    private var fpsOptions = listOf(30)
    private var fpsRanges = listOf<Range<Int>>()
    private var videoFps = 30

    private var isoRange = Range(100, 800)
    private var expRange = Range(1_000_000L, 100_000_000L)
    private var minFocus = 0f
    private var isoP = 30
    private var expP = 50
    private var focP = 0

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private val rawLock = Any()
    private var pendingRaw: Image? = null
    private var pendingResult: CaptureResult? = null

    // ---- UI ----
    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var btnLens: Button
    private lateinit var btnMode: Button
    private lateinit var btnRaw: Button
    private lateinit var btnManual: Button
    private lateinit var btnShutter: Button
    private lateinit var resSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var manualGroup: LinearLayout
    private lateinit var tvVals: TextView
    private lateinit var tvInfo: TextView

    // =====================================================================
    // Ciclo de vida
    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager
        try { buildLenses() } catch (e: Exception) { toast("Error listando cámaras: ${e.message}") }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        if (ensurePerms() && texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        super.onPause()
    }

    private fun ensurePerms(): Boolean {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && texture.isAvailable) {
            openCamera()
        }
    }

    // =====================================================================
    // Lentes
    // =====================================================================
    private fun buildLenses() {
        val ids = cm.cameraIdList.toSet()
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            lenses += Lens(id, null, describe(id, c, null))
            for (p in c.physicalCameraIds) {
                if (p in ids) continue
                val pc = try { cm.getCameraCharacteristics(p) } catch (e: Exception) { c }
                lenses += Lens(id, p, describe(id, pc, p))
            }
        }
    }

    private fun describe(id: String, c: CameraCharacteristics, phys: String?): String {
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        return (if (front) "Frontal" else "Trasera") + " ID $id" +
            (if (phys != null) "/$phys" else "") +
            (if (f != null) " (%.1f mm)".format(f) else "")
    }

    // =====================================================================
    // Abrir / cerrar
    // =====================================================================
    private fun openCamera() {
        if (device != null || opening || lenses.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val lens = lenses[lensIdx]
        capChars = (lens.phys?.let { try { cm.getCameraCharacteristics(it) } catch (e: Exception) { null } })
            ?: cm.getCameraCharacteristics(lens.id)
        refreshOptions()
        opening = true
        try {
            cm.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opening = false; device = c; startPreview() }
                override fun onDisconnected(c: CameraDevice) { opening = false; c.close(); device = null }
                override fun onError(c: CameraDevice, e: Int) {
                    opening = false; c.close(); device = null; toast("Error de cámara: $e")
                }
            }, bg)
        } catch (e: Exception) {
            opening = false
            toast("No se pudo abrir: ${e.message}")
        }
    }

    private fun closeCamera() {
        gen++
        if (recording) {
            try { recorder?.stop() } catch (e: Exception) { }
            finishRecorder()
        }
        try { session?.close() } catch (e: Exception) { }
        session = null
        device?.close()
        device = null
        opening = false
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        synchronized(rawLock) { pendingRaw?.close(); pendingRaw = null; pendingResult = null }
    }

    // =====================================================================
    // Opciones según la lente
    // =====================================================================
    private fun refreshOptions() {
        val caps = capChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val map = capChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        rawSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
        if (!rawSupported) rawOn = false

        val avail = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        videoSizes = listOf(Size(7680, 4320), Size(3840, 2160), Size(1920, 1080), Size(1280, 720))
            .filter { it in avail }
        if (videoSizes.isEmpty()) videoSizes = listOf(Size(1920, 1080))
        videoSize = videoSizes.firstOrNull { it.width == 1920 } ?: videoSizes[0]

        fpsRanges = capChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        fpsOptions = listOf(24, 30, 60).filter { f -> fpsRanges.any { it.upper == f } }.ifEmpty { listOf(30) }
        videoFps = if (30 in fpsOptions) 30 else fpsOptions[0]

        isoRange = capChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        expRange = capChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
        minFocus = capChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        resSpinner.adapter = adapter(videoSizes.map { "${it.width}x${it.height}" })
        resSpinner.setSelection(videoSizes.indexOf(videoSize).coerceAtLeast(0))
        fpsSpinner.adapter = adapter(fpsOptions.map { "$it fps" })
        fpsSpinner.setSelection(fpsOptions.indexOf(videoFps).coerceAtLeast(0))

        btnLens.text = lenses[lensIdx].label
        tvInfo.text = "FPS que expone el sistema:\n" +
            fpsRanges.joinToString("  ") { "[${it.lower}-${it.upper}]" } +
            "\nRAW: " + (if (rawSupported) "sí" else "no")
        updateUiState()
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // =====================================================================
    // Vista previa
    // =====================================================================
    private fun cfg(s: Surface): OutputConfiguration {
        val c = OutputConfiguration(s)
        lenses[lensIdx].phys?.let { c.setPhysicalCameraId(it) }
        return c
    }

    private fun pickPreview(map: StreamConfigurationMap, target: Size): Size {
        val ratio = target.width.toFloat() / target.height
        val all = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width * it.height <= 1920 * 1080 }
        return all.filter { Math.abs(it.width.toFloat() / it.height - ratio) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: all.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun fitPreview() {
        val cw = root.width
        val ch = root.height
        if (cw == 0 || ch == 0) { root.post { fitPreview() }; return }
        val ratio = previewSize.width.toFloat() / previewSize.height
        var w = cw
        var h = (cw / ratio).toInt()
        if (h > ch) { h = ch; w = (ch * ratio).toInt() }
        texture.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun startPreview() {
        val d = device ?: return
        val my = ++gen
        try {
            session?.close(); session = null
            jpegReader?.close(); jpegReader = null
            rawReader?.close(); rawReader = null
            synchronized(rawLock) { pendingRaw?.close(); pendingRaw = null; pendingResult = null }

            val map = capChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val photoSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width * it.height }!!
            previewSize = pickPreview(map, if (videoMode) videoSize else photoSize)
            val st = texture.surfaceTexture ?: return
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            runOnUiThread { fitPreview() }
            val ps = Surface(st)
            previewSurface = ps

            val outs = mutableListOf(cfg(ps))
            if (!videoMode) {
                jpegReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2).also { r ->
                    r.setOnImageAvailableListener({ saveJpeg(it) }, bg)
                    outs += cfg(r.surface)
                }
                if (rawOn && rawSupported) {
                    val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width * it.height }!!
                    rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2).also { r ->
                        r.setOnImageAvailableListener({ x ->
                            synchronized(rawLock) { pendingRaw?.close(); pendingRaw = x.acquireNextImage() }
                            tryDng()
                        }, bg)
                        outs += cfg(r.surface)
                    }
                }
            }
            d.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (my != gen) { s.close(); return }
                        session = s
                        repeatNow()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        toast("No se pudo configurar esta combinación (lente/resolución)")
                    }
                }))
        } catch (e: Exception) {
            toast("Error en vista previa: ${e.message}")
        }
    }

    // =====================================================================
    // Controles de captura
    // =====================================================================
    private fun fpsRange(fps: Int): Range<Int>? =
        fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }

    private fun isoValue(): Int {
        val lo = isoRange.lower.toDouble()
        val hi = isoRange.upper.toDouble()
        return (lo * (hi / lo).pow(isoP / 100.0)).toInt().coerceIn(isoRange.lower, isoRange.upper)
    }

    private fun expValue(video: Boolean): Long {
        val lo = expRange.lower.toDouble()
        val hi = min(expRange.upper, 1_000_000_000L).toDouble()
        var e = (lo * (hi / lo).pow(expP / 100.0)).toLong()
        if (video) e = min(e, 1_000_000_000L / videoFps)
        return e.coerceIn(expRange.lower, expRange.upper)
    }

    private fun focusValue(): Float = minFocus * focP / 100f

    private fun applyControls(b: CaptureRequest.Builder, video: Boolean) {
        if (video) fpsRange(videoFps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue())
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValue(video))
            if (video) b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / videoFps)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusValue())
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (video || videoMode) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    private fun repeatNow() {
        val s = session ?: return
        val d = device ?: return
        try {
            val targets = mutableListOf<Surface>()
            previewSurface?.let { targets += it }
            if (recording) recorder?.surface?.let { targets += it }
            val b = d.createCaptureRequest(
                if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            )
            targets.forEach { b.addTarget(it) }
            applyControls(b, videoMode)
            s.setRepeatingRequest(b.build(), null, bg)
        } catch (e: Exception) { }
    }

    // =====================================================================
    // Foto
    // =====================================================================
    private fun orient(): Int {
        val s = capChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = capChars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        // La app está bloqueada en horizontal (rotación de pantalla = 90°)
        return if (front) (s + 90) % 360 else (s - 90 + 360) % 360
    }

    private fun takePhoto() {
        val s = session ?: return
        val d = device ?: return
        val jr = jpegReader ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            rawReader?.let { b.addTarget(it.surface) }
            applyControls(b, false)
            b.set(CaptureRequest.JPEG_ORIENTATION, orient())
            val wantRaw = rawReader != null
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    if (wantRaw) {
                        synchronized(rawLock) { pendingResult = result }
                        tryDng()
                    }
                }
            }, bg)
        } catch (e: Exception) { toast("Error al tomar foto: ${e.message}") }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun newMedia(collection: Uri, name: String, mime: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CamaraY2S")
        }
        return contentResolver.insert(collection, v)
    }

    private fun saveJpeg(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val uri = newMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMG_${stamp()}.jpg", "image/jpeg")
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                toast("Foto guardada")
            }
        } finally { img.close() }
    }

    private fun tryDng() {
        val pair = synchronized(rawLock) {
            val i = pendingRaw
            val r = pendingResult
            if (i == null || r == null) null else {
                pendingRaw = null; pendingResult = null
                Pair(i, r)
            }
        } ?: return
        val (img, result) = pair
        try {
            val phys = lenses[lensIdx].phys
            val res: CaptureResult =
                (if (phys != null && result is TotalCaptureResult) result.physicalCameraResults[phys] else null) ?: result
            val dng = DngCreator(capChars, res)
            dng.setOrientation(1) // ORIENTATION_NORMAL (app en horizontal)
            val uri = newMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "RAW_${stamp()}.dng", "image/x-adobe-dng")
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, img) }
                toast("RAW (DNG) guardado")
            }
            dng.close()
        } catch (e: Exception) {
            toast("Error guardando RAW: ${e.message}")
        } finally { img.close() }
    }

    // =====================================================================
    // Video
    // =====================================================================
    private fun startRecording() {
        val d = device ?: return
        val my = ++gen
        try {
            session?.close(); session = null
            val uri = newMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VID_${stamp()}.mp4", "video/mp4")
                ?: throw IllegalStateException("No se pudo crear el archivo")
            val pfd = contentResolver.openFileDescriptor(uri, "rw")!!
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            val audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioOk) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(pfd.fileDescriptor)
            val bitrate = min(120_000_000.0, videoSize.width.toDouble() * videoSize.height * videoFps * 0.08).toInt()
            mr.setVideoEncodingBitRate(bitrate)
            mr.setVideoFrameRate(videoFps)
            mr.setVideoSize(videoSize.width, videoSize.height)
            mr.setVideoEncoder(
                if (videoSize.width >= 3840) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264
            )
            if (audioOk) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(192_000)
                mr.setAudioSamplingRate(48_000)
            }
            mr.setOrientationHint(orient())
            mr.prepare()
            recorder = mr
            recPfd = pfd

            val outs = listOf(cfg(previewSurface!!), cfg(mr.surface))
            d.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (my != gen) { s.close(); return }
                        session = s
                        recording = true
                        repeatNow()
                        try { mr.start() } catch (e: Exception) {
                            toast("No se pudo iniciar: ${e.message}")
                            recording = false
                            finishRecorder()
                            startPreview()
                        }
                        runOnUiThread { updateUiState() }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        toast("Esta resolución/fps no es compatible con esta lente")
                        finishRecorder()
                        startPreview()
                    }
                }))
        } catch (e: Exception) {
            toast("Error de video: ${e.message}")
            finishRecorder()
            startPreview()
        }
    }

    private fun finishRecorder() {
        recording = false
        try { recorder?.reset() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        try { recPfd?.close() } catch (e: Exception) { }
        recPfd = null
    }

    private fun stopRecording() {
        recording = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (e: Exception) { }
        try { recorder?.stop(); toast("Video guardado") } catch (e: RuntimeException) { toast("Video demasiado corto") }
        finishRecorder()
        updateUiState()
        startPreview()
    }

    // =====================================================================
    // Interfaz
    // =====================================================================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 11f
    }

    private fun addSeek(parent: LinearLayout, title: String, init: Int, onP: (Int) -> Unit) {
        parent.addView(label(title))
        parent.addView(SeekBar(this).apply {
            max = 100
            progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onP(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun updateManualLabel() {
        val exp = expValue(videoMode)
        tvVals.text = "ISO ${isoValue()}  |  1/${(1e9 / exp).toInt().coerceAtLeast(1)} s  |  " +
            (if (minFocus > 0f) "enfoque %.1f".format(focusValue()) else "enfoque fijo")
    }

    private fun onManualChanged() {
        updateManualLabel()
        repeatNow()
    }

    private fun updateUiState() {
        btnMode.text = if (videoMode) "Modo: VIDEO" else "Modo: FOTO"
        btnRaw.text = if (rawOn) "RAW: SÍ" else "RAW: NO"
        btnManual.text = if (manual) "Manual: SÍ" else "Manual: NO"
        resSpinner.visibility = if (videoMode) View.VISIBLE else View.GONE
        fpsSpinner.visibility = if (videoMode) View.VISIBLE else View.GONE
        btnRaw.visibility = if (!videoMode && rawSupported) View.VISIBLE else View.GONE
        manualGroup.visibility = if (manual) View.VISIBLE else View.GONE
        btnShutter.text = if (!videoMode) "FOTO" else if (recording) "PARAR" else "GRABAR"
        btnLens.isEnabled = !recording
        btnMode.isEnabled = !recording
        resSpinner.isEnabled = !recording
        fpsSpinner.isEnabled = !recording
        updateManualLabel()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }

        btnLens = btn("Lente") {
            if (lenses.isNotEmpty() && !recording) {
                closeCamera()
                lensIdx = (lensIdx + 1) % lenses.size
                texture.postDelayed({ openCamera() }, 400)
            }
        }
        btnMode = btn("Modo") {
            if (!recording) { videoMode = !videoMode; updateUiState(); startPreview() }
        }
        btnRaw = btn("RAW") { rawOn = !rawOn; updateUiState(); startPreview() }
        btnManual = btn("Manual") { manual = !manual; updateUiState(); repeatNow() }

        resSpinner = Spinner(this)
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in videoSizes.indices && videoSizes[pos] != videoSize) {
                    videoSize = videoSizes[pos]
                    if (videoMode && !recording) startPreview()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fpsSpinner = Spinner(this)
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in fpsOptions.indices && fpsOptions[pos] != videoFps) {
                    videoFps = fpsOptions[pos]
                    if (videoMode && !recording) repeatNow()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        manualGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvVals = label("")
        manualGroup.addView(tvVals)
        addSeek(manualGroup, "ISO", isoP) { isoP = it; onManualChanged() }
        addSeek(manualGroup, "Obturador", expP) { expP = it; onManualChanged() }
        addSeek(manualGroup, "Enfoque (izq = infinito)", focP) { focP = it; onManualChanged() }

        tvInfo = label("")

        panel.addView(btnLens)
        panel.addView(btnMode)
        panel.addView(resSpinner)
        panel.addView(fpsSpinner)
        panel.addView(btnRaw)
        panel.addView(btnManual)
        panel.addView(manualGroup)
        panel.addView(tvInfo)

        val scroll = ScrollView(this).apply { addView(panel) }
        root.addView(scroll, FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP).apply { rightMargin = dp(104) })

        btnShutter = Button(this).apply {
            text = "FOTO"
            setOnClickListener {
                if (!videoMode) takePhoto()
                else if (recording) stopRecording()
                else startRecording()
            }
        }
        root.addView(btnShutter, FrameLayout.LayoutParams(dp(92), dp(92),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })

        setContentView(root)
        updateUiState()
    }
}
