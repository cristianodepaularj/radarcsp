package com.autoaceita.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.autoaceita.data.LogRepository
import com.autoaceita.data.SettingsRepository
import com.autoaceita.model.AppSettings
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Radar de corridas: quando o app monitorado mostra uma oferta, tira uma captura da tela,
 * lê o texto (OCR no aparelho) e mostra uma faixa colorida sobre a tela:
 *   VERMELHO  PÉSSIMA        abaixo de R$ 1,50/km
 *   AMARELO   MAIS OU MENOS  de R$ 1,50 a R$ 1,99/km
 *   VERDE     ÓTIMA          R$ 2,00/km ou mais
 * Não toca em nada. A imagem é processada em memória e descartada.
 */
class AutoAceitaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    @Volatile private var settings = AppSettings()
    private var appliedFilter: String? = null
    private var tickerJob: Job? = null

    private var lastUberEvent = 0L
    private var lastDetectLog = -60_000L
    private var lastDiagLog = -60_000L
    private var lastOfferKey = ""
    private var busy = false
    private var misses = 0
    private var blanks = 0

    private var overlay: LinearLayout? = null
    private var overlayTitle: TextView? = null
    private var overlaySub: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val repo = SettingsRepository(applicationContext)
        scope.launch {
            repo.settings.collect { s ->
                settings = s
                applyPackageFilter(s)
                if (!s.enabled) hideOverlay()
            }
        }
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                tick()
            }
        }
    }

    private fun applyPackageFilter(s: AppSettings) {
        val target = if (s.enabled && s.monitoredPackage.isNotBlank()) s.monitoredPackage else IDLE_PACKAGE
        if (target == appliedFilter) return
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(target)
        serviceInfo = info
        appliedFilter = target
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val s = settings
        if (event == null || !s.enabled || s.monitoredPackage.isBlank()) return
        if (event.packageName?.toString() != s.monitoredPackage) return
        val now = SystemClock.elapsedRealtime()
        lastUberEvent = now
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - lastDetectLog > 30_000) {
            LogRepository.add("Aplicativo monitorado detectado")
            lastDetectLog = now
        }
    }

    private fun tick() {
        val s = settings
        if (!s.enabled || s.monitoredPackage.isBlank()) {
            hideOverlay()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUberEvent > ACTIVE_MS) {
            hideOverlay()
            return
        }
        if (busy) return
        if (Build.VERSION.SDK_INT < 30) {
            logOnce("Este Android é antigo demais para captura de tela (precisa Android 11+)")
            return
        }
        busy = true
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        handleShot(result)
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        logOnce("Falha na captura de tela (código $errorCode)")
                    }
                }
            )
        } catch (e: Exception) {
            busy = false
            logOnce("Erro ao capturar a tela")
        }
    }

    private fun handleShot(result: AccessibilityService.ScreenshotResult) {
        var decoded: Bitmap? = null
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            if (hw != null) {
                decoded = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
            }
        } catch (_: Exception) {
        } finally {
            buffer.close()
        }
        val full = decoded
        if (full == null) {
            busy = false
            return
        }
        try {
            // Lê só a parte de baixo da tela (onde fica o cartão); a faixa colorida fica no topo
            val top = (full.height * CROP_TOP).toInt()
            val cropped = Bitmap.createBitmap(full, 0, top, full.width, full.height - top)
            full.recycle()
            recognizer.process(InputImage.fromBitmap(cropped, 0))
                .addOnSuccessListener { r ->
                    cropped.recycle()
                    busy = false
                    onText(r.text)
                }
                .addOnFailureListener {
                    cropped.recycle()
                    busy = false
                    logOnce("Falha na leitura do texto")
                }
        } catch (e: Exception) {
            busy = false
            logOnce("Erro ao processar a imagem")
        }
    }

    private fun onText(text: String) {
        if (text.isBlank()) {
            blanks++
            if (blanks >= 3) logOnce("Captura vazia: a tela pode estar protegida pelo app")
            misses++
            if (misses >= MISSES_TO_HIDE) hideOverlay()
            return
        }
        blanks = 0

        val offer = OfferTextParser.parse(text)
        if (offer == null) {
            misses++
            if (misses >= MISSES_TO_HIDE) hideOverlay()
            return
        }
        misses = 0

        val rating = rate(offer.perKm)
        showOverlay(rating, offer)

        val key = "${offer.price}|${offer.perKm}"
        if (key != lastOfferKey) {
            lastOfferKey = key
            LogRepository.add("Oferta: ${money(offer.price)} • ${perKmText(offer.perKm)} • ${rating.label}")
        }
    }

    private fun rate(perKm: Double): Rating {
        val r = Math.round(perKm * 100) / 100.0
        return when {
            r < RED_BELOW -> Rating.RED
            r < GREEN_FROM -> Rating.YELLOW
            else -> Rating.GREEN
        }
    }

    // ---------- Faixa colorida sobreposta ----------

    private fun ensureOverlay() {
        if (overlay != null) return
        val dp = resources.displayMetrics.density
        val title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (10 * dp).toInt(), (24 * dp).toInt(), (10 * dp).toInt())
            addView(title)
            addView(sub)
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * dp).toInt()
        }
        try {
            wm.addView(box, lp)
            overlay = box
            overlayTitle = title
            overlaySub = sub
        } catch (e: Exception) {
            logOnce("Não foi possível criar a faixa sobre a tela")
        }
    }

    private fun showOverlay(rating: Rating, offer: Parsed) {
        ensureOverlay()
        val box = overlay ?: return
        val dp = resources.displayMetrics.density
        box.background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(rating.bg)
        }
        overlayTitle?.apply {
            text = rating.label
            setTextColor(rating.fg)
        }
        val parts = ArrayList<String>()
        parts.add(perKmText(offer.perKm))
        offer.totalKm?.let { parts.add(String.format(PT_BR, "%.1f km", it)) }
        offer.price?.let { parts.add(money(it)) }
        overlaySub?.apply {
            text = parts.joinToString("  •  ")
            setTextColor(rating.fg)
        }
        box.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlay?.visibility = View.GONE
        misses = 0
    }

    private fun logOnce(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagLog < 30_000) return
        lastDiagLog = now
        LogRepository.add(msg)
    }

    private fun money(v: Double) = String.format(PT_BR, "R\$ %.2f", v)
    private fun perKmText(v: Double) = String.format(PT_BR, "R\$ %.2f/km", v)

    override fun onInterrupt() {}

    override fun onDestroy() {
        tickerJob?.cancel()
        scope.cancel()
        try {
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private companion object {
        const val IDLE_PACKAGE = "com.autoaceita.idle.nothing"
        const val POLL_MS = 1200L          // intervalo entre capturas
        const val ACTIVE_MS = 12_000L      // só captura enquanto o app monitorado teve atividade recente
        const val CROP_TOP = 0.35f         // ignora os 35% de cima da tela
        const val MISSES_TO_HIDE = 2

        // >>> Limites das cores (R$ por km). Altere aqui se quiser. <<<
        const val RED_BELOW = 1.50         // abaixo disso = PÉSSIMA
        const val GREEN_FROM = 2.00        // a partir disso = ÓTIMA (entre os dois = MAIS OU MENOS)

        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    }
}

private enum class Rating(val label: String, val bg: Int, val fg: Int) {
    RED("PÉSSIMA", 0xFFD32F2F.toInt(), Color.WHITE),
    YELLOW("MAIS OU MENOS", 0xFFFBC02D.toInt(), Color.BLACK),
    GREEN("ÓTIMA", 0xFF2E7D32.toInt(), Color.WHITE)
}

private class Parsed(val price: Double?, val perKm: Double, val totalKm: Double?)

/** Lê preço, km e "R$/km aprox." do texto reconhecido na tela. */
private object OfferTextParser {
    private val priceRegex = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})+|\d+)(?:[.,](\d{1,2}))?""")
    private val perKmRegex = Regex("""R\$\s*(\d+)[.,](\d{1,2})\s*/\s*km""", RegexOption.IGNORE_CASE)
    private val distRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m)(?![A-Za-zÀ-ÿ])""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Parsed? {
        val t = raw.replace('\n', ' ')

        val perKmUber = perKmRegex.find(t)?.let { m ->
            "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull()
        }

        // Preço da corrida: ignora "R$ x/km" e "+R$ x incluído"
        val priceMatch = priceRegex.findAll(t).firstOrNull { m ->
            val before = t.substring(0, m.range.first).trimEnd().lastOrNull()
            val after = t.substring(m.range.last + 1).trimStart().lowercase()
            before != '+' && !after.startsWith("/") && !after.startsWith("km") && !after.startsWith("inclu")
        }
        val price = priceMatch?.let { m ->
            val intPart = m.groupValues[1].replace(".", "")
            val dec = m.groupValues[2]
            (if (dec.isEmpty()) intPart else "$intPart.$dec").toDoubleOrNull()
        }

        val dists = distRegex.findAll(t).mapNotNull { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (m.groupValues[2].equals("m", ignoreCase = true)) v / 1000.0 else v
        }.toList()

        val total: Double? = when {
            dists.size >= 2 -> dists[0] + dists[1]   // até o passageiro + viagem
            dists.size == 1 -> dists[0]
            else -> null
        }
        val computed: Double? =
            if (price != null && dists.size >= 2 && total != null && total > 0.0) price / total else null

        val perKm = perKmUber ?: computed ?: return null
        if (perKm < 0.1 || perKm > 50.0) return null
        return Parsed(price, perKm, total)
    }
}
