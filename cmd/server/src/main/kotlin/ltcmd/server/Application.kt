@file:Suppress("DuplicatedCode")

package ltcmd.server

import lighttunnel.BuildConfig
import lighttunnel.cmd.AbstractApplication
import lighttunnel.logger.LoggerFactory
import lighttunnel.logger.loggerDelegate
import lighttunnel.server.TunnelRequestInterceptor
import lighttunnel.server.TunnelServer
import lighttunnel.server.http.HttpFd
import lighttunnel.server.http.HttpPlugin
import lighttunnel.server.http.HttpRequestInterceptor
import lighttunnel.server.tcp.TcpFd
import lighttunnel.util.SslContextUtil
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.Options
import org.apache.log4j.Level
import org.apache.log4j.helpers.OptionConverter
import org.ini4j.Ini
import org.ini4j.Profile
import java.io.File

class Application : AbstractApplication() {
    private val logger by loggerDelegate()
    private var tunnelServer: TunnelServer? = null
    private val onTcpTunnelStateListener = object : TunnelServer.OnTcpTunnelStateListener {
        override fun onConnected(fd: TcpFd) {
            logger.info("onConnected: {}", fd)
        }

        override fun onDisconnect(fd: TcpFd) {
            logger.info("onDisconnect: {}", fd)
        }
    }
    private val onHttpTunnelStateListener = object : TunnelServer.OnHttpTunnelStateListener {
        override fun onConnected(fd: HttpFd) {
            logger.info("onConnected: {}", fd)
        }

        override fun onDisconnect(fd: HttpFd) {
            logger.info("onDisconnect: {}", fd)
        }
    }

    override val options: Options
        get() = Options().apply {
            addOption("h", "help", false, "帮助信息")
            addOption("v", "version", false, "版本信息")
            addOption("c", "config", true, "配置文件, 默认为lts.ini")
        }

    override fun main(commandLine: CommandLine) {
        if (commandLine.hasOption("h")) {
            printUsage()
            return
        }
        if (commandLine.hasOption("v")) {
            System.out.printf("%s%n", BuildConfig.VERSION_NAME)
            return
        }
        val configFilePath = commandLine.getOptionValue("c") ?: "lts.ini"
        val ini = Ini()
        ini.load(File(configFilePath))
        val basic = ini["basic"] ?: return
        loadLogConf(basic)
        tunnelServer = createTunnelServer(basic)
        tunnelServer?.start()
    }

    private fun createTunnelServer(basic: Profile.Section): TunnelServer {
        val tunnelRequestInterceptor = createTunnelRequestInterceptor(basic)
        val tunnelServiceArgs = createTunnelServiceArgs(basic, tunnelRequestInterceptor)
        val sslTunnelServiceArgs = createSslTunnelServiceArgs(basic, tunnelRequestInterceptor)
        val httpServerArgs = createHttpServerArgs(basic, HttpRequestInterceptor.defaultImpl)
        val httpsServerArgs = createHttpsServerArgs(basic, HttpRequestInterceptor.defaultImpl)
        val webServerArgs = createWebServerArgs(basic)
        return TunnelServer(
            bossThreads = basic["boss_threads"].asInt() ?: -1,
            workerThreads = basic["worker_threads"].asInt() ?: -1,
            tunnelServiceArgs = tunnelServiceArgs,
            sslTunnelServiceArgs = sslTunnelServiceArgs,
            httpServerArgs = httpServerArgs,
            httpsServerArgs = httpsServerArgs,
            webServerArgs = webServerArgs,
            onTcpTunnelStateListener = onTcpTunnelStateListener,
            onHttpTunnelStateListener = onHttpTunnelStateListener
        )
    }

    private fun createTunnelRequestInterceptor(basic: Profile.Section): TunnelRequestInterceptor {
        val authToken = basic["auth_token"]
        val allowPorts = basic["allow_ports"]
        return if (authToken != null || allowPorts != null) {
            TunnelRequestInterceptor.defaultImpl(authToken, allowPorts)
        } else {
            TunnelRequestInterceptor.emptyImpl
        }
    }

