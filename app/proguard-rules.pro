# ── Compose ────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── org.json (AdDetector / ViewFingerprintCache) ────────────────────
-keep class org.json.** { *; }

# ── Kotlin serialization ────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── AccessibilityService ────────────────────────────────────────────
-keep class me.geekabe.aitiao.AitiaoAccessibilityService { *; }
-keep class me.geekabe.aitiao.AitiaoAccessibilityService$StopReceiver { *; }

# ── Data classes (Compose state reflection) ─────────────────────────
-keep class me.geekabe.aitiao.AppInfo { *; }
-keep class me.geekabe.aitiao.AdPageFingerprint { *; }
-keep class me.geekabe.aitiao.AdResult { *; }
-keep class me.geekabe.aitiao.AiConfig { *; }
-keep class me.geekabe.aitiao.LogCollector$Entry { *; }

# ── ViewFingerprintCache (reflection via object) ────────────────────
-keep class me.geekabe.aitiao.ViewFingerprintCache { *; }
