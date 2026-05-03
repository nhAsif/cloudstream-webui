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
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
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
                        get("/api/history") {
                            try {
                                val history = com.lagradost.cloudstream3.ui.home.HomeViewModel.getResumeWatching()
                                if (history != null) {
                                    // Map to a simple format for the WebUI
                                    val result = history.distinctBy { it.name }.map {
                                        mapOf(
                                            "name" to it.name,
                                            "url" to it.url,
                                            "apiName" to it.apiName,
                                            "posterUrl" to it.posterUrl,
                                            "type" to it.type?.name,
                                            "episode" to it.episode,
                                            "season" to it.season
                                        )
                                    }
                                    call.respond(result)
                                } else {
                                    call.respond(emptyList<Map<String, Any?>>())
                                }
                            } catch (e: Throwable) {
                                logError(e)
                                call.respond(emptyList<Map<String, Any?>>())
                            }
                        }
                        get("/api/search") {
                            val query = call.parameters["q"] ?: ""
                            if (query.length < 2) {
                                call.respond(emptyMap<String, List<Map<String, Any?>>>())
                                return@get
                            }
                            
                            val results = apis.amap { api ->
                                try {
                                    val repo = APIRepository(api)
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
                                                "type" to (it.type?.name ?: "Others")
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
                            
                            val categorized = results.groupBy { it["apiName"] as String }
                            call.respond(categorized)
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
                                                        "episode" to ep.episode,
                                                        "description" to ep.description,
                                                        "posterUrl" to ep.posterUrl,
                                                        "score" to ep.score?.toDouble()
                                                    ))
                                                }
                                            }
                                            is AnimeLoadResponse -> {
                                                loadResponse.episodes.values.flatten().forEach { ep ->
                                                    episodes.add(mapOf(
                                                        "name" to (ep.name ?: "Episode ${ep.episode ?: "Unknown"}"), 
                                                        "data" to ep.data, 
                                                        "season" to ep.season, 
                                                        "episode" to ep.episode,
                                                        "description" to ep.description,
                                                        "posterUrl" to ep.posterUrl,
                                                        "score" to ep.score?.toDouble()
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
                                            "plot" to loadResponse.plot,
                                            "year" to loadResponse.year,
                                            "score" to loadResponse.score?.toDouble(),
                                            "tags" to loadResponse.tags,
                                            "duration" to loadResponse.duration,
                                            "actors" to loadResponse.actors?.map { mapOf("name" to it.actor.name, "role" to it.roleString, "image" to it.actor.image) },
                                            "contentRating" to loadResponse.contentRating,
                                            "type" to loadResponse.type.name,
                                            "imdbId" to loadResponse.getImdbId(),
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
                            val episodeName = req["episodeName"]
                            
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
                                    
                                    CurrentStreamManager.updateStream(title, poster, episode, season, links, subs, episodeName)
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
                            CurrentStreamManager.updateStream(data.title, data.poster, data.episode, data.season, data.links, data.subs, data.episodeName)
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
                                    },
                                    shimmer: {
                                        '0%': { transform: 'translateX(-100%)' },
                                        '100%': { transform: 'translateX(200%)' },
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
                    
                    #global-loader { transition: opacity 0.3s ease-in-out; }

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
                    <div id="home-view" class="fade-in-up">
                        <!-- Active Stream Section (Always shown if active) -->
                        <div id="active-stream-container" class="hidden mb-16">
                            <h3 class="text-sm font-mono tracking-[0.2em] text-accent-bright uppercase mb-6 flex items-center gap-2">
                                <span class="relative flex h-3 w-3">
                                  <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent opacity-75"></span>
                                  <span class="relative inline-flex rounded-full h-3 w-3 bg-accent"></span>
                                </span>
                                Now Playing
                            </h3>
                            <div class="flex flex-col md:flex-row gap-8 items-start">
                                <div class="w-full md:w-1/4 max-w-[200px] mx-auto md:mx-0">
                                    <div class="glass-card interactive-card aspect-[2/3] flex items-center justify-center">
                                        <img id="active-poster" src="" class="w-full h-full object-cover z-10 relative">
                                    </div>
                                </div>
                                <div class="w-full md:w-3/4 glass-card p-8 interactive-card">
                                    <h1 id="active-title" class="text-4xl font-semibold tracking-tight text-gradient mb-3"></h1>
                                    <p id="active-info" class="text-lg text-foreground-muted font-medium mb-8"></p>
                                    <div id="active-links" class="flex flex-col gap-3"></div>
                                </div>
                            </div>
                        </div>

                        <!-- Watch History Section -->
                        <div id="history-section">
                            <div class="flex items-center justify-between mb-8">
                                <h2 class="text-3xl md:text-4xl font-semibold text-gradient tracking-tight">Watch History</h2>
                            </div>
                            
                            <div id="history-loading" class="flex justify-center py-20">
                                <div class="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin"></div>
                            </div>
                            
                            <div id="history-list" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6 auto-rows-[auto]"></div>
                            <div id="history-empty" class="hidden text-center py-20">
                                <p class="text-foreground-muted italic">Your watch history is empty. Start watching something in the app!</p>
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
                        
                        <div id="search-results" class="flex flex-col gap-12"></div>
                    </div>

                    <div id="episode-view" class="hidden max-w-6xl mx-auto fade-in-up">
                        <button onclick="window.history.back()" class="mb-8 btn-secondary rounded-lg px-4 py-2 text-sm inline-flex items-center gap-2">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-4 h-4"><path stroke-linecap="round" stroke-linejoin="round" d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18" /></svg>
                            Back
                        </button>
                        
                        <div class="flex flex-col lg:flex-row gap-8 mb-12">
                            <div class="w-full lg:w-1/3 xl:w-1/4">
                                <div class="glass-card interactive-card aspect-[2/3] relative group overflow-hidden">
                                    <div class="spotlight"></div>
                                    <img id="meta-poster" src="" class="w-full h-full object-cover z-10 relative">
                                    <div id="meta-rating-badge" class="absolute top-4 right-4 z-20 bg-black/60 backdrop-blur-md px-3 py-1.5 rounded-lg border border-white/10 flex items-center gap-2 shadow-xl">
                                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-yellow-500"><path fill-rule="evenodd" d="M10.788 3.21c.448-1.077 1.976-1.077 2.424 0l2.082 5.007 5.404.433c1.164.093 1.636 1.545.749 2.305l-4.117 3.527 1.257 5.273c.271 1.136-.964 2.033-1.96 1.425L12 18.354 7.373 21.18c-.996.608-2.231-.29-1.96-1.425l1.257-5.273-4.117-3.527c-.887-.76-.415-2.212.749-2.305l5.404-.433 2.082-5.006z" clip-rule="evenodd" /></svg>
                                        <span id="meta-rating" class="text-sm font-bold text-white">N/A</span>
                                    </div>
                                </div>
                            </div>
                            
                            <div class="flex-grow">
                                <div class="glass-card p-8 h-full">
                                    <div class="flex flex-wrap items-center gap-3 mb-4">
                                        <span id="meta-year" class="px-2 py-1 rounded bg-white/5 text-xs font-mono text-foreground-muted"></span>
                                        <span id="meta-type" class="px-2 py-1 rounded bg-accent/10 text-xs font-mono text-accent-bright uppercase tracking-widest"></span>
                                        <span id="meta-content-rating" class="px-2 py-1 rounded border border-white/10 text-xs font-mono text-foreground-muted"></span>
                                        <span id="meta-duration" class="px-2 py-1 rounded bg-white/5 text-xs font-mono text-foreground-muted"></span>
                                    </div>
                                    <h2 id="metadata-title" class="text-4xl md:text-5xl font-bold text-gradient mb-6 tracking-tight"></h2>
                                    <p id="meta-plot" class="text-foreground-subtle text-lg leading-relaxed mb-8 max-w-3xl"></p>
                                    
                                    <div id="meta-tags" class="flex flex-wrap gap-2 mb-8"></div>
                                    
                                    <div id="imdb-link-container" class="hidden">
                                        <a id="imdb-link" href="" target="_blank" class="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-[#F5C518] text-black font-bold text-sm hover:scale-105 transition-transform">
                                            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" class="w-6 h-6"><path d="M22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12Z" fill="black"/><path d="M7 9H8.5V15H7V9ZM10 9H12.5C13.0523 9 13.5 9.44772 13.5 10V11H12V10.5H11.5V14.5H12V14H13.5V15H10V9ZM15 9H17.5C18.0523 9 18.5 9.44772 18.5 10V15H17V10.5H16.5V15H15V9Z" fill="white"/></svg>
                                            View on IMDb
                                        </a>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div class="glass-card p-8 mb-12">
                            <div id="season-selector-container" class="mb-10 hidden">
                                <label for="season-select" class="block text-xs font-mono tracking-widest text-foreground-muted mb-4 uppercase">Select Season</label>
                                <div id="season-tabs" class="flex flex-wrap gap-2"></div>
                            </div>

                            <div id="episodes-list" class="grid grid-cols-1 md:grid-cols-2 gap-4"></div>
                        </div>

                        <div id="actors-container" class="hidden">
                            <h3 class="text-xl font-bold text-gradient mb-8 tracking-tight">Cast & Crew</h3>
                            <div id="actors-list" class="flex gap-6 overflow-x-auto pb-6 no-scrollbar"></div>
                        </div>
                    </div>
                </main>

                <div id="global-loader" class="hidden fixed inset-0 z-[100] flex items-center justify-center bg-background-deep/70 backdrop-blur-xl">
                    <div class="flex flex-col items-center gap-6">
                        <div class="relative">
                            <div class="w-20 h-20 border-4 border-accent/20 rounded-full"></div>
                            <div class="absolute inset-0 w-20 h-20 border-4 border-accent border-t-transparent rounded-full animate-spin"></div>
                            <div class="absolute inset-2 w-16 h-16 border-4 border-accent-bright/10 border-b-accent-bright rounded-full animate-spin" style="animation-direction: reverse; animation-duration: 1.5s;"></div>
                        </div>
                        <div class="flex flex-col items-center gap-3">
                            <p class="text-accent-bright font-mono text-xs tracking-[0.3em] uppercase animate-pulse">Loading Content</p>
                            <div class="w-48 h-1 bg-white/5 rounded-full overflow-hidden relative">
                                <div class="absolute inset-0 bg-accent w-1/2 animate-[shimmer_1.5s_infinite_linear]"></div>
                            </div>
                        </div>
                    </div>
                </div>

                <div id="toast" class="fixed bottom-8 left-1/2 bg-background-elevated border border-white/10 text-foreground px-5 py-3 rounded-full shadow-[0_8px_30px_rgba(0,0,0,0.5),0_0_0_1px_rgba(255,255,255,0.05)] flex items-center gap-3 z-50 backdrop-blur-xl">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-5 h-5 text-accent-bright"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" /></svg>
                    <span id="toast-msg" class="text-sm font-medium">Success!</span>
                </div>

                <script>
                    function toggleLoader(show) {
                        const loader = document.getElementById('global-loader');
                        if (show) {
                            loader.classList.remove('hidden');
                        } else {
                            loader.classList.add('hidden');
                        }
                    }

                    function handleMouseMove(e, element) {
                        const rect = element.getBoundingClientRect();
                        const x = e.clientX - rect.left;
                        const y = e.clientY - rect.top;
                        element.style.setProperty('--mouse-x', `${'$'}{x}px`);
                        element.style.setProperty('--mouse-y', `${'$'}{y}px`);
                    }

                    const state = {
                        view: 'home',
                        searchQuery: '',
                        searchResults: [],
                        currentMetadata: null,
                        currentApiName: null,
                        isPolling: false,
                        pollInterval: null,
                        history: []
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
                            state.view = 'home';
                            await fetchHistory();
                            renderPlayerView();
                        }
                        
                        updateViewVisibility();
                    }

                    function updateViewVisibility() {
                        document.getElementById('home-view').classList.toggle('hidden', state.view !== 'home');
                        document.getElementById('search-view').classList.toggle('hidden', state.view !== 'search');
                        document.getElementById('episode-view').classList.toggle('hidden', state.view !== 'episodes');
                        
                        if (!state.isPolling) {
                            startPolling();
                        }
                    }

                    async function fetchHistory() {
                        const list = document.getElementById('history-list');
                        const loading = document.getElementById('history-loading');
                        const empty = document.getElementById('history-empty');
                        
                        loading.classList.remove('hidden');
                        list.innerHTML = '';
                        empty.classList.add('hidden');

                        try {
                            const res = await fetch('/api/history');
                            state.history = await res.json();
                            renderHistory();
                        } catch (e) {
                            console.error(e);
                        } finally {
                            loading.classList.add('hidden');
                        }
                    }

                    function renderHistory() {
                        const list = document.getElementById('history-list');
                        const empty = document.getElementById('history-empty');
                        
                        if (state.history.length === 0) {
                            empty.classList.remove('hidden');
                            return;
                        }

                        let html = '';
                        state.history.forEach((item, i) => {
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
                                        <div class="flex items-center justify-between mt-3">
                                            <span class="text-[10px] font-mono tracking-wider text-accent-bright truncate uppercase">${'$'}{escapeHtml(item.type || 'Media')}</span>
                                            <span class="text-[10px] font-mono text-foreground-muted">${'$'}{item.season ? 'S'+item.season : ''}${'$'}{item.episode ? 'E'+item.episode : ''}</span>
                                        </div>
                                    </div>
                                </div>
                            `;
                        });
                        list.innerHTML = html;
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
                        toggleLoader(true);
                        renderSearchView(); 

                        try {
                            const res = await fetch('/api/search?q=' + encodeURIComponent(query));
                            state.searchResults = await res.json();
                            renderSearchView();
                        } catch (e) {
                            console.error(e);
                            showToast("Search failed.");
                        } finally {
                            toggleLoader(false);
                        }
                    }

                    async function fetchMetadata(url, apiName) {
                        state.currentApiName = apiName;
                        toggleLoader(true);
                        
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
                        } finally {
                            toggleLoader(false);
                        }
                    }

                    function renderSearchView() {
                        const resultsContainer = document.getElementById('search-results');
                        resultsContainer.innerHTML = '';
                        
                        const categories = Object.keys(state.searchResults);
                        if (categories.length === 0) {
                            resultsContainer.innerHTML = '<div class="col-span-full text-center text-foreground-muted py-12">No results found.</div>';
                            return;
                        }

                        categories.forEach((category) => {
                            const items = state.searchResults[category];
                            if (!items || items.length === 0) return;

                            const section = document.createElement('div');
                            section.className = "flex flex-col gap-6";
                            
                            let itemsHtml = '';
                            items.forEach((item, i) => {
                                const escapedName = escapeHtml(item.name);
                                const posterUrl = item.posterUrl ? escapeHtml(item.posterUrl) : 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iNDUwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4=';
                                
                                itemsHtml += `
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
                                            <span class="text-xs font-mono tracking-wider text-accent-bright mt-3 truncate uppercase">${'$'}{escapeHtml(item.type)}</span>
                                        </div>
                                    </div>
                                `;
                            });

                            section.innerHTML = `
                                <h3 class="text-sm font-mono tracking-[0.2em] text-foreground-muted uppercase border-b border-white/5 pb-4">${'$'}{category}</h3>
                                <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6 auto-rows-[auto]">
                                    ${'$'}{itemsHtml}
                                </div>
                            `;
                            resultsContainer.appendChild(section);
                        });
                    }

                    function renderEpisodeView() {
                        const metadata = state.currentMetadata;
                        document.getElementById('metadata-title').innerText = metadata.title;
                        document.getElementById('meta-poster').src = metadata.poster || 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iNDUwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4=';
                        document.getElementById('meta-plot').innerText = metadata.plot || 'No description available.';
                        document.getElementById('meta-rating').innerText = metadata.score ? metadata.score.toFixed(1) : 'N/A';
                        document.getElementById('meta-year').innerText = metadata.year || 'N/A';
                        document.getElementById('meta-type').innerText = metadata.type || 'N/A';
                        document.getElementById('meta-content-rating').innerText = metadata.contentRating || 'NR';
                        document.getElementById('meta-duration').innerText = metadata.duration ? metadata.duration + ' min' : '';
                        
                        const imdbContainer = document.getElementById('imdb-link-container');
                        if (metadata.imdbId) {
                            imdbContainer.classList.remove('hidden');
                            document.getElementById('imdb-link').href = 'https://www.imdb.com/title/' + metadata.imdbId;
                        } else {
                            imdbContainer.classList.add('hidden');
                        }

                        const tagsContainer = document.getElementById('meta-tags');
                        tagsContainer.innerHTML = '';
                        if (metadata.tags) {
                            metadata.tags.forEach(tag => {
                                const span = document.createElement('span');
                                span.className = "px-2 py-1 rounded-full bg-white/5 text-[10px] font-medium text-foreground-muted border border-white/5";
                                span.innerText = tag;
                                tagsContainer.appendChild(span);
                            });
                        }

                        const actorsContainer = document.getElementById('actors-container');
                        const actorsList = document.getElementById('actors-list');
                        if (metadata.actors && metadata.actors.length > 0) {
                            actorsContainer.classList.remove('hidden');
                            actorsList.innerHTML = '';
                            metadata.actors.forEach(actor => {
                                const item = document.createElement('div');
                                item.className = "flex-shrink-0 flex flex-col items-center gap-3 w-20";
                                const imgUrl = actor.image || 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxMDAiIGhlaWdodD0iMTAwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4=';
                                item.innerHTML = `
                                    <div class="w-20 h-20 rounded-full overflow-hidden border border-white/10 shadow-lg">
                                        <img src="${'$'}{imgUrl}" class="w-full h-full object-cover">
                                    </div>
                                    <div class="text-center">
                                        <p class="text-[10px] font-bold text-foreground line-clamp-1">${'$'}{escapeHtml(actor.name)}</p>
                                        <p class="text-[9px] text-foreground-muted line-clamp-1">${'$'}{escapeHtml(actor.role || '')}</p>
                                    </div>
                                `;
                                actorsList.appendChild(item);
                            });
                        } else {
                            actorsContainer.classList.add('hidden');
                        }

                        const seasons = new Set();
                        let hasValidSeason = false;
                        metadata.episodes.forEach(ep => {
                            if (ep.season != null) {
                                seasons.add(ep.season);
                                hasValidSeason = true;
                            }
                        });

                        const seasonContainer = document.getElementById('season-selector-container');
                        const seasonTabs = document.getElementById('season-tabs');
                        
                        if (hasValidSeason && seasons.size > 0) {
                            seasonContainer.classList.remove('hidden');
                            seasonTabs.innerHTML = '';
                            const sortedSeasons = Array.from(seasons).sort((a, b) => a - b);
                            sortedSeasons.forEach(s => {
                                const btn = document.createElement('button');
                                btn.className = "season-tab px-4 py-2 rounded-lg bg-white/5 border border-white/5 text-sm font-medium transition-all hover:bg-white/10";
                                btn.innerText = 'Season ' + s;
                                btn.onclick = () => {
                                    document.querySelectorAll('.season-tab').forEach(b => b.classList.remove('bg-accent', 'text-white', 'border-accent'));
                                    btn.classList.add('bg-accent', 'text-white', 'border-accent');
                                    renderEpisodesForSeason(s);
                                };
                                seasonTabs.appendChild(btn);
                                if (s === sortedSeasons[0]) btn.click();
                            });
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
                            const container = document.createElement('div');
                            container.className = "glass-card p-4 flex gap-4 group cursor-pointer hover:bg-white/[0.05] transition-all duration-300";
                            container.style.animation = `fadeInUp 0.3s cubic-bezier(0.16, 1, 0.3, 1) ${'$'}{i * 0.03}s forwards`;
                            container.style.opacity = '0';
                            container.onclick = () => playEpisode(ep.data, state.currentApiName, state.currentMetadata.title, state.currentMetadata.poster, ep.season, ep.episode, ep.name);

                            const posterUrl = ep.posterUrl || 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iMTcwIiBmaWxsPSIjMGEwYTBjIj48L3N2Zz4=';
                            const epNum = ep.episode != null ? `E${'$'}{ep.episode}` : '';
                            const rating = ep.score ? `<div class="flex items-center gap-1 text-yellow-500 text-[10px] font-bold"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-3 h-3"><path fill-rule="evenodd" d="M10.788 3.21c.448-1.077 1.976-1.077 2.424 0l2.082 5.007 5.404.433c1.164.093 1.636 1.545.749 2.305l-4.117 3.527 1.257 5.273c.271 1.136-.964 2.033-1.96 1.425L12 18.354 7.373 21.18c-.996.608-2.231-.29-1.96-1.425l1.257-5.273-4.117-3.527c-.887-.76-.415-2.212.749-2.305l5.404-.433 2.082-5.006z" clip-rule="evenodd" /></svg>${'$'}{ep.score.toFixed(1)}</div>` : '';

                            container.innerHTML = `
                                <div class="w-32 h-20 flex-shrink-0 rounded-lg overflow-hidden relative border border-white/5 shadow-inner">
                                    <img src="${'$'}{posterUrl}" class="w-full h-full object-cover group-hover:scale-110 transition-transform duration-500">
                                    <div class="absolute inset-0 bg-black/20 group-hover:bg-black/0 transition-colors"></div>
                                    <div class="absolute bottom-1 right-1 bg-black/60 backdrop-blur-md px-1.5 py-0.5 rounded text-[9px] font-mono text-white border border-white/10">${'$'}{epNum}</div>
                                </div>
                                <div class="flex-grow flex flex-col justify-center overflow-hidden">
                                    <div class="flex items-center justify-between gap-2 mb-1">
                                        <h4 class="font-bold text-sm text-foreground truncate group-hover:text-accent-bright transition-colors">${'$'}{escapeHtml(ep.name)}</h4>
                                        ${'$'}{rating}
                                    </div>
                                    <p class="text-[11px] text-foreground-muted line-clamp-2 leading-tight">${'$'}{escapeHtml(ep.description || 'No description available.')}</p>
                                </div>
                                <div class="flex items-center ml-2">
                                    <div class="w-8 h-8 rounded-full bg-white/5 flex items-center justify-center group-hover:bg-accent group-hover:shadow-[0_0_15px_rgba(94,106,210,0.5)] transition-all">
                                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white ml-0.5"><path fill-rule="evenodd" d="M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z" clip-rule="evenodd" /></svg>
                                    </div>
                                </div>
                            `;
                            episodesList.appendChild(container);
                        });
                    }

                    async function playEpisode(dataUrl, apiName, title, poster, season, episode, name) {
                        toggleLoader(true);
                        try {
                            const res = await fetch('/api/load_links', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ data: dataUrl, apiName: apiName, title: title, poster: poster, season: season, episode: episode, episodeName: name })
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
                        } finally {
                            toggleLoader(false);
                        }
                    }

                    function renderPlayerView() {
                        updatePoll();
                    }

                    async function updatePoll() {
                        try {
                            const res = await fetch('/api/current_stream');
                            const data = await res.json();
                            
                            const container = document.getElementById('active-stream-container');
                            const titleEl = document.getElementById('active-title');
                            const posterEl = document.getElementById('active-poster');
                            const infoEl = document.getElementById('active-info');
                            const linksList = document.getElementById('active-links');
                            
                            if (data.error) {
                                container.classList.add('hidden');
                                return;
                            }
                            
                            container.classList.remove('hidden');
                            titleEl.innerText = data.title || 'Unknown Title';
                            
                            if (data.poster) {
                                posterEl.src = data.poster;
                                posterEl.classList.remove('hidden');
                            } else {
                                posterEl.classList.add('hidden');
                            }
                            
                            let infoText = '';
                            if (data.season) infoText += '<span class="bg-white/10 px-2 py-0.5 rounded text-sm mr-2">Season ' + data.season + '</span>';
                            if (data.episode) infoText += '<span class="bg-white/10 px-2 py-0.5 rounded text-sm mr-2">Episode ' + data.episode + '</span>';
                            if (data.episodeName) infoText += '<span class="text-foreground-muted text-sm italic">' + escapeHtml(data.episodeName) + '</span>';
                            infoEl.innerHTML = infoText;
                            
                            if (data.links && data.links.length > 0) {
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
