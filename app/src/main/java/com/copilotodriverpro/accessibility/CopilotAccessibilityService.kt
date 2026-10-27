package com.copilotodriverpro.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import com.copilotodriverpro.recognition.ImageOcrProcessor
import com.copilotodriverpro.recognition.OfferTextExtractor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.GeoCoderHelper
import com.copilotodriverpro.recognition.RideOfferValidator
import com.copilotodriverpro.recognition.RideScreenClassifier
import com.copilotodriverpro.recognition.RideTextParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AccessibilityService que lê APENAS o texto visível da tela dos apps de transporte
 * (Uber e 99) para identificar ofertas. Não clica, não toca, não digita, não aceita
 * nem recusa corridas. Nenhum conteúdo sai do aparelho.
 */
class CopilotAccessibilityService : AccessibilityService() {

    private val app get() = application as CopilotApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var lastProcessedAt = 0L
    @Volatile private var lastText = ""

    // Eventos de tela do Uber/99. CONFLATED = só o mais recente fica na fila: a leitura pesada da
    // árvore roda em thread de fundo e nunca perde o último estado da tela.
    private val screenEvents = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var eventJob: Job? = null

    // Regiões em memória (antes cada leitura esperava o DataStore ser lido de novo).
    @Volatile private var latestRegions: List<com.copilotodriverpro.models.Region>? = null

    /**
     * Corrida ACEITA: toque em Aceitar (ou tela de corrida em andamento logo depois) grava a corrida
     * no Histórico e o ganho em Lançamentos. O serviço só observa: nunca clica por você.
     */
    private val acceptTracker = com.copilotodriverpro.recognition.RideAcceptTracker { offer, evaluation ->
        scope.launch {
            runCatching { app.rideRecorder.record(offer, evaluation) }
                .onFailure { Log.e(TAG, "Falha ao gravar a corrida aceita", it) }
        }
    }

    /** Contador que invalida leituras em andamento quando a oferta some da tela. */
    private val generation = AtomicInteger(0)

    /**
     * Trava anti-fantasma: depois que uma oferta some (tempo máximo, clique ou app trocado),
     * a MESMA oferta (mesmo valor/distâncias/tempo) não pode reaparecer por um tempo, mesmo
     * que o Uber deixe nós antigos na árvore de acessibilidade.
     */
    @Volatile private var capSig: String? = null
    @Volatile private var capUntil = 0L

    private fun signature(o: RideOffer) =
        "${o.platform}|${o.fare}|${o.tripDistanceKm}|${o.distanceToPickupKm}|${o.durationMin}"

    private fun blockCurrentOffer(store: com.copilotodriverpro.state.RideStateStore) {
        store.ui.value.offer?.let {
            capSig = signature(it)
            capUntil = System.currentTimeMillis() + SUPPRESS_MS
        }
    }

    /**
     * Última vez que a oferta foi CONFIRMADA na tela (botão Aceitar visível + valor visível).
     * A oferta só é dada como encerrada depois de [ABSENT_GRACE_MS] sem confirmação: a árvore de
     * acessibilidade do Uber/99 some por instantes durante animações/contagem regressiva, e
     * encerrar na 1ª leitura ruim fazia o card sumir e reaparecer (piscar).
     */
    @Volatile private var lastPresentAt = 0L

    /** Vigia: confere se a oferta ainda está na tela e some com o card junto. */
    private var watchdogJob: Job? = null

    // Card de análise no topo — nasce e morre com o leitor.
    private var cardWindow: com.copilotodriverpro.overlay.RideCardWindow? = null

