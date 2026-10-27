# Regras do release (R8 ativado). Se algum recurso sumir em produção, veja o Logcat e
# acrescente um -keep aqui.

# ML Kit (OCR local) usa reflexão/serviços internos
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room (KSP gera o código, mas as entidades são lidas por nome)
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Serviços do Android declarados no manifesto
-keep class com.copilotodriverpro.accessibility.CopilotAccessibilityService { *; }
-keep class com.copilotodriverpro.overlay.FloatingPanelService { *; }
-keep class com.copilotodriverpro.floating.ScreenCaptureService { *; }

# Modelos persistidos em JSON/DataStore
-keep class com.copilotodriverpro.models.** { *; }
-keep class com.copilotodriverpro.settings.** { *; }

# Útil para ler erros do Logcat no release
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
