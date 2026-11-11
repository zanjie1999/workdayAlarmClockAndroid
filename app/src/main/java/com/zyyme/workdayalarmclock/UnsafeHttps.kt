package com.zyyme.workdayalarmclock

import android.os.Build
import android.util.Log
import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Socket
import java.net.URLConnection
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 建 SSLContext 时按新到旧尝试，老设备没有 TLSv1.2 就退到 TLSv1.1 / TLSv1 */
private val TLS_CONTEXT_PROTOCOLS = arrayOf("TLSv1.2", "TLSv1.1", "TLS")

/** 真正在 socket 上打开的顺序：能协商高版本就用高版本，不行再往下降 */
private val TLS_SOCKET_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")

/** Conscrypt 建上下文用哪个算法名 */
private const val CONSCRYPT_CONTEXT_ALGORITHM = "TLSv1.2"

/**
 * 老系统（Android 4.4 及以下）访问现代 HTTPS 站点时的兜底。
 *
 * 这些设备自带的东西太旧：根证书里没有 ISRG Root X1 之类的新根，TLS 1.2 不在默认协议里，
 * 更致命的是只提供 CBC 套件、一个 AEAD（GCM）都没有，而 GitHub / Cloudflare 早已只收 AEAD。
 * 所以自动更新、歌词、待办、通知转发这些接口要么证书验证不过，要么握手直接被拒。
 *
 * 处理方式三层：
 * 1. 关掉证书与主机名校验；
 * 2. 每个 socket 上打开设备支持的所有 TLS 协议（含 TLS 1.3），并补齐 GCM 套件；
 * 3. 老系统额外装一份内置的 Conscrypt，把整套 TLS 实现换成现代的（新系统用自带的不动）。
 *
 * 只用于本程序访问的少量公共接口，是有意为之的降级，不要拿去访问敏感数据。
 */
object UnsafeHttps {
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext by lazy { createContext() }

    /**
     * 4.4 这类老系统的 JSSE 只提供 CBC 套件，而 GitHub / Cloudflare 只收 AEAD，
     * 光打开 TLSv1.2 也没用（实测 GCM 套件 0 个）。所以老系统换成内置的 Conscrypt，
     * 新系统继续用平台自带的，少背 1.6MB 和额外风险。
     */
    private val conscryptProvider: Provider? by lazy { installConscrypt() }

    private fun installConscrypt(): Provider? {
        if (Build.VERSION.SDK_INT >= 21) return null
        return try {
            val provider = Conscrypt.newProvider()
            if (Security.getProvider(provider.name) == null) {
                Security.insertProviderAt(provider, 1)
            }
            // 立刻做一次真实初始化：native 库加载不了就在这里暴露，不会拖到第一次请求
            SSLContext.getInstance(CONSCRYPT_CONTEXT_ALGORITHM, provider).apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }.socketFactory.createSocket().close()
            provider
        } catch (e: Throwable) {
            Log.w("UnsafeHttps", "Conscrypt 不可用（${e.message}），退回系统 TLS")
            null
        }
    }

    private fun createContext(): SSLContext {
        val provider = conscryptProvider
        if (provider != null) {
            try {
                return SSLContext.getInstance(CONSCRYPT_CONTEXT_ALGORITHM, provider).apply {
                    init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                }
            } catch (e: Exception) {
                Log.w("UnsafeHttps", "Conscrypt 上下文创建失败: ${e.message}")
            }
        }
        var lastError: Exception? = null
        for (protocol in TLS_CONTEXT_PROTOCOLS) {
            try {
                return SSLContext.getInstance(protocol).apply {
                    init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("没有可用的 TLS 协议", lastError)
    }

    /** 不校验证书、且会打开 TLSv1.2 的 socket 工厂 */
    private val sslSocketFactory: SSLSocketFactory by lazy {
        ProtocolSocketFactory(sslContext.socketFactory)
    }

    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    @Volatile
    private var installed = false

    /**
     * 把进程默认的 HTTPS 校验换成宽松版本。
     * 设置之后，任何 HttpsURLConnection 都不再校验证书，
     * 包括 URLConnection 内部跟随重定向时自己新建的连接，所以不用逐个 connect 去兜。
     * 幂等，随便调。
     */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(sslSocketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
            } catch (e: Exception) {
                Log.w("UnsafeHttps", "设置默认 HTTPS 配置失败: ${e.message}")
            }
            installed = true
        }
        Log.d("UnsafeHttps", "HTTPS 已放宽校验，${capabilityText()}")
    }

    /**
     * 本机的 TLS 能力，握手失败时看这行就知道是协议不够还是套件不够。
     */
    fun capabilityText(): String = try {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            val protocols = socket.supportedProtocols
            val gcmSuites = socket.supportedCipherSuites.count { it.contains("_GCM_") }
            "TLS 提供者 ${sslContext.provider.name}，支持 ${protocols.joinToString("/")}，GCM 套件 $gcmSuites 个"
        } finally {
            socket.close()
        }
    } catch (e: Exception) {
        "TLS 能力探测失败: ${e.message}"
    }

    /** 单个连接上也设置一遍，顺带确保全局默认已经装好 */
    fun configure(connection: URLConnection) {
        install()
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = sslSocketFactory
            connection.hostnameVerifier = hostnameVerifier
        }
    }
}

/**
 * 包一层 socket 工厂：委托给系统的工厂建 socket，建好之后把 [UnsafeHttps] 里列的
 * 协议都打开。Android 4.4 的 SSLContext("TLS") 默认只启用 TLSv1，
 * 而 GitHub 之类早就只收 TLS 1.2 了。
 */
private class ProtocolSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = enableTls(delegate.createSocket())

    override fun createSocket(host: String?, port: Int): Socket =
        enableTls(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        enableTls(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        enableTls(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        enableTls(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        enableTls(delegate.createSocket(socket, host, port, autoClose))

    private fun enableTls(socket: Socket): Socket {
        if (socket is SSLSocket) {
            try {
                val supported = socket.supportedProtocols
                val wanted = TLS_SOCKET_PROTOCOLS.filter { supported.contains(it) }
                if (wanted.isNotEmpty()) {
                    socket.enabledProtocols = wanted.toTypedArray()
                }
            } catch (e: Exception) {
                Log.w("UnsafeHttps", "设置 TLS 协议失败: ${e.message}")
            }
            try {
                // GitHub 这类站点只收 AEAD 套件，老设备默认启用的列表里可能没有 GCM，
                // 少了它就算协议对上也会被服务器拒掉，所以这里补上。
                val enabled = socket.enabledCipherSuites
                if (enabled.none { it.contains("_GCM_") }) {
                    val gcm = socket.supportedCipherSuites.filter { it.contains("_GCM_") }
                    if (gcm.isNotEmpty()) {
                        socket.enabledCipherSuites = (enabled.toList() + gcm).distinct().toTypedArray()
                    }
                }
            } catch (e: Exception) {
                Log.w("UnsafeHttps", "设置 GCM 套件失败: ${e.message}")
            }
        }
        return socket
    }
}
