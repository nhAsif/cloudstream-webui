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
                                val allHeaders = link.getAllHeaders()
                                val userAgent = allHeaders["User-Agent"] ?: allHeaders["user-agent"]
                                val referer = allHeaders["Referer"] ?: allHeaders["referer"]

                                val m3u = StringBuilder().apply {
                                    appendLine("#EXTM3U")
                                    appendLine("#EXTINF:-1,${data.title ?: "Stream"}")
                                    // VLC headers
                                    if (!userAgent.isNullOrBlank()) appendLine("#EXTVLCOPT:http-user-agent=$userAgent")
                                    if (!referer.isNullOrBlank()) appendLine("#EXTVLCOPT:http-referrer=$referer")
                                    // PotPlayer headers (often uses standard URL or specific format)
                                    // Some versions of PotPlayer support |Header=Value in the URL
                                    val urlWithHeaders = if (!userAgent.isNullOrBlank() || !referer.isNullOrBlank()) {
                                        var suffix = ""
                                        if (!userAgent.isNullOrBlank()) suffix += "|User-Agent=$userAgent"
                                        if (!referer.isNullOrBlank()) suffix += "|Referer=$referer"
                                        "${link.url}$suffix"
                                    } else {
                                        link.url
                                    }
                                    appendLine(urlWithHeaders)
                                }.toString()

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
                    #toast { visibility: hidden; min-width: 200px; background-color: #333; color: #fff; text-align: center; border-radius: 2px; padding: 16px; position: fixed; z-index: 1; left: 50%; bottom: 30px; transform: translateX(-50%); }
                    #toast.show { visibility: visible; -webkit-animation: fadein 0.5s, fadeout 0.5s 2.5s; animation: fadein 0.5s, fadeout 0.5s 2.5s; }
                    @-webkit-keyframes fadein { from {bottom: 0; opacity: 0;} to {bottom: 30px; opacity: 1;} }
                    @keyframes fadein { from {bottom: 0; opacity: 0;} to {bottom: 30px; opacity: 1;} }
                    @-webkit-keyframes fadeout { from {bottom: 30px; opacity: 1;} to {bottom: 0; opacity: 0;} }
                    @keyframes fadeout { from {bottom: 30px; opacity: 1;} to {bottom: 0; opacity: 0;} }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1 id="title">Loading...</h1>
                    <img id="poster" src="" style="display:none;">
                    <div id="info"></div>
                    <div id="links" class="links"></div>
                </div>
                <div id="toast">Copied to clipboard!</div>

                <script>
                    function showToast(text) {
                        const x = document.getElementById("toast");
                        x.innerText = text;
                        x.className = "show";
                        setTimeout(function(){ x.className = x.className.replace("show", ""); }, 3000);
                    }

                    function copyToClipboard(text) {
                        navigator.clipboard.writeText(text).then(() => {
                            showToast("Copied!");
                        }).catch(err => {
                            console.error('Error copying text: ', err);
                        });
                    }

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
                                const m3uUrl = window.location.origin + '/play.m3u?index=' + index;
                                linksHtml += `<div style="margin-bottom: 20px; border-bottom: 1px solid #333; padding-bottom: 10px;">`;
                                linksHtml += `<strong>${'$'}{link.name} (${'$'}{link.quality}p)</strong>`;
                                linksHtml += `<a class="btn" href="${'$'}{m3uUrl}">Download M3U</a>`;
                                linksHtml += `<button class="btn btn-secondary" onclick="copyToClipboard('${'$'}{m3uUrl}')">Copy M3U URL</button>`;
                                linksHtml += `<button class="btn btn-secondary" onclick="copyToClipboard('${'$'}{link.url}')">Copy Stream URL</button>`;
                                linksHtml += `</div>`;
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
