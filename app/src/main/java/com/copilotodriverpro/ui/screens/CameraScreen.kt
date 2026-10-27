
package com.copilotodriverpro.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Estado global da gravação, compartilhado com outras telas.
 */
object CameraState {
    val recording = MutableStateFlow(false)
}

/**
 * Câmera sentinela.
 * A gravação só começa após uma ação explícita do motorista.
 */
@Composable
fun CameraScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by app.settingsRepository.settings.collectAsState(
        initial = null
    )
    val isRecording by CameraState.recording.collectAsState()

    var granted by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }

    var videoCapture by remember {
        mutableStateOf<VideoCapture<Recorder>?>(null)
    }
    var activeRecording by remember {
        mutableStateOf<Recording?>(null)
    }
    var durationMin by remember { mutableFloatStateOf(30f) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(settings?.cameraMaxDurationMin) {
        settings?.cameraMaxDurationMin?.let {
            durationMin = it.toFloat().coerceIn(5f, 60f)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted =
            result[Manifest.permission.CAMERA] == true &&
                    result[Manifest.permission.RECORD_AUDIO] == true

        if (!granted) {
            Toast.makeText(
                context,
                "Permita o acesso à câmera e ao microfone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions() {
        permissionRequested = true

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    fun startRecording() {
        val capture = videoCapture

        if (capture == null || !hasPermissions()) {
            Toast.makeText(
                context,
                "Câmera indisponível ou permissões ausentes.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (activeRecording != null) return

        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "piloto_ninja_${System.currentTimeMillis()}"
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q
            ) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/PilotoNinjaGO"
                )
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(values)
            .build()

        try {
            val pendingRecording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()

            activeRecording = pendingRecording.start(
                ContextCompat.getMainExecutor(context)
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        CameraState.recording.value = true
                    }

                    is VideoRecordEvent.Finalize -> {
                        CameraState.recording.value = false
                        activeRecording = null

                        if (event.hasError()) {
                            Toast.makeText(
                                context,
                                "Falha ao salvar o vídeo. Verifique as permissões e o armazenamento.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Vídeo salvo em Filmes/PilotoNinjaGO.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            activeRecording = null
            CameraState.recording.value = false

            Toast.makeText(
                context,
                "Não foi possível iniciar a gravação.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Solicita permissões apenas uma vez ao abrir a tela.
    LaunchedEffect(Unit) {
        if (hasPermissions()) {
            granted = true
        } else if (!permissionRequested) {
            requestPermissions()
        }
    }

    // Inicializa a câmera quando as permissões e a prévia estiverem prontas.
    LaunchedEffect(granted, previewView, lifecycleOwner) {
        val view = previewView

        if (!granted || view == null) return@LaunchedEffect

        try {
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener(
                {
                    try {
                        val provider = future.get()

                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(view.surfaceProvider)
                            }

                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(Quality.HD)
                            )
                            .build()

                        val capture = VideoCapture.withOutput(recorder)

                        provider.unbindAll()

                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )

                        videoCapture = capture
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Não foi possível inicializar a câmera.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Câmera indisponível neste aparelho.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            activeRecording?.stop()
            activeRecording = null
            CameraState.recording.value = false

            try {
                ProcessCameraProvider.getInstance(context)
                    .get()
                    .unbindAll()
            } catch (_: Exception) {
                // O provedor pode ainda não estar inicializado.
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Câmera sentinela",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Grave vídeo e áudio da viagem. A gravação só começa quando você tocar no botão.",
            style = MaterialTheme.typography.bodyMedium
        )

        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    it.implementationMode =
                        PreviewView.ImplementationMode.COMPATIBLE
                    previewView = it
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardSecondary)
        )

        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SignalRed.copy(alpha = 0.2f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("●", color = SignalRed, fontWeight = FontWeight.Bold)
                Text(
                    "GRAVAÇÃO ATIVA",
                    color = SignalRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Button(
            onClick = {
                if (isRecording) {
                    activeRecording?.stop()
                } else if (hasPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = isRecording || videoCapture != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) SignalRed else SignalGreen
            )
        ) {
            Text(
                if (isRecording) "Parar gravação" else "Iniciar gravação"
            )
        }

        CopilotoCard("Configuração da gravação") {
            Text(
                "Duração máxima configurada: ${durationMin.toInt()} minutos",
                style = MaterialTheme.typography.bodyLarge
            )

            Slider(
                value = durationMin,
                onValueChange = { durationMin = it },
                onValueChangeFinished = {
                    app.updateSetting {
                        it.copy(cameraMaxDurationMin = durationMin.toInt())
                    }
                },
                valueRange = 5f..60f
            )

            Text(
                text = "Os vídeos são salvos em Filmes/PilotoNinjaGO. O áudio requer permissão para o microfone.",
                style = MaterialTheme.typography.labelSmall
            )
        }

        CopilotoCard("Limitações") {
            Text(
                text = "• A disponibilidade da câmera depende do aparelho.\n" +
                        "• Gravar em segundo plano pode exigir um serviço em primeiro plano.\n" +
                        "• Verifique o espaço disponível antes de iniciar viagens longas.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        OutlinedButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Voltar", color = TextWhite)
        }
    }
}
