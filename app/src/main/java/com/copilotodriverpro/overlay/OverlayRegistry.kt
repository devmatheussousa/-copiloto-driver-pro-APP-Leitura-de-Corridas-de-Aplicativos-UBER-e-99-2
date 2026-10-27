package com.copilotodriverpro.overlay

import android.graphics.Rect
import android.view.View
import java.lang.ref.WeakReference

/**
 * Lista das janelas que o PRÓPRIO app desenha por cima do Uber/99 (card de análise, selo de
 * região, botão/painel flutuante).
 *
 * Serve para o leitor por imagem (OCR da 99): ele tira uma foto da tela inteira e essa foto inclui
 * o nosso card ("R$/hora R$ 46,40"...). Sem tapar essas áreas, o OCR lia os NOSSOS números como se
 * fossem da corrida e o card passava a mostrar valores que subiam a cada leitura.
 */
object OverlayRegistry {

    private val views = ArrayList<WeakReference<View>>()

    @Synchronized
    fun register(view: View) {
        views.removeAll { it.get() == null || it.get() === view }
        views.add(WeakReference(view))
    }

    @Synchronized
    fun unregister(view: View) {
        views.removeAll { it.get() == null || it.get() === view }
    }

    /** Retângulos (em pixels da tela) das janelas do app que estão visíveis agora. */
    @Synchronized
    fun screenRects(): List<Rect> {
        val out = ArrayList<Rect>()
        val loc = IntArray(2)
        for (ref in views) {
            val v = ref.get() ?: continue
            if (!v.isShown || v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            out.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
        }
        return out
    }
}
