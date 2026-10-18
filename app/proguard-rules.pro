# AsmrOne 正式版混淆规则
#
# 本工程刻意"零反射"：接口用 org.json 手工解析、没有任何 @Serializable 注解处理器、
# 也没有 ViewBinding/DataBinding。所以这里**几乎不需要 keep** ——
# 下面的规则都是"防止被裁掉/被优化改坏"的针对性保护，而不是兜底大网。

# ── 崩溃栈可读性 ───────────────────────────────────────────────
# 保留行号信息，正式包的崩溃栈才能定位到源码行（不保留源码文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 异常类名不参与混淆：
# MarkFailure 与 AsmrOneApi 的日志用 `e.javaClass.simpleName` 显示异常类型，
# 混淆后变成 `a.b.c` 就失去了排查价值。
-keepnames class * extends java.lang.Exception
-keepnames class * extends java.lang.Throwable

# 泛型与注解在运行时不需要，但保留重命名后的签名能让部分反射型库正常工作；
# 本工程没有这类库，这里保留 Annotation 是为了 Coil/Haze/Media3 的 consumer 规则不冲突
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ── Android 组件 ──────────────────────────────────────────────
# Activity 由系统按类名实例化（manifest 里引用了 .MainActivity）
-keep class com.asmrone.app.MainActivity { *; }
# Application 同理
-keep class com.asmrone.app.AsmrOneApplication { *; }
# FileProvider 由 framework 通过 authority 找到，类名在 manifest 里
-keep class androidx.core.content.FileProvider { *; }

# ── 单例 / 顶层对象 ──────────────────────────────────────────
# 这些 object 只在包内被引用，R8 会正常内联；但它们的 <clinit> 里有 Regex 与静态表，
# 若被裁掉可能导致"看起来未使用、实际由 Compose 反应式路径触发"的问题。
# 用 @Keep 语义上更精确，但这里直接按包保留可控性更好。
-keep class com.asmrone.app.data.prefs.** { *; }
-keep class com.asmrone.app.data.cache.TrackJson { *; }
-keep class com.asmrone.app.data.cache.TrackTreeStore { *; }
-keep class com.asmrone.app.data.cache.SyncCacheStore$* { *; }
-keep class com.asmrone.app.data.lyrics.LyricsLoader { *; }

# ── 第三方（它们的 consumer 规则已覆盖大部分，这里补齐易被误裁的） ──
# Haze 的 state 在 Compose 里以类型参数出现
-keep class dev.chrisbanes.haze.** { *; }
# Media3 通过反射探测可用的音频/视频解码器
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }

# ── 调试期网络日志：正式包禁用它 ─────────────────────────────
# HttpLog 在正式包里本来就不会被启用（gated by FLAG_DEBUGGABLE），
# 但保留类本身没有意义，交给 R8 裁掉即可，这里不 keep。

# ── org.json（平台自带） ────────────────────────────────────
# 不需要 keep：它由 framework 提供，不在 apk 的 dex 里
