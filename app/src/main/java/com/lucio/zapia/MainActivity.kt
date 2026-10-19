package com.lucio.zapia

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

// ============================================================================
// 1. CLIENTE DE APIs (GROQ STT + FISH AUDIO TTS CON COLA DE REPRODUCCIÓN)
// ============================================================================
object ApiClient {
    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val speechQueue = Channel<Pair<Context, String>>(Channel.UNLIMITED)
    private var currentPlayer: MediaPlayer? = null

    init {
        scope.launch {
            for ((ctx, text) in speechQueue) {
                playFishAudioSequential(ctx, text)
            }
        }
    }

    fun enqueueSpeech(context: Context, text: String) {
        if (text.isBlank()) return
        speechQueue.trySend(context.applicationContext to text.trim())
    }

    fun stopAllSpeech() {
        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (_: Exception) {}
        currentPlayer = null
    }

    suspend fun transcribeGroq(audioFile: File, groqKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", "es")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${groqKey.trim()}")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                val respStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Groq Error ${resp.code}: $respStr"))
                }
                val text = JSONObject(respStr).optString("text", "").trim()
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun playFishAudioSequential(context: Context, text: String) {
        val prefs = context.getSharedPreferences("zapia_prefs", Context.MODE_PRIVATE)
        val fishKey = prefs.getString("fish_key", "")?.trim() ?: ""
        val voiceId = prefs.getString("fish_voice", "")?.trim() ?: ""
        if (fishKey.isEmpty()) return

        try {
            val json = JSONObject().apply {
                put("text", text)
                put("format", "mp3")
                put("latency", "balanced")
                if (voiceId.isNotEmpty()) {
                    put("reference_id", voiceId)
                }
            }

            val req = Request.Builder()
                .url("https://api.fish.audio/v1/tts")
                .addHeader("Authorization", "Bearer $fishKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val mp3File = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val bytes = resp.body?.bytes() ?: return
                mp3File.writeBytes(bytes)
            }

            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                currentPlayer = mp
                mp.setDataSource(mp3File.absolutePath)
                mp.setOnCompletionListener {
                    mp.release()
                    mp3File.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, _, _ ->
                    mp.release()
                    mp3File.delete()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                mp.prepare()
                mp.start()
            }
        } catch (_: Exception) {}
    }
}

