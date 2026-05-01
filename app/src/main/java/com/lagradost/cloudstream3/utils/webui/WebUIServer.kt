package com.lagradost.cloudstream3.utils.webui

import com.lagradost.cloudstream3.mvvm.logError
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object WebUIServer {
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start(port: Int = 8945) {
        if (server != null) return
        
        scope.launch {
            try {
                server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(ContentNegotiation) {
                        jackson {}
                    }
                    routing {
                        get("/") {
                            call.respondText(getHtml(), io.ktor.http.ContentType.Text.Html)
                        }
                        get("/api/current_stream") {
                            val data = CurrentStreamManager.currentStream.value
                            if (data != null) {
                                call.respond(data)
                            } else {
                                call.respond(mapOf("error" to "No stream active"))
                            }
                        }
                        get("/play.m3u") {
                            val data = CurrentStreamManager.currentStream.value
                            val linkIndex = call.parameters["index"]?.toIntOrNull() ?: 0
                            val link = data?.links?.getOrNull(linkIndex)
                            
                            if (link != null) {
                                val m3u = """
                                    #EXTM3U
                                    #EXTINF:-1,${data.title ?: "Stream"}
                                    ${link.url}
                                """.trimIndent()
                                call.respondText(m3u, io.ktor.http.ContentType.parse("audio/x-mpegurl"))
                            } else {
                                call.respondText("Link not found", status = io.ktor.http.HttpStatusCode.NotFound)
                            }
                        }
                    }
                }.start(wait = false)
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
        server = null
    }

    private fun getHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Cloudstream WebUI</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: sans-serif; background: #121212; color: white; padding: 20px; text-align: center; }
                    .card { background: #1e1e1e; padding: 20px; border-radius: 10px; display: inline-block; max-width: 400px; width: 100%; }
                    img { max-width: 100%; border-radius: 5px; }
                    .btn { display: block; background: #e50914; color: white; padding: 10px; margin: 10px 0; text-decoration: none; border-radius: 5px; cursor: pointer; border: none; font-size: 16px; }
                    .btn-secondary { background: #333; }
                    .links { text-align: left; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1 id="title">Loading...</h1>
                    <img id="poster" src="" style="display:none;">
                    <div id="info"></div>
                    <div id="links" class="links"></div>
                </div>

                <script>
                    async function update() {
                        try {
                            const res = await fetch('/api/current_stream');
                            const data = await res.json();
                            if (data.error) {
                                document.getElementById('title').innerText = 'No Stream Active';
                                document.getElementById('poster').style.display = 'none';
                                document.getElementById('links').innerHTML = '';
                                return;
                            }
                            document.getElementById('title').innerText = data.title;
                            if (data.poster) {
                                document.getElementById('poster').src = data.poster;
                                document.getElementById('poster').style.display = 'block';
                            }
                            document.getElementById('info').innerText = (data.season ? 'S' + data.season : '') + (data.episode ? ' E' + data.episode : '');
                            
                            let linksHtml = '<h3>Play on Desktop:</h3>';
                            data.links.forEach((link, index) => {
                                linksHtml += `<a class="btn" href="/play.m3u?index=${'$'}{index}">Play ${'$'}{link.name} (${'$'}{link.quality}p)</a>`;
                                linksHtml += `<button class="btn btn-secondary" onclick="window.location.href='vlc://${'$'}{link.url}'">Open in VLC</button>`;
                                linksHtml += `<button class="btn btn-secondary" onclick="window.location.href='potplayer://${'$'}{link.url}'">Open in PotPlayer</button>`;
                            });
                            document.getElementById('links').innerHTML = linksHtml;
                        } catch (e) {
                            console.error(e);
                        }
                    }
                    setInterval(update, 5000);
                    update();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
