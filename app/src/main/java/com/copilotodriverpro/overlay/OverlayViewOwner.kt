package com.copilotodriverpro.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Dono ÚNICO de Lifecycle + SavedState + ViewModelStore para uma ComposeView que vive
 * numa janela do WindowManager (fora de Activity).
 *
 * CORREÇÃO: a versão anterior criava um Lifecycle já em RESUMED e só depois chamava
 * performRestore(), que exige estado INITIALIZED e lança IllegalStateException. Como o
 * erro era engolido por runCatching, a janela do card simplesmente nunca era criada.
 * Ordem correta: attach -> restore (ainda INITIALIZED) -> só então avançar o lifecycle.
 */
internal class OverlayViewOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