    private fun loadLogConf(basic: Profile.Section) {
        val logLevel = Level.toLevel(basic["log_level"], null) ?: Level.INFO
        val logFile = basic["log_file"]
        val logCount = basic["log_count"].asInt() ?: 3
        val logSize = basic["log_size"] ?: "1MB"
        LoggerFactory.configConsole(Level.OFF, names = *arrayOf(
            "io.netty",
            "org.ini4j",
            "org.slf4j",
            "org.json",
            "org.apache.commons.cli"
        ))
        LoggerFactory.configConsole(level = logLevel, conversionPattern = "%-d{yyyy-MM-dd HH:mm:ss} - [ %p ] %m%n")
        if (logFile != null) {
            LoggerFactory.configFile(
                level = logLevel,
                file = logFile,
                maxBackupIndex = logCount,
                maxFileSize = OptionConverter.toFileSize(logSize, 1)
            )
        }
    }

    private fun createTunnelServiceArgs(basic: Profile.Section, tunnelRequestInterceptor: TunnelRequestInterceptor): TunnelServer.TunnelServiceArgs {
        return TunnelServer.TunnelServiceArgs(
            bindAddr = basic["bind_addr"],
            bindPort = basic["bind_port"].asInt() ?: 5080,
            tunnelRequestInterceptor = tunnelRequestInterceptor
        )
    }

    private fun createSslTunnelServiceArgs(basic: Profile.Section, tunnelRequestInterceptor: TunnelRequestInterceptor): TunnelServer.SslTunnelServiceArgs {
        return TunnelServer.SslTunnelServiceArgs(
            bindAddr = basic["bind_addr"],
            bindPort = basic["ssl_bind_port"].asInt() ?: 5443,
            tunnelRequestInterceptor = tunnelRequestInterceptor,
            sslContext = try {
                val jks = basic["ssl_jks"] ?: "lts.jks"
                val storePassword = basic["ssl_key_password"] ?: "ltspass"
                val keyPassword = basic["ssl_store_password"] ?: "ltspass"
                SslContextUtil.forServer(jks, storePassword, keyPassword)
            } catch (e: Exception) {
                logger.warn("tunnel ssl used builtin jks.")
                SslContextUtil.forBuiltinServer()
            }
        )
    }

    private fun createHttpServerArgs(http: Profile.Section, httpRequestInterceptor: HttpRequestInterceptor): TunnelServer.HttpServerArgs {
        val pluginSfPaths = http["plugin_sf_paths"]?.split(',')
        val pluginSfHosts = http["plugin_sf_hosts"]?.split(',')
        var sfHttpPlugin: HttpPlugin? = null
        if (!pluginSfPaths.isNullOrEmpty() && !pluginSfHosts.isNullOrEmpty()) {
            sfHttpPlugin = HttpPlugin.staticFileImpl(
                paths = pluginSfPaths,
                hosts = pluginSfHosts
            )
        }
        return TunnelServer.HttpServerArgs(
            bindAddr = http["bind_addr"],
            bindPort = http["http_bind_port"].asInt(),
            httpRequestInterceptor = httpRequestInterceptor,
            httpPlugin = sfHttpPlugin
        )
    }

    private fun createHttpsServerArgs(https: Profile.Section, httpRequestInterceptor: HttpRequestInterceptor): TunnelServer.HttpsServerArgs {
        val pluginSfPaths = https["plugin_sf_paths"]?.split(',')
        val pluginSfHosts = https["plugin_sf_hosts"]?.split(',')
        var sfHttpPlugin: HttpPlugin? = null
        if (!pluginSfPaths.isNullOrEmpty() && !pluginSfHosts.isNullOrEmpty()) {
            sfHttpPlugin = HttpPlugin.staticFileImpl(
                paths = pluginSfPaths,
                hosts = pluginSfHosts
            )
        }
        return TunnelServer.HttpsServerArgs(
            bindAddr = https["bind_addr"],
            bindPort = https["https_bind_port"].asInt(),
            httpRequestInterceptor = httpRequestInterceptor,
            httpPlugin = sfHttpPlugin,
            sslContext = try {
                val jks = https["https_jks"] ?: "lts.jks"
                val storePassword = https["https_key_password"] ?: "ltspass"
                val keyPassword = https["https_store_password"] ?: "ltspass"
                SslContextUtil.forServer(jks, storePassword, keyPassword)
            } catch (e: Exception) {
                logger.warn("tunnel ssl used builtin jks.")
                SslContextUtil.forBuiltinServer()
            }
        )
    }

    private fun createWebServerArgs(web: Profile.Section): TunnelServer.WebServerArgs {
        return TunnelServer.WebServerArgs(
            bindAddr = web["bind_addr"],
            bindPort = web["web_bind_port"].asInt()
        )
    }

    private fun String?.asInt(default_: Int? = null): Int? {
        return try {
            this?.toInt()
        } catch (e: NumberFormatException) {
            return default_
        }
    }

}