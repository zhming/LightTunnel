@file:Suppress("MemberVisibilityCanBePrivate")

package lighttunnel.client.connect

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.handler.ssl.SslContext
import lighttunnel.client.util.AK_TUNNEL_CONNECT_FD
import lighttunnel.logger.loggerDelegate
import lighttunnel.proto.ProtoMessage
import lighttunnel.proto.ProtoMessageType
import lighttunnel.proto.TunnelRequest
import java.util.concurrent.atomic.AtomicBoolean

class TunnelConnectFd(
    val serverAddr: String,
    val serverPort: Int,
    private val tunnelRequest: TunnelRequest,
    internal val sslContext: SslContext? = null
) {
    private val logger by loggerDelegate()
    private var connectChannelFuture: ChannelFuture? = null

    val originalTunnelRequest get() = tunnelRequest
    var finalTunnelRequest: TunnelRequest? = null
        internal set

    private val activeClosedFlag = AtomicBoolean(false)
    val isActiveClosed get() = activeClosedFlag.get()

    internal fun connect(bootstrap: Bootstrap, connectFailureCallback: (fd: TunnelConnectFd) -> Unit) {
        if (isActiveClosed) {
            logger.warn("This tunnel already closed.")
            return
        }
        @Suppress("RedundantSamConstructor")
        bootstrap.connect(serverAddr, serverPort)
            .also { connectChannelFuture = it }
            .addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    // 连接成功，向服务器发送请求建立隧道消息
                    val head = (finalTunnelRequest ?: originalTunnelRequest).toBytes()
                    future.channel().writeAndFlush(ProtoMessage(ProtoMessageType.REQUEST, head = head))
                    future.channel().attr(AK_TUNNEL_CONNECT_FD).set(this)
                } else {
                    connectFailureCallback.invoke(this)
                }
            })
    }

    internal fun close() {
        activeClosedFlag.set(true)
        connectChannelFuture?.apply {
            channel().attr(AK_TUNNEL_CONNECT_FD).set(null)
            channel().close()
        }
    }

    override fun toString(): String {
        return (finalTunnelRequest ?: originalTunnelRequest).toString(serverAddr)
    }


}