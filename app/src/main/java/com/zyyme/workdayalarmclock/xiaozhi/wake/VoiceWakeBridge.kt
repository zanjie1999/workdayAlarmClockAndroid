package com.zyyme.workdayalarmclock.xiaozhi.wake

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
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

    /**
     * 进程内唯一一份插件 ClassLoader。
     *
     * 不能每次 start 都新建：so 一旦被某个 ClassLoader 打开就不会卸载（sherpa 只加载不释放），
     * 同一路径再去另一个 ClassLoader 的 namespace 里 load 会被 linker 直接拒掉
     * （`already opened by ClassLoader 0x..; can't open in ClassLoader 0x..`）。
     * 换设置要重建引擎时必然走到这里，所以 loader 必须缓存；缓存住之后插件类只初始化一次，
     * System.loadLibrary 也就不会再被调用。
     */
    @Volatile
    private var loader: DexClassLoader? = null

    /** 缓存 loader 对应的插件路径，插件升级换了安装目录就得重建 */
    @Volatile
    private var loaderKey: String? = null

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
            val created = pluginClassLoader(appContext, info).loadClass(ENGINE_CLASS)
                .newInstance() as WakeWordHost
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

    /**
     * 取进程内唯一的插件 ClassLoader。插件安装路径没变就复用，只有插件升级
     * 导致 sourceDir / nativeLibraryDir 变化才重建（见 [loader] 的注释）。
     *
     * parent 显式指定成宿主 ClassLoader，插件里的实现类强转 [WakeWordHost] 时
     * 才会解析到宿主那份接口；librarySearchPath 传插件的 nativeLibraryDir，
     * 插件里的 System.loadLibrary 才能找到 so。
     */
    private fun pluginClassLoader(context: Context, info: ApplicationInfo): DexClassLoader {
        val key = "${info.sourceDir}|${info.nativeLibraryDir}"
        val cached = loader
        if (cached != null && key == loaderKey) return cached
        val dexOut = if (Build.VERSION.SDK_INT >= 21) context.codeCacheDir else context.cacheDir
        val outDir = File(dexOut, "voicewake")
        if (!outDir.exists()) outDir.mkdirs()
        val created = DexClassLoader(
            info.sourceDir,
            outDir.absolutePath,
            info.nativeLibraryDir,
            WakeWordHost::class.java.classLoader
        )
        loader = created
        loaderKey = key
        return created
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
