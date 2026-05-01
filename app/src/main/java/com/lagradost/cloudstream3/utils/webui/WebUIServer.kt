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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

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
                        get("/api/search") {
                            val query = call.parameters["q"] ?: ""
                            if (query.length < 2) {
                                call.respond(emptyList<SearchResponse>())
                                return@get
                            }
                            
                            val results = apis.amap { api ->
                                try {
                                    api.search(query) ?: emptyList()
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            }.flatten()
                            
                            call.respond(results)
                        }
                        get("/api/load") {
                            val url = call.parameters["url"] ?: ""
                            val apiName = call.parameters["apiName"] ?: ""
                            val api = getApiFromNameNull(apiName)
                            
                            if (api != null && url.isNotBlank()) {
                                try {
                                    val loadResponse = api.load(url)
                                    if (loadResponse != null) {
                                        val links = mutableListOf<ExtractorLink>()
                                        val subs = mutableListOf<SubtitleFile>()
                                        
                                        // Extract links for Movie/Episode
                                        val dataUrls = when (loadResponse) {
                                            is MovieLoadResponse -> listOf(loadResponse.dataUrl)
                                            is TvSeriesLoadResponse -> loadResponse.episodes.map { it.data }
                                            is AnimeLoadResponse -> loadResponse.episodes.values.flatten().map { it.data }
                                            is LiveStreamLoadResponse -> listOf(loadResponse.dataUrl)
                                            else -> emptyList()
                                        }
                                        
                                        dataUrls.forEach { dataUrl ->
                                            api.loadLinks(dataUrl, false, { subs.add(it) }, { links.add(it) })
                                        }
                                        
                                        val streamData = CurrentStreamData(
                                            title = loadResponse.name,
                                            poster = loadResponse.posterUrl,
                                            links = links,
                                            subs = subs
                                        )
                                        call.respond(streamData)
                                    } else {
                                        call.respond(mapOf("error" to "Failed to load metadata"))
                                    }
                                } catch (e: Exception) {
                                    call.respond(mapOf("error" to e.message))
                                }
                            } else {
                                call.respond(mapOf("error" to "Invalid API or URL"))
                            }
                        }
                        post("/api/play") {
                            val data = call.receive<CurrentStreamData>()
                            CurrentStreamManager.updateData(data.title, data.poster, null, null, data.links, data.subs)
                            call.respond(mapOf("success" to true))
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
                    .search-container { margin-bottom: 20px; display: flex; gap: 10px; }
                    input { flex-grow: 1; padding: 10px; border-radius: 5px; border: 1px solid #333; background: #1e1e1e; color: white; }
                    .search-results { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; text-align: left; }
                    .search-item { background: #1e1e1e; border-radius: 5px; overflow: hidden; cursor: pointer; font-size: 12px; }
                    .search-item img { width: 100%; height: 180px; object-fit: cover; }
                    .search-item-title { padding: 5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
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
                    <div id="player-view">
                        <div class="search-container">
                            <input type="text" id="search-input" placeholder="Search movies/series..." onkeyup="if(event.key === 'Enter') search()">
                            <button class="btn" style="margin:0; width: auto;" onclick="search()">Search</button>
                        </div>
                        <h1 id="title">Loading...</h1>
                        <img id="poster" src="" style="display:none;">
                        <div id="info"></div>
                        <div id="links" class="links"></div>
                    </div>
                    <div id="search-view" style="display:none;">
                        <button class="btn btn-secondary" onclick="toggleView(false)">← Back to Player</button>
                        <h2 id="search-query-title">Search Results</h2>
                        <div id="search-results" class="search-results"></div>
                    </div>
                </div>
                <div id="toast">Copied to clipboard!</div>

                <script>
                    let isSearching = false;

                    function toggleView(searching) {
                        isSearching = searching;
                        document.getElementById('player-view').style.display = searching ? 'none' : 'block';
                        document.getElementById('search-view').style.display = searching ? 'block' : 'none';
                    }

                    async function search() {
                        const query = document.getElementById('search-input').value;
                        if (!query) return;
                        
                        toggleView(true);
                        document.getElementById('search-query-title').innerText = 'Searching for "' + query + '"...';
                        document.getElementById('search-results').innerHTML = 'Loading results...';
                        
                        try {
                            const res = await fetch('/api/search?q=' + encodeURIComponent(query));
                            const results = await res.json();
                            
                            let html = '';
                            results.forEach(item => {
                                html += `
                                    <div class="search-item" onclick="loadAndPlay('${'$'}{item.url}', '${'$'}{item.apiName}')">
                                        <img src="${'$'}{item.posterUrl || ''}" onerror="this.src='https://via.placeholder.com/120x180?text=No+Poster'">
                                        <div class="search-item-title">${'$'}{item.name}</div>
                                        <div style="padding: 0 5px 5px; opacity: 0.7;">${'$'}{item.apiName}</div>
                                    </div>
                                `;
                            });
                            document.getElementById('search-results').innerHTML = html || 'No results found.';
                            document.getElementById('search-query-title').innerText = 'Results for "' + query + '"';
                        } catch (e) {
                            console.error(e);
                            document.getElementById('search-results').innerHTML = 'Error searching.';
                        }
                    }

                    async function loadAndPlay(url, apiName) {
                        showToast("Loading metadata & links...");
                        try {
                            const res = await fetch(`/api/load?url=${'$'}{encodeURIComponent(url)}&apiName=${'$'}{encodeURIComponent(apiName)}`);
                            const streamData = await res.json();
                            
                            if (streamData.error) {
                                showToast("Error: " + streamData.error);
                                return;
                            }
                            
                            const playRes = await fetch('/api/play', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(streamData)
                            });
                            
                            if (playRes.ok) {
                                showToast("Stream pushed to app!");
                                toggleView(false);
                                update(); // Refresh player view immediately
                            }
                        } catch (e) {
                            console.error(e);
                            showToast("Failed to load stream.");
                        }
                    }

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
                        if (isSearching) return;
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