// ============================================================================
// 2. PANTALLA PRINCIPAL (CONFIGURACIÓN DE CLAVES Y ESCÁNER DE ZAPIA)
// ============================================================================
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val prefs = getSharedPreferences("zapia_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scroll.addView(root)

        fun label(txt: String) = TextView(this).apply {
            text = txt
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 24, 0, 8)
        }

        fun input(hintTxt: String, key: String) = EditText(this).apply {
            hint = hintTxt
            setText(prefs.getString(key, ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "⚡ Zapia Voice Bridge"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 22f
        }
        root.addView(title)

        val groqInput = input("Pega tu Groq API Key (gsk_...)", "groq_key")
        val fishInput = input("Pega tu Fish Audio API Key", "fish_key")
        val voiceInput = input("ID de Voz Fish Audio (reference_id, opcional)", "fish_voice")

        root.addView(label("1. Groq API Key (STT):"))
        root.addView(groqInput)
        root.addView(label("2. Fish Audio API Key (TTS):"))
        root.addView(fishInput)
        root.addView(label("3. Fish Audio Voice ID (reference_id):"))
        root.addView(voiceInput)

        val saveBtn = Button(this).apply {
            text = "💾 Guardar Claves y Probar Voz"
            setOnClickListener {
                prefs.edit()
                    .putString("groq_key", groqInput.text.toString().trim())
                    .putString("fish_key", fishInput.text.toString().trim())
                    .putString("fish_voice", voiceInput.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Claves guardadas. Probando Fish Audio...", Toast.LENGTH_SHORT).show()
                ApiClient.enqueueSpeech(this@MainActivity, "Hola Lucio, conexión con Fish Audio funcionando perfecto.")
            }
        }
        root.addView(saveBtn)

        val accBtn = Button(this).apply {
            text = "⚙️ 1. Activar Servicio de Accesibilidad"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accBtn)

        val assistBtn = Button(this).apply {
            text = "🤖 2. Elegir como Asistente Predeterminado"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
        root.addView(assistBtn)

        val scanBtn = Button(this).apply {
            text = "📋 Copiar Escaneo de Pantalla de Zapia"
            setOnClickListener {
                val log = ZapiaAccessibilityService.lastScanReport
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ZapiaScan", log))
                Toast.makeText(this@MainActivity, "Escaneo copiado al portapapeles (${log.length} chars)", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(scanBtn)

        setContentView(scroll)
    }
}

// ============================================================================
// 3. VENTANA FLOTANTE DEL BOTÓN DE ASISTENTE (GRABA Y MANDA A GROQ)
// ============================================================================
class AssistantActivity : Activity() {
    private var recorder: MediaRecorder? = null
    private lateinit var audioFile: File
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var statusTv: TextView
    private lateinit var actionBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.stopAllSpeech()

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { finish() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 40f
            }
            setOnClickListener { /* evita cerrar al tocar adentro */ }
        }

        statusTv = TextView(this).apply {
            text = "🎙️ Escuchando para Zapia..."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        card.addView(statusTv)

        actionBtn = Button(this).apply {
            text = "⏹️ Terminar y Enviar a Zapia"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setOnClickListener { stopAndTranscribe() }
        }
        card.addView(actionBtn)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(32, 32, 32, 64) }

        container.addView(card, params)
        setContentView(container)

        startRecording()
    }

    private fun startRecording() {
        try {
            audioFile = File(cacheDir, "prompt.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            statusTv.text = "Error micrófono: ${e.message}. Abrí la app primero para dar permiso."
        }
    }

    private fun stopAndTranscribe() {
        actionBtn.isEnabled = false
        statusTv.text = "⚡ Transcribiendo con Groq..."
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null

        val prefs = getSharedPreferences("zapia_prefs", Context.MODE_PRIVATE)
        val groqKey = prefs.getString("groq_key", "") ?: ""
        if (groqKey.isBlank()) {
            statusTv.text = "Falta configurar tu Groq API Key en la app."
            return
        }

        scope.launch {
            val res = ApiClient.transcribeGroq(audioFile, groqKey)
            res.onSuccess { text ->
                if (text.isBlank()) {
                    statusTv.text = "No se escuchó nada."
                    return@onSuccess
                }
                statusTv.text = "📝 \"$text\"\nAbriendo Zapia..."
                ZapiaAccessibilityService.enqueuePromptForZapia(text)
                launchZapiaApp()
                delay(600)
                finish()
            }.onFailure { err ->
                statusTv.text = "Error Groq: ${err.message}"
            }
        }
    }

    private fun launchZapiaApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.brainlogic.zapia")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "No se encontró la app de Zapia instalada", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.release() } catch (_: Exception) {}
        scope.cancel()
    }
}

// ============================================================================
// 4. SERVICIO DE ACCESIBILIDAD (INYECTA TEXTO Y LEE EN TIEMPO REAL)
// ============================================================================
class ZapiaAccessibilityService : AccessibilityService() {

    companion object {
        var pendingPrompt: String? = null
        var lastScanReport: String = "Aún no se abrió Zapia con el servicio activo."
        private val seenTexts = mutableSetOf<String>()
        private var watchingSession = false

        fun enqueuePromptForZapia(prompt: String) {
            pendingPrompt = prompt
            watchingSession = true
            seenTexts.add(prompt.trim())
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var readDebounceRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.brainlogic.zapia") return

        val root = rootInActiveWindow ?: return

        // Guardar siempre una radiografía de los elementos de Zapia para diagnóstico
        val sb = StringBuilder()
        dumpNodes(root, 0, sb)
        lastScanReport = sb.toString()

        // Si hay un mensaje pendiente de enviar desde nuestro asistente:
        val prompt = pendingPrompt
        if (prompt != null) {
            // Registrar textos actuales en pantalla antes de enviar para no leer historial viejo
            collectTexts(root, seenTexts)
            if (tryInjectAndSend(root, prompt)) {
                pendingPrompt = null
            }
            return
        }

        // Si estamos en una sesión activa esperando respuesta de Zapia:
        if (watchingSession) {
            readDebounceRunnable?.let { handler.removeCallbacks(it) }
            readDebounceRunnable = Runnable {
                val currentRoot = rootInActiveWindow ?: return@Runnable
                val currentList = mutableListOf<String>()
                collectTexts(currentRoot, currentList)
                for (txt in currentList) {
                    val clean = txt.trim()
                    if (clean.length > 3 && !seenTexts.contains(clean)) {
                        seenTexts.add(clean)
                        ApiClient.enqueueSpeech(applicationContext, clean)
                    }
                }
            }
            handler.postDelayed(readDebounceRunnable!!, 1100)
        }
    }

    private fun tryInjectAndSend(root: AccessibilityNodeInfo, prompt: String): Boolean {
        val editNode = findEditableNode(root) ?: return false

        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
        }
        val setOk = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (setOk) {
            handler.postDelayed({
                val freshRoot = rootInActiveWindow ?: return@postDelayed
                val sendBtn = findSendButton(freshRoot)
                sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }, 450)
            return true
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
        if (node.isClickable && (desc.contains("enviar", true) || desc.contains("send", true))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableCollection<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
        }
    }

    private fun dumpNodes(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "Node"
        val txt = node.text?.toString()?.replace("\n", " ") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", " ") ?: ""
        val flags = buildString {
            if (node.isEditable) append("[EDIT] ")
            if (node.isClickable) append("[CLICK] ")
            if (node.isFocusable) append("[FOCUS] ")
        }
        if (txt.isNotEmpty() || desc.isNotEmpty() || flags.isNotEmpty()) {
            sb.append("$indent$cls $flags txt='$txt' desc='$desc'\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodes(child, depth + 1, sb)
        }
    }

    override fun onInterrupt() {}
}