    // Selo "Dentro/Fora da região de atuação" por cima do Uber/99.
    private var zonePill: com.copilotodriverpro.overlay.ZonePillWindow? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureCard()
        ensureZonePill()
        startEventWorker()
        startWatchdog()
        scope.launch { app.regionRepository.regions.collect { latestRegions = it } }
    }

    /** Lê a tela em thread de fundo; eventos seguidos são fundidos e o mais recente sempre é lido. */
    private fun startEventWorker() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            for (pkg in screenEvents) {
                val wait = THROTTLE_MS - (System.currentTimeMillis() - lastProcessedAt)
                if (wait > 0) delay(wait)
                runCatching { processScreen(pkg) }
                    .onFailure { Log.e(TAG, "Falha ao ler a tela", it) }
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_MS)
                acceptTracker.tick()
                val store = app.rideState
                if (store.ui.value.offer?.source != RideSource.ACCESSIBILITY) {
                    // Nenhum card no ar: procura a oferta nas janelas abertas. A 99 desenha o card
                    // POR CIMA de qualquer app (Waze, Maps...) e nem sempre gera eventos próprios,
                    // então não dá para depender só de eventos do pacote da 99.
                    runCatching { scanTransportWindows() }
                    continue
                }
                val cur = store.ui.value.offer
                if (cur != null && System.currentTimeMillis() - cur.timestamp > MAX_OFFER_MS) {
                    // Trava de segurança (oferta "presa" na árvore): o card sai e não volta.
                    blockCurrentOffer(store)
                    finishOffer(store)
                    continue
                }
                val now = System.currentTimeMillis()
                if (offerStillOnScreen()) {
                    lastPresentAt = now
                    store.renewOfferLease()   // oferta ainda visível: mantém o card vivo
                } else {
                    // 99: o card pode não aparecer na árvore de acessibilidade. Confere pela imagem
                    // da tela (OCR) e mantém o card vivo enquanto o OCR ainda enxerga a oferta.
                    if (cur?.platform == SupportedPlatform.NINE_NINE) ocrScreenFor99(last99Pkg)
                    if (now - lastOcrPresentAt < OCR_PRESENT_MS) {
                        lastPresentAt = now
                        store.renewOfferLease()
                    } else if (now - lastPresentAt >= ABSENT_GRACE_MS) {
                        // Ausente de forma contínua além da tolerância: a oferta realmente saiu.
                        finishOffer(store)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 99 pela IMAGEM da tela: o card da 99 aparece por cima de outros apps e o texto dele nem
    // sempre chega à árvore de acessibilidade. takeScreenshot (Android 11+) tira a foto da tela
    // pelo próprio serviço de acessibilidade, sem diálogo de captura, e o ML Kit lê o texto.
    // ---------------------------------------------------------------------------------------
    @Volatile private var ocrBusy = false
    @Volatile private var lastOcrAt = 0L
    @Volatile private var lastOcrPresentAt = 0L
    @Volatile private var last99Pkg = "com.app99.driver"
    private val ocrExecutor by lazy { Executors.newSingleThreadExecutor() }

    private fun ocrScreenFor99(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val settings = latestSettings ?: return
        if (!settings.copilotEnabled) return
        last99Pkg = pkg
        if (ocrBusy) return
        val now = System.currentTimeMillis()
        if (now - lastOcrAt < OCR_MS) return
        lastOcrAt = now
        ocrBusy = true
        try {
            takeScreenshotCompat(pkg)
        } catch (t: Throwable) {
            ocrBusy = false
            Log.e(TAG, "Falha ao pedir a captura de tela", t)
            app.rideState.publishCaptureNote("99 via tela: não foi possível capturar (${t.javaClass.simpleName})")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotCompat(pkg: String) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ocrExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    scope.launch {
                        try {
                            val hw = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            runCatching { hw.close() }
                            if (bmp != null) {
                                val raw = ImageOcrProcessor.recognizeTextFromBitmap(bmp)
                                bmp.recycle()
                                processOcrText(raw, pkg)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Falha no OCR da tela", t)
                        } finally {
                            ocrBusy = false
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy = false
                    Log.w(TAG, "takeScreenshot falhou: $errorCode")
                    app.rideState.publishCaptureNote("99 via tela: captura recusada pelo Android (código $errorCode)")
                }
            }
        )
    }

    /** Texto lido da imagem da tela: se tem o card da 99, segue a MESMA esteira do texto da árvore. */
    private suspend fun processOcrText(raw: String, pkg: String) {
        val store = app.rideState
        // O OCR enxerga também o painel do próprio Ninja Go (R$/km, R$/hora...): tira essas linhas.
        val text = OfferTextExtractor.cleanScreenText(OfferTextExtractor.normalize(raw))
        val hasCard = RideScreenClassifier.looks99OfferWithoutButton(text)
        if (text.contains("R$")) {
            store.publishCapture("[lido pela imagem da tela]\n$text", pkg)
            store.publishOfferCandidate(text)
        }
        if (!hasCard) {
            store.publishCaptureNote("99 via tela: sem card de oferta na imagem")
            return
        }
        store.publishCaptureNote("99 via tela: card reconhecido")
        val now = System.currentTimeMillis()
        val cur = store.ui.value.offer
        if (cur != null && cur.platform == SupportedPlatform.NINE_NINE &&
            cur.source == RideSource.ACCESSIBILITY && fareVisible(text, cur.fare)
        ) {
            // Mesma oferta ainda na tela: só mantém o card vivo (não republica por causa de OCR ruidoso).
            lastOcrPresentAt = now
            lastPresentAt = now
            store.renewOfferLease()
            return
        }
        lastOcrPresentAt = now
        handleText(text, pkg, acceptVisible = true)
    }

    @Volatile private var lastScanAt = 0L

    /** Existe agora alguma janela do Uber/99 na tela (inclusive card sobreposto a outro app)? */
    private fun transportWindowPresent(): Boolean = runCatching {
        windows.any { w -> isTransport(w.root?.packageName?.toString()) }
    }.getOrDefault(false)

    /**
     * Varredura ativa: olha TODAS as janelas abertas e, se alguma for do Uber/99, lê o texto e
     * processa como se o evento tivesse chegado. Cobre o card da 99 sobreposto a outro app.
     * Só aprofunda na árvore quando existe janela de Uber/99 (custo baixo no resto do tempo).
     */
    private fun scanTransportWindows() {
        val settings = latestSettings ?: return
        if (!settings.copilotEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_MS) return
        lastScanAt = now

        val grouped = windows.mapNotNull { it.root }.groupBy { it.packageName?.toString() }
        for ((pkg, roots) in grouped) {
            if (pkg == null || !isTransport(pkg)) continue
            val platform = resolvePlatform(pkg)
            val screen = readScreen(roots, platform)
            val text = screen.text
            // 99 sem card legível na árvore (ou sem texto nenhum): confere pela imagem da tela.
            if (platform == SupportedPlatform.NINE_NINE && !RideScreenClassifier.looks99OfferWithoutButton(text)) {
                ocrScreenFor99(pkg)
            }
            if (text.isNotBlank()) acceptTracker.onScreenText(text)
            if (text.isBlank() || text == lastText) continue
            lastText = text
            val acceptVisible = screen.acceptVisible ||
                (platform == SupportedPlatform.NINE_NINE && RideScreenClassifier.looks99OfferWithoutButton(text))
            app.rideState.publishCapture(text, pkg)
            if (platform == SupportedPlatform.NINE_NINE && text.contains("R$")) {
                app.rideState.publishOfferCandidate(text)
            }
            scope.launch { handleText(text, pkg, acceptVisible) }
        }
    }

    /**
     * true somente se a MESMA oferta ainda está na tela do Uber/99: botão Aceitar presente
     * E o valor da oferta (ex.: 10,23) visível. Qualquer outra tela (mapa, "Procurando
     * viagens", corrida reservada, card de outra coisa) conta como "oferta sumiu".
     */
    private fun offerStillOnScreen(): Boolean {
        val fare = app.rideState.ui.value.offer?.fare
        val result = runCatching {
            // Agrupa por app: o card pode estar numa janela própria, separada do mapa do mesmo app.
            windows.mapNotNull { it.root }
                .groupBy { it.packageName?.toString() }
                .any { (pkg, roots) ->
                    if (!isTransport(pkg)) return@any false
                    val platform = resolvePlatform(pkg)
                    val screen = readScreen(roots, platform)
                    val text = screen.text
                    (screen.acceptVisible ||
                        (platform == SupportedPlatform.NINE_NINE && RideScreenClassifier.looks99OfferWithoutButton(text))) &&
                        RideScreenClassifier.isOfferScreen(text, platform) && fareVisible(text, fare)
                }
        }.getOrDefault(false)
        Log.d(TAG, "Vigia: oferta na tela = $result")
        return result
    }

    /** O valor da oferta (ex.: R$ 10,23) ainda aparece no texto da tela? */
    private fun fareVisible(text: String, fare: Double?): Boolean {
        if (fare == null) return true   // sem valor lido, não dá para conferir
        val wanted = String.format(java.util.Locale("pt", "BR"), "%.2f", fare)
            .replace(".", "")
        return text.replace(".", "").contains(wanted)
    }

    /** Oferta escondida de propósito (clique) que AINDA está na tela: não reexibir. */
    @Volatile private var suppressedFare: Double? = null

    /** Esconde o card agora, mas a oferta ainda está na tela: impede que ele reapareça. */
    private fun hideOffer(store: com.copilotodriverpro.state.RideStateStore, suppress: Boolean) {
        if (suppress) {
            suppressedFare = store.ui.value.offer?.fare ?: suppressedFare
            blockCurrentOffer(store)
        }
        invalidateOffer(store)
    }

    /** A oferta saiu da tela. */
    private fun finishOffer(store: com.copilotodriverpro.state.RideStateStore) {
        suppressedFare = null
        invalidateOffer(store)
    }

    /** A oferta acabou: limpa o card, descarta leituras em andamento e libera a próxima. */
    private fun invalidateOffer(store: com.copilotodriverpro.state.RideStateStore) {
        // Avisa o rastreador de aceite ANTES de limpar o estado: a oferta saiu da tela.
        store.ui.value.let { ui ->
            ui.offer?.takeIf { it.source == RideSource.ACCESSIBILITY }
                ?.let { acceptTracker.onOfferGone(it, ui.evaluation) }
        }
        generation.incrementAndGet()
        store.expireOfferLease()
        lastOcrPresentAt = 0L
        lastText = ""
        RideOfferValidator.reset()
        if (store.ui.value.offer?.source == RideSource.ACCESSIBILITY) store.clear()
        else store.setAnalyzing(false)
    }

    /** Cria o card se ainda não existir. Falhas agora aparecem no Logcat (tag Copiloto). */
    private fun ensureCard() {
        if (cardWindow != null) return
        try {
            cardWindow = com.copilotodriverpro.overlay.RideCardWindow(this, app).also { it.start() }
            Log.i(TAG, "Card de corrida criado")
        } catch (t: Throwable) {
            cardWindow = null
            Log.e(TAG, "Falha ao criar o card de corrida", t)
        }
    }

    /** Cria o selo de região se ainda não existir (falha não derruba o leitor). */
    private fun ensureZonePill() {
        if (zonePill != null) return
        try {
            zonePill = com.copilotodriverpro.overlay.ZonePillWindow(this, app).also { it.start() }
        } catch (t: Throwable) {
            zonePill = null
            Log.e(TAG, "Falha ao criar o selo de região", t)
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        eventJob?.cancel()
        runCatching { zonePill?.stop() }
        zonePill = null
        runCatching { cardWindow?.stop() }
        cardWindow = null
        super.onDestroy()
    }

    /** Só Uber e 99 (apps de motorista). Qualquer outro app é ignorado por completo. */
    private fun isTransport(pkg: String?) = RideScreenClassifier.isTransportPackage(pkg) ||
        resolvePlatform(pkg) == com.copilotodriverpro.models.SupportedPlatform.NINE_NINE

    /** Identifica a plataforma do pacote, incluindo apps da 99 com pacote novo (pelo nome do app). */
    private fun resolvePlatform(pkg: String?): com.copilotodriverpro.models.SupportedPlatform {
        if (pkg == null) return com.copilotodriverpro.models.SupportedPlatform.UNKNOWN
        RideScreenClassifier.identifyPlatform(pkg).takeIf {
            it != com.copilotodriverpro.models.SupportedPlatform.UNKNOWN
        }?.let { return it }
        // Cache: a pergunta ao PackageManager é cara e os eventos chegam aos milhares.
        resolved99Cache[pkg]?.let { return it }
        val is99 = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrNull()?.let { RideScreenClassifier.looksLike99AppLabel(it) } ?: false
        val result = if (is99) com.copilotodriverpro.models.SupportedPlatform.NINE_NINE
        else com.copilotodriverpro.models.SupportedPlatform.UNKNOWN
        resolved99Cache[pkg] = result
        return result
    }

    private val resolved99Cache = HashMap<String, com.copilotodriverpro.models.SupportedPlatform>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Quem decide que a oferta saiu da tela é o vigia (thread de fundo, com tolerância).
        // Antes, TODO evento varria a árvore inteira (até 4000 nós x janelas) na thread principal
        // e encerrava a oferta na 1ª leitura ruim: travava os eventos e fazia o card piscar.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (!isTransport(pkg)) {
            // Motorista saiu do Uber/99 para outro app: o card some na hora.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != packageName && pkg != "com.android.systemui" && pkg != "android" &&
                app.rideState.ui.value.offer?.source == RideSource.ACCESSIBILITY &&
                // O card da 99 fica POR CIMA de outros apps: se ainda há janela da 99/Uber na tela,
                // a mudança de janela do app de baixo (Waze, Maps...) não encerra a oferta.
                !transportWindowPresent()
            ) {
                blockCurrentOffer(app.rideState)
                finishOffer(app.rideState)
            }
            return   // lê SOMENTE Uber e 99
        }

        // Toque do motorista em Aceitar / Recusar / Fechar: o card some IMEDIATAMENTE,
        // sem esperar a leitura da próxima tela.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isTransport(pkg)) {
            // Textos do próprio evento. O botão da 99 costuma ser um contêiner clicável cujo evento vem
            // SEM texto (o "Aceitar" está num TextView filho): nesse caso lê o texto do botão tocado.
            val parts = ArrayList<String>()
            event.text?.forEach { t -> t.toString().takeIf { it.isNotBlank() }?.let { parts.add(it) } }
            event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (parts.isEmpty()) parts += clickedNodeTexts(event.source)
            val label = parts.joinToString(" ").lowercase()
            val platform = resolvePlatform(pkg)
            val rejectWords = listOf("recusar", "rejeitar", "fechar", "dispensar", "descartar", "cancelar")
            val clickWords = RideScreenClassifier.acceptLabels(platform) + rejectWords
            if (clickWords.any { label.contains(it) }) {
                val isReject = rejectWords.any { label.contains(it) }
                val isAccept = !isReject && parts.any { RideScreenClassifier.isAcceptText(it, platform) }
                val shown = app.rideState.ui.value
                shown.offer?.takeIf { it.source == RideSource.ACCESSIBILITY }?.let { o ->
                    if (isAccept) acceptTracker.onAcceptClick(o, shown.evaluation)
                    else if (isReject) acceptTracker.onDeclineClick(o)
                }
                hideOffer(app.rideState, suppress = true)
            }
            return
        }

        // NÃO usar runBlocking aqui: esta é a thread de callback do sistema e
        // qualquer bloqueio derruba eventos do Android. Só lemos o cache em memória.
        ensureCard() // tenta de novo caso a criação na conexão tenha falhado

        val settings = latestSettings ?: return
        if (!settings.copilotEnabled) return

        // A leitura da árvore (centenas de nós, cada um é uma chamada ao sistema) NÃO roda mais
        // nesta thread principal. O evento só avisa o leitor; eventos em sequência são fundidos e a
        // leitura mais recente sempre é processada (nada se perde pelo intervalo mínimo).
        screenEvents.trySend(pkg)
    }

    /** Lê a tela do Uber/99 (thread de fundo) e entrega o texto para interpretação. */
    private fun processScreen(pkg: String) {
        val settings = latestSettings ?: return
        if (!settings.copilotEnabled) return
        lastProcessedAt = System.currentTimeMillis()

        // O card da oferta (principalmente o da 99) pode estar numa JANELA separada do mapa, mesmo
        // sendo do mesmo app: lê TODAS as janelas do pacote e junta o texto (ordem visual).
        val roots = rootsFor(pkg)
        val platformNow = resolvePlatform(pkg)
        if (roots.isEmpty()) {
            // Diagnóstico: o Android avisou que o app mudou, mas não entregou nenhuma janela legível.
            app.rideState.publishCaptureNote(
                "${com.copilotodriverpro.history.AcceptedRideRecorder.platformLabel(platformNow)}: " +
                    "o app abriu, mas o Android não entregou a tela para leitura (conteúdo bloqueado)"
            )
            if (platformNow == SupportedPlatform.NINE_NINE) ocrScreenFor99(pkg)
            return
        }

        // Uma única passada na árvore: texto + botão Aceitar visível.
        val screen = readScreen(roots, platformNow)
        val text = screen.text
        if (text.isBlank()) {
            app.rideState.publishCaptureNote(
                "${com.copilotodriverpro.history.AcceptedRideRecorder.platformLabel(platformNow)}: " +
                    "a tela chegou SEM nenhum texto legível (o app ou o Android escondeu o conteúdo)"
            )
            if (platformNow == SupportedPlatform.NINE_NINE) ocrScreenFor99(pkg)
            return
        }

        // Botão da oferta visível agora: renova a licença do card a cada mudança de tela.
        val acceptVisible = screen.acceptVisible ||
            (platformNow == SupportedPlatform.NINE_NINE && RideScreenClassifier.looks99OfferWithoutButton(text))
        if (platformNow == SupportedPlatform.NINE_NINE && !acceptVisible) ocrScreenFor99(pkg)
        val curOffer = app.rideState.ui.value.offer
        if (curOffer?.source == RideSource.ACCESSIBILITY && acceptVisible &&
            RideScreenClassifier.isOfferScreen(text, platformNow) && fareVisible(text, curOffer.fare)
        ) {
            lastPresentAt = System.currentTimeMillis()
            app.rideState.renewOfferLease()
        }

        // Tela de corrida em andamento (depois de aceitar) confirma a corrida aceita.
        acceptTracker.onScreenText(text)

        if (text == lastText) return
        lastText = text

        // Publica o texto bruto capturado para o painel de diagnóstico do app.
        app.rideState.publishCapture(text, pkg)
        if (platformNow == SupportedPlatform.NINE_NINE && text.contains("R$")) {
            app.rideState.publishOfferCandidate(text)
        }

        scope.launch { handleText(text, pkg, acceptVisible) }
    }

    private suspend fun handleText(text: String, pkg: String, acceptVisible: Boolean) {
        val store = app.rideState
        val gen = generation.get()
        store.setAnalyzing(true)

        val settings = latestSettings ?: app.settingsRepository.settings.first()
        val regions = latestRegions ?: app.regionRepository.regions.first()

        val platform = resolvePlatform(pkg)

        // Só tenta interpretar se a tela parece mesmo uma oferta. Evita limpar o
        // painel toda vez que o motorista abre um mapa ou o histórico — mas,
        // se a oferta LIDA PELO SERVIÇO desapareceu da tela, o card/painel some junto.
        // Só vale como oferta o card do Uber/99 com botão Aceitar realmente visível.
        // Uma leitura sem oferta NÃO encerra o card aqui: pode ser só um instante em que a árvore
        // de acessibilidade está sendo reconstruída. O vigia encerra se a ausência persistir.
        if (!acceptVisible || !RideScreenClassifier.isOfferScreen(text, platform)) {
            if (platform == SupportedPlatform.NINE_NINE) {
                store.publishCaptureNote(
                    "99 lida, mas descartada: " +
                        if (!acceptVisible) "não achei o botão Aceitar na tela" else "a tela não parece uma oferta"
                )
            }
            store.setAnalyzing(false)
            return
        }

        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, platform)
        Log.d(TAG, "Tela de oferta pkg=$pkg parse=${offer != null}")
        if (offer == null) {
            if (platform == SupportedPlatform.NINE_NINE) {
                store.publishCaptureNote("99 lida, mas descartada: não consegui extrair valor/distância do texto")
            }
            store.setAnalyzing(false)
            return
        }
        if (platform == SupportedPlatform.NINE_NINE) store.publishCaptureNote("99: oferta reconhecida")

        // MESMA oferta que já está no card (o texto muda a cada segundo por causa do cronômetro):
        // só mantém o card vivo. Não republica, não troca o id, não reinicia nada.
        val shown = store.ui.value.offer
        if (shown != null && shown.source == RideSource.ACCESSIBILITY && signature(shown) == signature(offer)) {
            lastPresentAt = System.currentTimeMillis()
            store.renewOfferLease()
            store.setAnalyzing(false)
            return
        }

        // Oferta já encerrada (tempo máximo/clique/app trocado): não reaparece como fantasma.
        if (signature(offer) == capSig && System.currentTimeMillis() < capUntil) {
            store.setAnalyzing(false)
            return
        }

        // Oferta escondida de propósito (clique ou saída antecipada) e ainda na tela: não reexibe.
        val sup = suppressedFare
        if (sup != null) {
            if (offer.fare == sup) { store.setAnalyzing(false); return }
            suppressedFare = null   // outra oferta chegou: libera
        }

        // Descarta duplicidade (o evento dispara várias vezes para a mesma oferta)
        if (!RideOfferValidator.isValidAndNew(offer)) {
            store.setAnalyzing(false)
            return
        }

        // Região: converte embarque e destino em coordenadas EM PARALELO e só espera um instante
        // (endereço já conhecido volta do cache na hora). Se demorar, o card aparece JÁ
        // ("Verificando região…") com R$/km e R$/hora, e a região entra quando chegar — o card
        // nunca fica preso esperando endereço (antes podia esperar até 2 s a cada oferta).
        val hasActiveRegion = regions.any { it.active }
        val svc = this
        val pickupJob = scope.async {
            offer.pickupText?.takeIf { hasActiveRegion && settings.checkPickupRegion }
                ?.let { GeoCoderHelper.geocode(svc, it, regions) }
        }
        val destJob = scope.async {
            offer.destinationText?.takeIf { hasActiveRegion && settings.checkDestinationRegion }
                ?.let { GeoCoderHelper.geocode(svc, it, regions) }
        }
        val quick = withTimeoutOrNull(GEOCODE_WAIT_MS) { pickupJob.await() to destJob.await() }

        // A oferta pode ter sumido enquanto esta leitura processava: não republica oferta velha.
        if (generation.get() != gen) {
            pickupJob.cancel(); destJob.cancel()
            store.setAnalyzing(false)
            return
        }
        val stillResolving = quick == null
        lastPresentAt = System.currentTimeMillis()
        store.renewOfferLease()
        acceptTracker.onOfferShown(offer)
        store.publish(offer, settings, regions, quick?.first, quick?.second, resolvingPlaces = stillResolving)

        if (stillResolving) {
            scope.launch {
                val pickup = pickupJob.await()
                val dest = destJob.await()
                if (generation.get() == gen) {
                    store.applyRegionCheck(offer.id, settings, regions, pickup, dest)
                }
            }
        }
    }

    /**
     * Remove a oferta do estado apenas quando ela veio DA LEITURA automática.
     * Ofertas digitadas manualmente ou vindas de OCR permanecem até o
     * motorista limpá-las.
     */
    private fun clearIfServiceOffer(store: com.copilotodriverpro.state.RideStateStore) {
        if (store.ui.value.offer?.source == RideSource.ACCESSIBILITY) {
            store.clear()
            lastText = ""   // permite a MESMA oferta reaparecer depois
            // Sem isto, uma oferta nova com os MESMOS valores da anterior era descartada
            // como duplicada e o popup nunca reaparecia.
            RideOfferValidator.reset()
        }
    }

    /**
     * Percorre a árvore de nós e junta os textos visíveis. Limite alto o bastante
     * para telas densas de app de transporte, com guarda contra ciclos.
     */
    /** Todas as janelas abertas do pacote (card em janela própria + tela principal). */
    private fun rootsFor(pkg: String): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching {
            windows.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
        }.getOrDefault(emptyList())
        if (fromWindows.isNotEmpty()) return fromWindows
        return listOfNotNull(rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg })
    }

    /** Textos do botão tocado e dos filhos dele (poucos nós: roda na thread de eventos). */
    private fun clickedNodeTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val out = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < CLICK_NODES) {
            val n = queue.removeFirst()
            visited++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** Resultado de UMA passada na árvore: texto em ordem visual + botão Aceitar visível. */
    private class ScreenRead(val text: String, val acceptVisible: Boolean)

    /**
     * Percorre a árvore UMA vez só (antes eram duas passadas — uma para o texto e outra para o
     * botão Aceitar — e cada nó é uma chamada ao sistema). O texto sai em ordem visual (de cima
     * para baixo, esquerda para a direita), porque a busca em largura não segue a tela e selos
     * como "Viagem longa (mais de 30 min)" chegavam antes do embarque, trocando embarque/viagem.
     */
    private fun readScreen(roots: List<AccessibilityNodeInfo>, platform: SupportedPlatform): ScreenRead {
        class Piece(val top: Int, val left: Int, val text: String)

        val pieces = ArrayList<Piece>()
        val seen = HashSet<String>()
        val dm = resources.displayMetrics
        val rect = android.graphics.Rect()
        var acceptVisible = false

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++

                n.getBoundsInScreen(rect)
                val t = n.text?.toString()
                val d = n.contentDescription?.toString()
                if (!t.isNullOrBlank() && seen.add(t)) pieces.add(Piece(rect.top, rect.left, t))
                if (!d.isNullOrBlank() && d.length <= 200 && seen.add("d:$d")) pieces.add(Piece(rect.top, rect.left, d))

                if (!acceptVisible && n.isVisibleToUser &&
                    (RideScreenClassifier.isAcceptText(t, platform) || RideScreenClassifier.isAcceptText(d, platform)) &&
                    !rect.isEmpty && rect.bottom > 0 && rect.top < dm.heightPixels &&
                    rect.right > 0 && rect.left < dm.widthPixels
                ) acceptVisible = true

                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        val sb = StringBuilder()
        pieces.sortedWith(compareBy<Piece>({ it.top }, { it.left })).forEach { sb.append(it.text).append('\n') }
        return ScreenRead(sb.toString(), acceptVisible)
    }

    override fun onInterrupt() {
        // Nenhum gesto é realizado pelo serviço; nada a interromper.
    }

    companion object {
        private const val TAG = "Copiloto"
        // Quanto o card espera os endereços virarem coordenadas antes de aparecer.
        private const val GEOCODE_WAIT_MS = 150L
        // Intervalo mínimo entre leituras da tela (a mais recente sempre é lida; nada é descartado).
        private const val THROTTLE_MS = 200L
        // Frequência com que o vigia confere se a oferta ainda está na tela.
        private const val WATCHDOG_MS = 200L
        // Intervalo da varredura ativa de janelas quando não há card no ar.
        private const val SCAN_MS = 500L
        // Intervalo mínimo entre capturas de tela para OCR da 99. O Android recusa takeScreenshot com
        // menos de ~1 s entre pedidos (com 800 ms, 1 de cada 2 capturas era recusada).
        private const val OCR_MS = 1_050L
        // Por quanto tempo uma leitura de OCR bem-sucedida mantém o card vivo.
        private const val OCR_PRESENT_MS = 2_000L
        // Tolerância antes de dar a oferta como encerrada (evita piscar em leitura ruim isolada).
        private const val ABSENT_GRACE_MS = 700L
        // Só trava de segurança: o card fica enquanto a oferta estiver na tela, e some junto.
        private const val MAX_OFFER_MS = 90_000L
        // Quanto tempo a mesma oferta encerrada fica proibida de reaparecer.
        private const val SUPPRESS_MS = 90_000L
        // Alta de propósito: a árvore do Uber Driver costuma ser profunda.
        private const val MAX_NODES = 4000
        // Nós lidos no botão tocado (o clique chega na thread de eventos: tem que ser rápido).
        private const val CLICK_NODES = 12

        @Volatile
        private var latestSettings: com.copilotodriverpro.settings.DriverSettings? = null

        fun bindSettings(s: com.copilotodriverpro.settings.DriverSettings) {
            latestSettings = s
        }
    }
}
