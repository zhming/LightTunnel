package lighttunnel.web.server

import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.junit.Before
import org.junit.Test

class WebServerTest {
    private lateinit var server: WebServer

    @Before
    fun before() {
        server = WebServer(
            NioEventLoopGroup(),
            NioEventLoopGroup(),
            null,
            80
        ).router {
            route("/") {
                DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer("Hello", Charsets.UTF_8)
                )
            }
        }
    }

    @Test
    fun start() {
        server.start()
        Thread.currentThread().join()
    }
}