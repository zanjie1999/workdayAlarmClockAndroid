package com.zyyme.workdayalarmclock.xiaozhi.wake

import android.content.Context
import android.os.Build
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.MeUpdater
import com.zyyme.workdayalarmclock.wake.WakeWordHost
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 宿主侧的语音唤醒加载器。
 *
 * 插件是独立 APK（minSdk 21），主包不依赖它的任何代码，只在运行时用 DexClassLoader 加载：
 * - parent 显式指定成宿主的 ClassLoader，这样插件里的实现类强转成 [WakeWordHost] 时
 *   解析到的是宿主那份接口，不会因为两个 ClassLoader 各有一份而 ClassCastException；
 * - librarySearchPath 传插件的 nativeLibraryDir，插件里的 System.loadLibrary 才能找到 so；
 * - 模型通过插件自己的 AssetManager 读取（插件 APK 的 assets/kws）。
 */
object VoiceWakeBridge {

    /** 插件里实现 [WakeWordHost] 的类 */
    private const val ENGINE_CLASS = "com.zyyme.workdayalarmclock.voicewake.WakeWordEngine"

    @Volatile
    private var engine: WakeWordHost? = null

    @Volatile
    private var lastError: String? = null

    fun isPluginInstalled(context: Context): Boolean = MeUpdater.isPluginInstalled(context)

    /**
     * 加载插件并开始监听。失败返回 false，原因可从 [lastError] 取。
     * 已经加载过就直接复用，只更新关键词和阈值。
     */
    fun start(
        context: Context,
        keywords: String,
        threshold: Float,
        onDetected: (String) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean {
        lastError = null
        val appContext = context.applicationContext
        val listener = object : WakeWordHost.Listener {
            override fun onWake(keyword: String) {
                onDetected(keyword)
            }

            override fun onError(message: String) {
                onFailure(message)
            }
        }
        val existing = engine
        if (existing != null) {
            // 已经加载过就直接复用；关键词/阈值改过的话调用方会先 release()
            existing.start()
            return true
        }
        return try {
            val info = pluginApplicationInfo(appContext) ?: run {
                lastError = "插件未安装"
                return false
            }
            val parent = WakeWordHost::class.java.classLoader
            val dexOut = if (Build.VERSION.SDK_INT >= 21) appContext.codeCacheDir else appContext.cacheDir
            File(dexOut, "voicewake").let { if (!it.exists()) it.mkdirs() }
            val loader = DexClassLoader(
                info.sourceDir,
                File(dexOut, "voicewake").absolutePath,
                info.nativeLibraryDir,
                parent
            )
            val created = loader.loadClass(ENGINE_CLASS).newInstance() as WakeWordHost
            val assets = pluginAssets(appContext) ?: run {
                lastError = "读不到插件资源"
                return false
            }
            created.init(assets, keywords, threshold, listener)
            created.start()
            engine = created
            true
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** 宿主采集线程每 20ms 调一次，内部只做入队，不会阻塞 */
    fun acceptPcm(samples: ShortArray, length: Int) {
        engine?.acceptPcm(samples, length)
    }

    fun stop() {
        try {
            engine?.stop()
        } catch (ignored: Throwable) {
        }
    }

    fun release() {
        try {
            engine?.release()
        } catch (ignored: Throwable) {
        }
        engine = null
    }

    fun errorMessage(): String? = lastError

    private fun pluginApplicationInfo(context: Context) = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(MeUpdater.PLUGIN_PKG, 0).applicationInfo
    } catch (e: Exception) {
        null
    }

    private fun pluginAssets(context: Context) = try {
        context.createPackageContext(
            MeUpdater.PLUGIN_PKG,
            Context.CONTEXT_IGNORE_SECURITY
        ).assets
    } catch (e: Exception) {
        lastError = "插件资源不可用：${e.message}"
        null
    }
}
