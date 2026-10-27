package com.copilotodriverpro.floating

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.MainActivity
import com.copilotodriverpro.R
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.recognition.ImageOcrProcessor
import com.copilotodriverpro.recognition.RideScreenClassifier
import com.copilotodriverpro.recognition.RideTextParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Print de corrida: captura de tela da oferta via MediaProjection.
 *
 * - Só roda com consentimento explícito (o sistema mostra o diálogo e o
 *   indicador permanente de captura) — nunca captura oculta.
 * - Observa as ofertas detectadas; ao ver uma NOVA oferta, salva o último
 *   frame em filesDir/prints e, se faltar valor/distância, tenta OCR local
 *   nos frames seguintes para completar os dados (leitura alternativa).
 * - Para quando o usuário desliga a função ou revoga o consentimento.
 */
class ScreenCaptureService : Service() {

    private val app get() = application as CopilotApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastOfferId: String? = null
    private var pendingOcr = false

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener
        try {
            val buffer = image.planes[0].buffer
            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride
            val width = image.width
            val height = image.height
            val padding = (rowStride - pixelStride * width) / pixelStride.coerceAtLeast(1)
            val raw = Bitmap.createBitmap(width + padding, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            // Recorta a área visível real (remove o padding da linha)
            val visible = if (padding > 0) Bitmap.createBitmap(raw, 0, 0, width, height) else raw
            lastFrame?.recycle()
            lastFrame = visible
            if (raw !== visible) raw.recycle()

            if (pendingOcr) {
                pendingOcr = false
                val bmp = visible
                scope.launch(Dispatchers.IO) { runOcr(bmp) }
            }
        } catch (e: Exception) {
            // frame inválido — ignora e espera o próximo
        } finally {
            image.close()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // startForeground ANTES de getMediaProjection (exigência do Android 14+)
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val proj = mgr.getMediaProjection(resultCode, resultData)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopEverything()
            }, null)
            setupDisplay(proj)
            observeOffers()
        }.onFailure {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupDisplay(proj: MediaProjection) {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        ).apply { setOnImageAvailableListener(imageListener, null) }

        virtualDisplay = proj.createVirtualDisplay(
            "PilotoNinjaCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    /** Salva print em filesDir/prints sempre que uma oferta NOVA aparece. */
    private fun observeOffers() {
        scope.launch {
            app.rideState.ui.collect { state ->
                val offer = state.offer ?: return@collect
                // Ofertas digitadas à mão não têm tela para provar
                if (offer.source == RideSource.MANUAL) return@collect
                if (offer.id == lastOfferId) return@collect
                lastOfferId = offer.id

                val frame = lastFrame ?: return@collect
                val file = saveFrame(frame, offer.id) ?: return@collect
                Toast.makeText(
                    this@ScreenCaptureService,
                    "Print da oferta salvo: ${file.name}",
                    Toast.LENGTH_SHORT
                ).show()

                // Leitura alternativa: se dados essenciais faltam, tenta OCR local
                if (offer.fare == null || offer.tripDistanceKm == null) {
                    pendingOcr = true
                }
            }
        }
    }

    private fun saveFrame(bitmap: Bitmap, offerId: String): File? = runCatching {
        val dir = File(filesDir, "prints").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "oferta_${stamp}_$offerId.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file
    }.getOrNull()

    /** OCR local do frame atual; completa apenas os campos que faltam. */
    private suspend fun runOcr(frame: Bitmap) {
        runCatching {
            val text = ImageOcrProcessor.recognizeTextFromBitmap(frame)
            val store = app.rideState
            val current = store.ui.value.offer ?: return
            // Interpreta com a plataforma da oferta atual (Uber: trecho único é ambíguo) e já sem as
            // linhas do próprio painel do app, que o OCR também enxerga na captura de tela.
            val parsed = RideTextParser.parse(text, RideSource.OCR_IMAGE, current.platform) ?: return

            val merged = com.copilotodriverpro.recognition.OfferConsistency.validate(
                current.copy(
                    fare = current.fare ?: parsed.fare,
                    tripDistanceKm = current.tripDistanceKm ?: parsed.tripDistanceKm,
                    distanceToPickupKm = current.distanceToPickupKm ?: parsed.distanceToPickupKm,
                    timeToPickupMin = current.timeToPickupMin ?: parsed.timeToPickupMin,
                    durationMin = current.durationMin ?: parsed.durationMin,
                    passengerRating = current.passengerRating ?: parsed.passengerRating,
                    ratingCount = current.ratingCount ?: parsed.ratingCount,
                    rideType = current.rideType ?: parsed.rideType,
                    platformPerKmHint = current.platformPerKmHint ?: parsed.platformPerKmHint,
                    bonusConfirmed = current.bonusConfirmed ?: parsed.bonusConfirmed,
                    bonusIncludedInFare = current.bonusIncludedInFare || parsed.bonusIncludedInFare,
                    rawText = current.rawText.ifBlank { text }
                )
            )
            val settings = app.settingsRepository.settings.first()
            val regions = app.regionRepository.regions.first()
            // A oferta pode ter sumido durante o OCR: só troca se ainda for a mesma (atômico).
            store.replaceOfferIfCurrent(current.id, merged, settings)
        }
    }

    private fun stopEverything() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        scope.cancel()
        app.updateSetting { it.copy(printEnabled = false) }
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copiloto)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openApp)
            .addAction(0, "Encerrar captura", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "piloto_capture"
        private const val NOTIF_ID = 103
        const val ACTION_STOP = "com.copilotodriverpro.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
