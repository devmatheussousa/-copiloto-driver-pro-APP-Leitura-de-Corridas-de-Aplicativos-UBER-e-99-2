package com.copilotodriverpro.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Atalhos para as telas do Xiaomi / POCO / Redmi (HyperOS e MIUI). Nesses aparelhos o sistema
 * bloqueia por padrão o que o Ninja Go precisa para ler as corridas em segundo plano:
 *  - "Configurações restritas" (app instalado fora da loja não consegue ligar a Acessibilidade);
 *  - "Início automático" desligado e economia de bateria "Otimizada" matam o leitor e o card.
 * Cada atalho tenta a tela própria da Xiaomi e, se ela não existir nesta versão, cai na tela do app.
 */
object XiaomiHelp {

    fun isXiaomiFamily(): Boolean {
        val ids = listOf(Build.MANUFACTURER, Build.BRAND).map { it.lowercase() }
        return ids.any { id -> listOf("xiaomi", "redmi", "poco").any { id.contains(it) } }
    }

    /** Informações do app: lá fica o menu ⋮ "Permitir configurações restritas" e as permissões. */
    fun openAppInfo(context: Context) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    /** Início automático (a lista de apps que podem abrir sozinhos). */
    fun openAutostart(context: Context) {
        val xiaomi = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        if (!start(context, xiaomi)) openAppInfo(context)
    }

    /** Economia de bateria do app (escolher "Sem restrições"). */
    fun openBatterySaver(context: Context) {
        val xiaomi = Intent().setComponent(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        ).putExtra("package_name", context.packageName).putExtra("package_label", "Ninja Go")
        if (!start(context, xiaomi)) Permissions.openBatterySettings(context)
    }

    /** true se a tela abriu; false se não existe/negou (quem chamou usa a alternativa). */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
