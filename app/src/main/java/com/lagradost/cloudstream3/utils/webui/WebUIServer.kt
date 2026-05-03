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
import io.ktor.server.request.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.mvvm.Resource
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
                                call.respond(emptyList<Map<String, Any?>>())
                                return@get
                            }
                            
                            val results = apis.amap { api ->
                                try {
                                    // Use APIRepository for consistent behavior with the app
                                    val repo = APIRepository(api)
                                    // We use a shorter timeout for search to keep WebUI responsive
                                    val search = withTimeoutOrNull(15000) {
                                        repo.search(query, 1)
                                    }
                                    
                                    if (search is Resource.Success) {
                                        search.value.items.map {
                                            mapOf(
                                                "name" to it.name,
                                                "url" to it.url,
                                                "apiName" to it.apiName,
                                                "posterUrl" to it.posterUrl,
                                                "type" to it.type?.name
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } catch (e: Throwable) {
                                    logError(e)
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
                                    val repo = APIRepository(api)
                                    val loadResource = withTimeoutOrNull(30000) {
                                        repo.load(url)
                                    }
                                    
                                    if (loadResource is Resource.Success) {
                                        val loadResponse = loadResource.value
                                        val episodes = mutableListOf<Map<String, Any?>>()
                                        
                                        when (loadResponse) {
                                            is MovieLoadResponse -> {
                                                episodes.add(mapOf("name" to loadResponse.name, "data" to loadResponse.dataUrl))
                                            }
                                            is TvSeriesLoadResponse -> {
                                                loadResponse.episodes.forEach { ep ->
                                                    episodes.add(mapOf(
                                                        "name" to (ep.name ?: "Episode ${ep.episode ?: "Unknown"}"), 
                                                        "data" to ep.data, 
                                                        "season" to ep.season, 
                                                        "episode" to ep.episode
                                                    ))
                                                }
                                            }
                                            is AnimeLoadResponse -> {
                                                loadResponse.episodes.values.flatten().forEach { ep ->
                                                    episodes.add(mapOf(
                                                        "name" to (ep.name ?: "Episode ${ep.episode ?: "Unknown"}"), 
                                                        "data" to ep.data, 
                                                        "season" to ep.season, 
                                                        "episode" to ep.episode
                                                    ))
                                                }
                                            }
                                            is LiveStreamLoadResponse -> {
                                                episodes.add(mapOf("name" to loadResponse.name, "data" to loadResponse.dataUrl))
                                            }
                                        }
                                        
                                        call.respond(mapOf(
                                            "title" to loadResponse.name,
                                            "poster" to loadResponse.posterUrl,
                                            "episodes" to episodes
                                        ))
                                    } else {
                                        val errorMsg = if (loadResource is Resource.Failure) loadResource.errorString else "Failed to load metadata (Timeout or Null)"
                                        com.lagradost.cloudstream3.mvvm.debugPrint { "WebUI Metadata load failed for $apiName ($url): $errorMsg" }
                                        call.respond(mapOf("error" to errorMsg))
                                    }
                                } catch (e: Throwable) {
                                    logError(e)
                                    call.respond(mapOf("error" to (e.message ?: "Unknown error")))
                                }
                            } else {
                                call.respond(mapOf("error" to "Invalid API or URL"))
                            }
                        }
                        post("/api/load_links") {
                            val req = call.receive<Map<String, String?>>()
                            val dataUrl = req["data"] ?: ""
                            val apiName = req["apiName"] ?: ""
                            val title = req["title"]
                            val poster = req["poster"]
                            val season = req["season"]?.toIntOrNull()
                            val episode = req["episode"]?.toIntOrNull()
                            
                            val api = getApiFromNameNull(apiName)
                            
                            if (api != null && dataUrl.isNotBlank()) {
                                try {
                                    val links = mutableListOf<ExtractorLink>()
                                    val subs = mutableListOf<SubtitleData>()
                                    
                                    api.loadLinks(dataUrl, false, { subFile -> 
                                        subs.add(SubtitleData(
                                            subFile.lang,
                                            "",
                                            subFile.url,
                                            SubtitleOrigin.URL,
                                            "text/vtt", // Placeholder
                                            emptyMap(),
                                            subFile.lang
                                        ))
                                    }, { links.add(it) })
                                    
                                    CurrentStreamManager.updateStream(title, poster, episode, season, links, subs)
                                    call.respond(mapOf("success" to true))
                                } catch (e: Throwable) {
                                    call.respond(mapOf("error" to e.message))
                                }
                            } else {
                                call.respond(mapOf("error" to "Invalid API or data"))
                            }
                        }
                        post("/api/play") {
                            val data = call.receive<CurrentStreamManager.StreamData>()
                            CurrentStreamManager.updateStream(data.title, data.poster, data.episode, data.season, data.links, data.subs)
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

                                call.response.header(
                                    io.ktor.http.HttpHeaders.ContentDisposition,
                                    io.ktor.http.ContentDisposition.Attachment.withParameter(
                                        io.ktor.http.ContentDisposition.Parameters.FileName,
                                        "${data.title ?: "stream"}.m3u"
                                    ).toString()
                                )
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
            <html lang="en" class="dark">
            <head>
                <title>Cloudstream WebUI</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
                <script src="https://cdn.tailwindcss.com"></script>
                <script>
                    tailwind.config = {
                        darkMode: 'class',
                        theme: {
                            extend: {
                                fontFamily: {
                                    sans: ['Inter', 'Geist Sans', 'system-ui', 'sans-serif'],
                                    mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
                                },
                                colors: {
                                    background: {
                                        deep: '#020203',
                                        base: '#050506',
                                        elevated: '#0a0a0c',
                                    },
                                    foreground: {
                                        DEFAULT: '#EDEDEF',
                                        muted: '#8A8F98',
                                        subtle: 'rgba(255,255,255,0.60)',
                                    },
                                    accent: {
                                        DEFAULT: '#5E6AD2',
                                        bright: '#6872D9',
                                    }
                                },
                                animation: {
                                    'float': 'float 10s ease-in-out infinite',
                                    'float-delayed': 'float 10s ease-in-out 5s infinite',
                                    'float-fast': 'float 8s ease-in-out 2s infinite',
                                },
                                keyframes: {
                                    float: {
                                        '0%, 100%': { transform: 'translateY(0) rotate(0deg)' },
                                        '50%': { transform: 'translateY(-20px) rotate(1deg)' },
                                    }
                                }
                            }
                        }
                    }
                </script>
                <style>
                    body {
                        background-color: #050506;
                        color: #EDEDEF;
                        overflow-x: hidden;
                    }
                    .no-scrollbar::-webkit-scrollbar { display: none; }
                    .no-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
                    
                    #toast { visibility: hidden; opacity: 0; transition: visibility 0s 0.5s, opacity 0.5s linear; transform: translate(-50%, 20px); }
                    #toast.show { visibility: visible; opacity: 1; transition: opacity 0.5s cubic-bezier(0.16, 1, 0.3, 1), transform 0.5s cubic-bezier(0.16, 1, 0.3, 1); transform: translate(-50%, 0); }
                    
                    .bg-layer-1 { position: fixed; inset: 0; background: radial-gradient(ellipse at top, #0a0a0f 0%, #050506 50%, #020203 100%); z-index: -5; }
                    .bg-layer-2 { position: fixed; inset: 0; background-image: linear-gradient(to right, rgba(255,255,255,0.02) 1px, transparent 1px), linear-gradient(to bottom, rgba(255,255,255,0.02) 1px, transparent 1px); background-size: 64px 64px; mask-image: radial-gradient(ellipse at center, black 40%, transparent 80%); -webkit-mask-image: radial-gradient(ellipse at center, black 40%, transparent 80%); z-index: -4; }
                    .bg-layer-3 { position: fixed; inset: 0; opacity: 0.015; background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E"); z-index: -3; pointer-events: none; }
                    .blob-1 { position: fixed; top: -20%; left: 50%; transform: translateX(-50%); width: 900px; height: 1400px; background: rgba(94,106,210,0.25); filter: blur(150px); border-radius: 50%; z-index: -2; pointer-events: none; }
                    .blob-2 { position: fixed; top: 10%; left: -20%; width: 600px; height: 800px; background: rgba(147,51,234,0.15); filter: blur(120px); border-radius: 50%; z-index: -2; pointer-events: none; }
                    .blob-3 { position: fixed; bottom: -10%; right: -10%; width: 500px; height: 700px; background: rgba(59,130,246,0.12); filter: blur(100px); border-radius: 50%; z-index: -2; pointer-events: none; }

                    .btn-primary { background-color: #5E6AD2; color: white; box-shadow: 0 0 0 1px rgba(94,106,210,0.5), 0 4px 12px rgba(94,106,210,0.3), inset 0 1px 0 0 rgba(255,255,255,0.2); transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1); }
                    .btn-primary:hover { background-color: #6872D9; box-shadow: 0 0 0 1px rgba(94,106,210,0.6), 0 6px 16px rgba(94,106,210,0.4), inset 0 1px 0 0 rgba(255,255,255,0.25); transform: translateY(-2px); }
                    .btn-primary:active { transform: scale(0.98); box-shadow: 0 0 0 1px rgba(94,106,210,0.5), 0 2px 4px rgba(94,106,210,0.2), inset 0 1px 0 0 rgba(255,255,255,0.1); }
                    
                    .btn-secondary { background-color: rgba(255,255,255,0.05); color: #EDEDEF; box-shadow: inset 0 0 0 1px rgba(255,255,255,0.06), inset 0 1px 0 0 rgba(255,255,255,0.05); transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1); }
                    .btn-secondary:hover { background-color: rgba(255,255,255,0.08); box-shadow: inset 0 0 0 1px rgba(255,255,255,0.1), inset 0 1px 0 0 rgba(255,255,255,0.1), 0 4px 12px rgba(0,0,0,0.2); transform: translateY(-2px); }
                    .btn-secondary:active { transform: scale(0.98); }

                    .glass-card { background: linear-gradient(to bottom, rgba(255,255,255,0.04), rgba(255,255,255,0.01)); box-shadow: 0 0 0 1px rgba(255,255,255,0.06), 0 2px 20px rgba(0,0,0,0.4), 0 0 40px rgba(0,0,0,0.2), inset 0 1px 0 0 rgba(255,255,255,0.05); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); border-radius: 16px; transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1); position: relative; overflow: hidden; }
                    .interactive-card:hover { box-shadow: 0 0 0 1px rgba(255,255,255,0.1), 0 8px 40px rgba(0,0,0,0.5), 0 0 80px rgba(94,106,210,0.1), inset 0 1px 0 0 rgba(255,255,255,0.1); transform: translateY(-4px); }
                    .spotlight { position: absolute; inset: 0; opacity: 0; transition: opacity 0.3s; pointer-events: none; background: radial-gradient(300px circle at var(--mouse-x) var(--mouse-y), rgba(94,106,210,0.15), transparent 40%); z-index: 1; }
                    .glass-card:hover .spotlight { opacity: 1; }

                    .input-modern { background-color: #0F0F12; border: 1px solid rgba(255,255,255,0.1); color: #EDEDEF; transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1); }
                    .input-modern:focus { outline: none; border-color: #5E6AD2; box-shadow: 0 0 0 2px rgba(94,106,210,0.3); }

                    .text-gradient { background: linear-gradient(to bottom, #FFFFFF, rgba(255,255,255,0.7)); -webkit-background-clip: text; background-clip: text; color: transparent; }
                    .text-gradient-accent { background: linear-gradient(to right, #5E6AD2, #818cf8, #5E6AD2); background-size: 200% auto; animation: textShine 4s linear infinite; -webkit-background-clip: text; background-clip: text; color: transparent; }
                    @keyframes textShine { to { background-position: 200% center; } }

                    nav { background: rgba(5, 5, 6, 0.8); backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); border-bottom: 1px solid rgba(255,255,255,0.06); }
                    .fade-in-up { animation: fadeInUp 0.6s cubic-bezier(0.16, 1, 0.3, 1) forwards; opacity: 0; transform: translateY(24px); }
                    @keyframes fadeInUp { to { opacity: 1; transform: translateY(0); } }
                </style>
            </head>
            <body class="font-sans antialiased min-h-screen flex flex-col relative">
                <!-- Ambient Backgrounds -->
                <div class="bg-layer-1"></div>
                <div class="bg-layer-2"></div>
                <div class="bg-layer-3"></div>
                <div class="blob-1 animate-float"></div>
                <div class="blob-2 animate-float-delayed"></div>
                <div class="blob-3 animate-float-fast"></div>

                <nav class="py-4 px-6 flex justify-between items-center sticky top-0 z-10 w-full transition-all">
                    <div class="text-xl font-semibold flex items-center gap-3 cursor-pointer group" onclick="navigate('#/')">
                        <div class="w-8 h-8 rounded-lg bg-gradient-to-br from-accent to-accent-bright flex items-center justify-center shadow-[0_0_15px_rgba(94,106,210,0.4)] group-hover:scale-105 transition-transform">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="white" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347c-.75.412-1.667-.13-1.667-.986V5.653Z" /></svg>
                        </div>
                        <span class="text-gradient">Cloudstream UI</span>
                    </div>
                    <div class="flex gap-3 w-full max-w-md ml-4">
                        <div class="relative w-full">
                            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 absolute left-3 top-1/2 transform -translate-y-1/2 text-foreground-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
                            <input type="text" id="search-input" placeholder="Search..." class="w-full input-modern rounded-lg pl-10 pr-4 py-2 text-sm" onkeyup="if(event.key === 'Enter') search()">
                        </div>
                        <button onclick="search()" class="btn-primary rounded-lg px-5 py-2 text-sm font-medium">Search</button>
                    </div>
                </nav>

                <main class="flex-grow container mx-auto px-4 py-16 md:py-24 max-w-6xl relative z-0">
                    <div id="player-view" class="flex flex-col md:flex-row gap-8 items-start justify-center fade-in-up" style="animation-delay: 0.1s;">
                        <div class="w-full md:w-1/3 max-w-sm mx-auto">
                            <div class="glass-card interactive-card aspect-[2/3] flex items-center justify-center group" onmousemove="handleMouseMove(event, this)">
                                <div class="spotlight"></div>
                                <img id="poster" src="" alt="Poster" class="w-full h-full object-cover hidden z-10 relative">
                                <div id="poster-placeholder" class="text-foreground-muted flex flex-col items-center z-10 relative">
                                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-12 h-12 mb-4 opacity-50"><path stroke-linecap="round" stroke-linejoin="round" d="M6 20.25h12m-7.5-3v3m3-3v3m-10.125-3h17.25c.621 0 1.125-.504 1.125-1.125V4.875c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125Z" /></svg>
                                    <span class="text-sm font-mono tracking-widest uppercase">STANDBY</span>
                                </div>
                            </div>
                        </div>
                        
                        <div class="w-full md:w-2/3 glass-card p-8 interactive-card" onmousemove="handleMouseMove(event, this)">
                            <div class="spotlight"></div>
                            <div class="relative z-10">
                                <h1 id="title" class="text-4xl md:text-5xl font-semibold tracking-tight text-gradient mb-3">Loading...</h1>
                                <p id="info" class="text-lg text-accent-bright font-medium mb-8 flex items-center gap-2"></p>
                                
                                <div id="links-container" class="hidden">
                                    <h3 class="text-sm font-mono tracking-widest text-foreground-muted mb-4 uppercase">Available Streams</h3>
                                    <div id="links" class="flex flex-col gap-3"></div>
                                </div>
                                <div id="no-links-msg" class="text-foreground-subtle italic text-sm">Push a stream from the app or search to start playing.</div>
                            </div>
                        </div>
                    </div>

                    <div id="search-view" class="hidden fade-in-up">
                        <div class="flex items-center justify-between mb-10">
                            <h2 id="search-query-title" class="text-3xl md:text-4xl font-semibold text-gradient tracking-tight">Search Results</h2>
                            <button onclick="navigate('#/')" class="btn-secondary rounded-lg px-4 py-2 text-sm flex items-center gap-2">
                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-4 h-4"><path stroke-linecap="round" stroke-linejoin="round" d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18" /></svg>
                                Back
                            </button>
                        </div>
                        
                        <div id="search-results-loading" class="hidden flex justify-center py-20">
                            <div class="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin"></div>
                        </div>
                        
                        <div id="search-results" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6 auto-rows-[auto]"></div>
                    </div>

                    <div id="episode-view" class="hidden max-w-5xl mx-auto fade-in-up">
                        <button onclick="window.history.back()" class="mb-8 btn-secondary rounded-lg px-4 py-2 text-sm inline-flex items-center gap-2">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-4 h-4"><path stroke-linecap="round" stroke-linejoin="round" d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18" /></svg>
                            Back
                        </button>
                        
                        <div class="glass-card p-8 interactive-card" onmousemove="handleMouseMove(event, this)">
                            <div class="spotlight"></div>
                            <div class="relative z-10">
                                <h2 id="metadata-title" class="text-4xl font-semibold text-gradient mb-8 tracking-tight">Loading...</h2>
                                
                                <div id="season-selector-container" class="mb-8 hidden">
                                    <label for="season-select" class="block text-xs font-mono tracking-widest text-foreground-muted mb-3 uppercase">Season</label>
                                    <select id="season-select" onchange="renderEpisodesForSeason(this.value)" class="input-modern text-sm rounded-lg block w-48 p-2.5 appearance-none cursor-pointer"></select>
                                </div>

                                <div id="episodes-list" class="flex flex-col gap-3 max-h-[60vh] overflow-y-auto pr-4 no-scrollbar"></div>
                            </div>
                        </div>
                    </div>
                </main>

                <div id="toast" class="fixed bottom-8 left-1/2 bg-background-elevated border border-white/10 text-foreground px-5 py-3 rounded-full shadow-[0_8px_30px_rgba(0,0,0,0.5),0_0_0_1px_rgba(255,255,255,0.05)] flex items-center gap-3 z-50 backdrop-blur-xl">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-5 h-5 text-accent-bright"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" /></svg>
                    <span id="toast-msg" class="text-sm font-medium">Success!</span>
                </div>

                <script>
                    function handleMouseMove(e, element) {
                        const rect = element.getBoundingClientRect();
                        const x = e.clientX - rect.left;
                        const y = e.clientY - rect.top;
                        element.style.setProperty('--mouse-x', `${'$'}{x}px`);
                        element.style.setProperty('--mouse-y', `${'$'}{y}px`);
                    }

                    const state = {
                        view: 'player',
                        searchQuery: '',
                        searchResults: [],
                        currentMetadata: null,
                        currentApiName: null,
                        isPolling: false,
                        pollInterval: null
                    };

                    function escapeHtml(unsafe) {
                        if (unsafe == null) return '';
                        return unsafe.toString()
                             .replace(/&/g, "&amp;")
                             .replace(/</g, "&lt;")
                             .replace(/>/g, "&gt;")
                             .replace(/"/g, "&quot;")
                             .replace(/'/g, "&#039;");
                    }

                    function navigate(hash) {
                        window.location.hash = hash;
                    }

                    async function handleRoute() {
                        const hash = window.location.hash || '#/';
                        
                        if (hash.startsWith('#/search')) {
                            const params = new URLSearchParams(hash.split('?')[1]);
                            const q = params.get('q') || '';
                            state.view = 'search';
                            if (q !== state.searchQuery || state.searchResults.length === 0) {
                                await performSearch(q);
                            } else {
                                renderSearchView();
                            }
                        } else if (hash.startsWith('#/episodes')) {
                            const params = new URLSearchParams(hash.split('?')[1]);
                            const url = params.get('url');
                            const api = params.get('api');
                            state.view = 'episodes';
                            if (!state.currentMetadata || state.currentMetadata.url !== url) {
                                await fetchMetadata(url, api);
                            } else {
                                renderEpisodeView();
                            }
                        } else {
                            state.view = 'player';
                            renderPlayerView();
                        }
                        
                        updateViewVisibility();
                    }

                    function updateViewVisibility() {
                        document.getElementById('player-view').classList.toggle('hidden', state.view !== 'player');
                        document.getElementById('search-view').classList.toggle('hidden', state.view !== 'search');
                        document.getElementById('episode-view').classList.toggle('hidden', state.view !== 'episodes');
                        
                        if (state.view === 'player' && !state.isPolling) {
                            startPolling();
                        } else if (state.view !== 'player' && state.isPolling) {
                            stopPolling();
                        }
                    }

                    function onSearchTrigger() {
                        const q = document.getElementById('search-input').value;
                        if (q) navigate(`#/search?q=${'$'}{encodeURIComponent(q)}`);
                    }

                    async function performSearch(query) {
                        state.searchQuery = query;
                        document.getElementById('search-input').value = query;
                        document.getElementById('search-query-title').innerHTML = `Results for <span class="text-gradient-accent">"${'$'}{escapeHtml(query)}"</span>`;
                        
                        const resultsContainer = document.getElementById('search-results');
                        const loadingIndicator = document.getElementById('search-results-loading');
                        
                        resultsContainer.innerHTML = '';
                        loadingIndicator.classList.remove('hidden');
                        renderSearchView(); 

                        try {
                            const res = await fetch('/api/search?q=' + encodeURIComponent(query));
                            state.searchResults = await res.json();
                            renderSearchView();
                        } catch (e) {
                            console.error(e);
                            showToast("Search failed.");
                        } finally {
                            loadingIndicator.classList.add('hidden');
                        }
                    }

                    async function fetchMetadata(url, apiName) {
                        showToast("Loading metadata...");
                        state.currentApiName = apiName;
                        
                        document.getElementById('metadata-title').innerText = 'Loading...';
                        document.getElementById('season-selector-container').classList.add('hidden');
                        document.getElementById('episodes-list').innerHTML = '<div class="flex justify-center py-12"><div class="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin"></div></div>';

                        try {
                            const res = await fetch(`/api/load?url=${'$'}{encodeURIComponent(url)}&apiName=${'$'}{encodeURIComponent(apiName)}`);
                            state.currentMetadata = await res.json();
                            state.currentMetadata.url = url; 
                            
                            if (state.currentMetadata.error) {
                                showToast("Error: " + state.currentMetadata.error);
                                navigate('#/search?q=' + encodeURIComponent(state.searchQuery));
                                return;
                            }
                            
                            renderEpisodeView();
                        } catch (e) {
                            console.error(e);
                            showToast("Failed to load metadata.");
                            navigate('#/search?q=' + encodeURIComponent(state.searchQuery));
                        }
                    }

                    function renderSearchView() {
                        const resultsContainer = document.getElementById('search-results');
                        
                        if (state.searchResults.length === 0) {
                            resultsContainer.innerHTML = '<div class="col-span-full text-center text-foreground-muted py-12">No results found.</div>';
                            return;
                        }

                        let html = '';
                        state.searchResults.forEach((item, i) => {
                            const escapedName = escapeHtml(item.name);
                            const posterUrl = item.posterUrl ? escapeHtml(item.posterUrl) : 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iNDUwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4=';
                            
                            html += `
                                <div class="glass-card interactive-card flex flex-col group cursor-pointer h-full" style="animation: fadeInUp 0.4s cubic-bezier(0.16, 1, 0.3, 1) ${'$'}{i * 0.05}s forwards; opacity: 0;" onclick="navigate('#/episodes?url=${'$'}{encodeURIComponent(item.url)}&api=${'$'}{encodeURIComponent(item.apiName)}')" onmousemove="handleMouseMove(event, this)">
                                    <div class="spotlight"></div>
                                    <div class="relative aspect-[2/3] w-full overflow-hidden rounded-t-2xl z-10">
                                        <img src="${'$'}{posterUrl}" onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iNDUwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4='" class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105">
                                        <div class="absolute inset-0 bg-gradient-to-t from-background-elevated to-transparent opacity-60"></div>
                                        <div class="absolute inset-0 bg-black/0 group-hover:bg-black/20 transition-colors flex items-center justify-center">
                                            <div class="w-12 h-12 rounded-full bg-accent/90 backdrop-blur-sm flex items-center justify-center opacity-0 group-hover:opacity-100 transform scale-75 group-hover:scale-100 transition-all duration-300 shadow-[0_0_20px_rgba(94,106,210,0.5)]">
                                                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5 text-white ml-1"><path fill-rule="evenodd" d="M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z" clip-rule="evenodd" /></svg>
                                            </div>
                                        </div>
                                    </div>
                                    <div class="p-4 flex-grow flex flex-col justify-between z-10 bg-background-elevated/50 backdrop-blur-md">
                                        <h3 class="font-medium text-sm line-clamp-2 text-foreground" title="${'$'}{escapedName}">${'$'}{escapedName}</h3>
                                        <span class="text-xs font-mono tracking-wider text-foreground-muted mt-3 truncate uppercase">${'$'}{escapeHtml(item.apiName)}</span>
                                    </div>
                                </div>
                            `;
                        });
                        resultsContainer.innerHTML = html;
                    }

                    function renderEpisodeView() {
                        const metadata = state.currentMetadata;
                        document.getElementById('metadata-title').innerText = metadata.title;
                        
                        const seasons = new Set();
                        let hasValidSeason = false;
                        metadata.episodes.forEach(ep => {
                            if (ep.season != null) {
                                seasons.add(ep.season);
                                hasValidSeason = true;
                            }
                        });

                        const seasonContainer = document.getElementById('season-selector-container');
                        const seasonSelect = document.getElementById('season-select');
                        
                        if (hasValidSeason && seasons.size > 0) {
                            seasonContainer.classList.remove('hidden');
                            seasonSelect.innerHTML = '';
                            const sortedSeasons = Array.from(seasons).sort((a, b) => a - b);
                            sortedSeasons.forEach(s => {
                                const option = document.createElement('option');
                                option.value = s;
                                option.text = 'Season ' + s;
                                seasonSelect.appendChild(option);
                            });
                            renderEpisodesForSeason(sortedSeasons[0]);
                        } else {
                            seasonContainer.classList.add('hidden');
                            renderEpisodesForSeason(null);
                        }
                    }

                    function renderEpisodesForSeason(seasonVal) {
                        const episodesList = document.getElementById('episodes-list');
                        episodesList.innerHTML = '';
                        
                        let filteredEpisodes = state.currentMetadata.episodes;
                        if (seasonVal !== null) {
                            const s = parseInt(seasonVal, 10);
                            filteredEpisodes = state.currentMetadata.episodes.filter(ep => ep.season === s);
                        }

                        filteredEpisodes.forEach((ep, i) => {
                            const btn = document.createElement('button');
                            btn.className = "flex items-center justify-between p-4 rounded-xl transition-all duration-200 text-left w-full group bg-white/[0.02] border border-white/[0.04] hover:bg-white/[0.05] hover:border-white/[0.1] hover:shadow-[0_4px_20px_rgba(0,0,0,0.2)]";
                            btn.style.animation = `fadeInUp 0.3s cubic-bezier(0.16, 1, 0.3, 1) ${'$'}{i * 0.03}s forwards`;
                            btn.style.opacity = '0';
                            
                            const epInfo = ep.episode != null ? `<span class="text-xs font-mono bg-white/[0.08] group-hover:bg-accent/20 group-hover:text-accent-bright px-2 py-1 rounded-md text-foreground-muted mr-4 transition-colors">E${'$'}{ep.episode}</span>` : '';
                            btn.innerHTML = `
                                <div class="flex items-center flex-grow overflow-hidden">
                                    ${'$'}{epInfo}
                                    <span class="font-medium text-sm text-foreground truncate group-hover:text-white transition-colors">${'$'}{escapeHtml(ep.name)}</span>
                                </div>
                                <div class="w-8 h-8 rounded-full bg-white/[0.05] group-hover:bg-accent flex items-center justify-center transition-all ml-4 flex-shrink-0 group-hover:shadow-[0_0_12px_rgba(94,106,210,0.5)]">
                                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-foreground-muted group-hover:text-white ml-0.5"><path fill-rule="evenodd" d="M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z" clip-rule="evenodd" /></svg>
                                </div>
                            `;
                            btn.addEventListener('click', () => {
                                playEpisode(ep.data, state.currentApiName, state.currentMetadata.title, state.currentMetadata.poster, ep.season, ep.episode);
                            });
                            episodesList.appendChild(btn);
                        });
                    }

                    async function playEpisode(dataUrl, apiName, title, poster, season, episode) {
                        showToast("Pushing stream...");
                        try {
                            const res = await fetch('/api/load_links', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ data: dataUrl, apiName: apiName, title: title, poster: poster, season: season, episode: episode })
                            });
                            const playRes = await res.json();
                            if (playRes.success) {
                                showToast("Stream pushed successfully!");
                                navigate('#/');
                            } else {
                                showToast("Error: " + playRes.error);
                            }
                        } catch (e) {
                            console.error(e);
                            showToast("Failed to push stream.");
                        }
                    }

                    function renderPlayerView() {
                        updatePoll();
                    }

                    async function updatePoll() {
                        if (state.view !== 'player') return;
                        try {
                            const res = await fetch('/api/current_stream');
                            const data = await res.json();
                            
                            const titleEl = document.getElementById('title');
                            const posterEl = document.getElementById('poster');
                            const posterPlaceholder = document.getElementById('poster-placeholder');
                            const infoEl = document.getElementById('info');
                            const linksContainer = document.getElementById('links-container');
                            const noLinksMsg = document.getElementById('no-links-msg');
                            const linksList = document.getElementById('links');
                            
                            if (data.error) {
                                titleEl.innerText = 'No Active Stream';
                                titleEl.classList.remove('text-gradient');
                                titleEl.classList.add('text-foreground-subtle');
                                posterEl.classList.add('hidden');
                                posterPlaceholder.classList.remove('hidden');
                                infoEl.innerText = '';
                                linksContainer.classList.add('hidden');
                                noLinksMsg.classList.remove('hidden');
                                return;
                            }
                            
                            titleEl.innerText = data.title || 'Unknown Title';
                            titleEl.classList.add('text-gradient');
                            titleEl.classList.remove('text-foreground-subtle');
                            
                            if (data.poster) {
                                posterEl.src = data.poster;
                                posterEl.classList.remove('hidden');
                                posterPlaceholder.classList.add('hidden');
                            } else {
                                posterEl.classList.add('hidden');
                                posterPlaceholder.classList.remove('hidden');
                            }
                            
                            let infoText = '';
                            if (data.season) infoText += '<span class="bg-white/10 px-2 py-0.5 rounded text-sm">Season ' + data.season + '</span>';
                            if (data.episode) infoText += '<span class="bg-white/10 px-2 py-0.5 rounded text-sm">Episode ' + data.episode + '</span>';
                            infoEl.innerHTML = infoText;
                            
                            if (data.links && data.links.length > 0) {
                                linksContainer.classList.remove('hidden');
                                noLinksMsg.classList.add('hidden');
                                
                                linksList.innerHTML = '';
                                data.links.forEach((link, index) => {
                                    const m3uUrl = window.location.origin + '/play.m3u?index=' + index;
                                    const linkName = escapeHtml(link.name || 'Unknown');
                                    const quality = escapeHtml(link.quality || 'Auto');
                                    
                                    const item = document.createElement('div');
                                    item.className = "bg-white/[0.02] p-4 rounded-xl border border-white/[0.04] flex flex-col xl:flex-row gap-4 items-start xl:items-center justify-between hover:bg-white/[0.04] transition-colors";
                                    item.innerHTML = `
                                        <div class="flex flex-col">
                                            <span class="font-medium text-foreground text-sm">${'$'}{linkName}</span>
                                            <span class="text-xs text-accent-bright font-mono mt-1 uppercase tracking-widest">${'$'}{quality}</span>
                                        </div>
                                        <div class="flex flex-wrap gap-2 w-full xl:w-auto">
                                            <a href="vlc://${'$'}{m3uUrl}" class="flex-grow xl:flex-grow-0 btn-primary rounded-lg px-3 py-1.5 text-xs font-medium text-center inline-flex items-center justify-center">
                                                VLC Direct
                                            </a>
                                            <a href="${'$'}{m3uUrl}" class="flex-grow xl:flex-grow-0 btn-secondary rounded-lg px-3 py-1.5 text-xs font-medium text-center inline-flex items-center justify-center border border-white/10">
                                                VLC (M3U)
                                            </a>
                                            <a href="potplayer://${'$'}{m3uUrl}" class="flex-grow xl:flex-grow-0 btn-secondary rounded-lg px-3 py-1.5 text-xs font-medium text-center inline-flex items-center justify-center border border-white/10">
                                                PotPlayer
                                            </a>
                                            <button onclick="copyToClipboard('${'$'}{m3uUrl}')" class="flex-grow xl:flex-grow-0 btn-secondary rounded-lg px-3 py-1.5 text-xs font-medium text-center inline-flex items-center justify-center border border-white/10">
                                                Copy M3U
                                            </button>
                                        </div>
                                    `;
                                    linksList.appendChild(item);
                                });
                            } else {
                                linksContainer.classList.add('hidden');
                                noLinksMsg.classList.remove('hidden');
                            }
                        } catch (e) {
                            console.error(e);
                        }
                    }

                    function startPolling() {
                        state.isPolling = true;
                        state.pollInterval = setInterval(updatePoll, 5000);
                        updatePoll();
                    }

                    function stopPolling() {
                        state.isPolling = false;
                        if (state.pollInterval) clearInterval(state.pollInterval);
                    }

                    function showToast(text) {
                        const toast = document.getElementById("toast");
                        const msg = document.getElementById("toast-msg");
                        msg.innerText = text;
                        toast.classList.remove("show");
                        void toast.offsetWidth;
                        toast.classList.add("show");
                        setTimeout(() => toast.classList.remove("show"), 3000);
                    }

                    function copyToClipboard(text) {
                        navigator.clipboard.writeText(text).then(() => {
                            showToast("Copied to clipboard!");
                        }).catch(err => {
                            const textArea = document.createElement("textarea");
                            textArea.value = text;
                            document.body.appendChild(textArea);
                            textArea.select();
                            document.execCommand('copy');
                            document.body.removeChild(textArea);
                            showToast("Copied!");
                        });
                    }

                    window.addEventListener('hashchange', handleRoute);
                    window.addEventListener('load', handleRoute);

                    window.search = onSearchTrigger;
                    window.renderEpisodesForSeason = renderEpisodesForSeason;
                    window.copyToClipboard = copyToClipboard;
                    window.update = () => navigate('#/');
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
