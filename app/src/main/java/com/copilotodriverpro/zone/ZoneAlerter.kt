package com.copilotodriverpro.zone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.copilotodriverpro.MainActivity
import com.copilotodriverpro.R
import com.copilotodriverpro.regions.ZoneChange
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.settings.DriverSettings
import com.copilotodriverpro.utils.Permissions
import java.util.Locale

/**
 * Avisos de "Dentro / Fora da região de atuação": notificação, vibração e voz (opcionais).
 * O aviso visual sobre o Uber/99 é feito pela [com.copilotodriverpro.overlay.ZonePillWindow].
 */
class ZoneAlerter(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun alert(change: ZoneChange, s: DriverSettings) {
        val outside = change.to == ZoneStatus.OUTSIDE
        // Começou já dentro: só o selo na tela, sem barulho nem vibração.
        if (change.initial && !outside) return

        val title = if (outside) "Fora da região de atuação" else "Dentro da região de atuação"
        val text = if (outside) {
            "Você saiu das regiões que escolheu."
        } else {
            change.regionName?.let { "Região: $it" } ?: "Você voltou para sua área."
        }
        if (s.zoneNotify) notify(title, text)
        if (s.zoneVibrate) vibrate(outside)
        if (s.zoneVoice) speak(title)
    }

    /**
     * A corrida que acabou de aparecer leva o motorista para FORA da região.
     * Independe do GPS (vem do endereço da oferta). Vibração diferente da do GPS: 2 pulsos médios.
     */
    fun alertRideOutside(s: DriverSettings) {
        if (s.rideRegionVibrate) vibratePattern(longArrayOf(0, 300, 120, 300))
        if (s.rideRegionVoice) speak("Atenção. Esta corrida sai da sua região.")
    }

    private fun notify(title: String, text: String) {
        if (!Permissions.canPostNotifications(context)) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Aviso de região de atuação", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_copiloto)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(0xFFFECA07.toInt())
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun vibrate(outside: Boolean) {
        // FORA: três vibrações longas (chama atenção). DENTRO: uma curta.
        vibratePattern(if (outside) longArrayOf(0, 400, 150, 400, 150, 400) else longArrayOf(0, 250))
    }

    private fun vibratePattern(pattern: LongArray) {
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun speak(text: String) {
        runCatching {
            val current = tts
            if (current != null) {
                if (ttsReady) current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zone")
                return
            }
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine?.language = Locale("pt", "BR")
                    ttsReady = true
                    engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zone")
                }
            }
            tts = engine
        }
    }

    companion object {
        private const val CHANNEL_ID = "copiloto_zone"
        private const val NOTIFICATION_ID = 201
    }
}
