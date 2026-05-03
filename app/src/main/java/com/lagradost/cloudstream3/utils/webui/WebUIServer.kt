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
                <script src="https://cdn.tailwindcss.com"></script>
                <script>
                    tailwind.config = {
                        darkMode: 'class',
                        theme: {
                            extend: {
                                colors: {
                                    primary: '#e50914',
                                    background: '#121212',
                                    surface: '#1e1e1e',
                                }
                            }
                        }
                    }
                </script>
                <style>
                    body { background-color: #121212; color: #ffffff; }
                    .no-scrollbar::-webkit-scrollbar { display: none; }
                    .no-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
                    #toast { visibility: hidden; opacity: 0; transition: visibility 0s 0.5s, opacity 0.5s linear; }
                    #toast.show { visibility: visible; opacity: 1; transition: opacity 0.5s linear; }
                </style>
            </head>
            <body class="bg-background text-white font-sans antialiased min-h-screen flex flex-col">
                <nav class="bg-surface shadow-md py-4 px-6 flex justify-between items-center sticky top-0 z-10">
                    <div class="text-xl font-bold text-primary flex items-center gap-2 cursor-pointer" onclick="toggleView(false); update()">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-6 h-6"><path stroke-linecap="round" stroke-linejoin="round" d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347c-.75.412-1.667-.13-1.667-.986V5.653Z" /></svg>
                        Cloudstream WebUI
                    </div>
                    <div class="flex gap-2 w-full max-w-md ml-4">
                        <input type="text" id="search-input" placeholder="Search movies, series, anime..." class="w-full bg-background border border-gray-700 rounded-lg px-4 py-2 focus:outline-none focus:border-primary transition-colors" onkeyup="if(event.key === 'Enter') search()">
                        <button onclick="search()" class="bg-primary hover:bg-red-700 text-white font-bold py-2 px-4 rounded-lg transition-colors">Search</button>
                    </div>
                </nav>

                <main class="flex-grow container mx-auto px-4 py-8 max-w-7xl">
                    <div id="player-view" class="flex flex-col md:flex-row gap-8 items-start justify-center">
                        <div class="w-full md:w-1/3 max-w-sm mx-auto">
                            <div class="bg-surface rounded-xl overflow-hidden shadow-lg border border-gray-800 relative aspect-[2/3] flex items-center justify-center">
                                <img id="poster" src="" alt="Poster" class="w-full h-full object-cover hidden">
                                <div id="poster-placeholder" class="text-gray-500 flex flex-col items-center">
                                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-16 h-16 mb-2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 20.25h12m-7.5-3v3m3-3v3m-10.125-3h17.25c.621 0 1.125-.504 1.125-1.125V4.875c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125Z" /></svg>
                                    <span>No Active Stream</span>
                                </div>
                            </div>
                        </div>
                        
                        <div class="w-full md:w-2/3 bg-surface p-6 rounded-xl shadow-lg border border-gray-800">
                            <h1 id="title" class="text-3xl font-bold mb-2 text-gray-100">Loading...</h1>
                            <p id="info" class="text-lg text-primary font-semibold mb-6"></p>
                            
                            <div id="links-container" class="hidden">
                                <h3 class="text-xl font-semibold mb-4 border-b border-gray-700 pb-2">Play on Desktop</h3>
                                <div id="links" class="flex flex-col gap-4"></div>
                            </div>
                            <div id="no-links-msg" class="text-gray-400 italic">Push a stream from the app or search to start playing.</div>
                        </div>
                    </div>

                    <div id="search-view" class="hidden">
                        <div class="flex items-center justify-between mb-6">
                            <h2 id="search-query-title" class="text-2xl font-bold">Search Results</h2>
                            <button onclick="toggleView(false)" class="text-gray-400 hover:text-white flex items-center gap-1 transition-colors">
                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M9 15 3 9m0 0 6-6M3 9h12a6 6 0 0 1 0 12h-3" /></svg>
                                Back to Player
                            </button>
                        </div>
                        
                        <div id="search-results-loading" class="hidden flex justify-center py-12">
                            <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary"></div>
                        </div>
                        
                        <div id="search-results" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4"></div>
                    </div>

                    <div id="episode-view" class="hidden max-w-4xl mx-auto">
                        <button onclick="document.getElementById('episode-view').classList.add('hidden'); document.getElementById('search-view').classList.remove('hidden');" class="mb-4 text-gray-400 hover:text-white flex items-center gap-1 transition-colors">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M9 15 3 9m0 0 6-6M3 9h12a6 6 0 0 1 0 12h-3" /></svg>
                            Back to Results
                        </button>
                        
                        <div class="bg-surface p-6 rounded-xl shadow-lg border border-gray-800">
                            <h2 id="metadata-title" class="text-3xl font-bold mb-6">Loading...</h2>
                            
                            <div id="season-selector-container" class="mb-6 hidden">
                                <label for="season-select" class="block text-sm font-medium text-gray-400 mb-2">Select Season</label>
                                <select id="season-select" onchange="renderEpisodesForSeason(this.value)" class="bg-background border border-gray-700 text-white text-sm rounded-lg focus:ring-primary focus:border-primary block w-full p-2.5 outline-none"></select>
                            </div>

                            <div id="episodes-list" class="flex flex-col gap-2 max-h-[60vh] overflow-y-auto pr-2 no-scrollbar"></div>
                        </div>
                    </div>
                </main>

                <div id="toast" class="fixed bottom-10 left-1/2 transform -translate-x-1/2 bg-gray-800 text-white px-6 py-3 rounded-full shadow-xl flex items-center gap-2 z-50">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-6 h-6 text-green-400"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" /></svg>
                    <span id="toast-msg">Success!</span>
                </div>

                <script>
                    let isSearching = false;
                    let currentMetadata = null;
                    let currentApiName = null;

                    function escapeHtml(unsafe) {
                        if (unsafe == null) return '';
                        return unsafe.toString()
                             .replace(/&/g, "&amp;")
                             .replace(/</g, "&lt;")
                             .replace(/>/g, "&gt;")
                             .replace(/"/g, "&quot;")
                             .replace(/'/g, "&#039;");
                    }

                    function toggleView(searching) {
                        isSearching = searching;
                        const playerView = document.getElementById('player-view');
                        const searchView = document.getElementById('search-view');
                        const episodeView = document.getElementById('episode-view');
                        
                        if (searching) {
                            playerView.classList.add('hidden');
                            searchView.classList.remove('hidden');
                            episodeView.classList.add('hidden');
                        } else {
                            playerView.classList.remove('hidden');
                            searchView.classList.add('hidden');
                            episodeView.classList.add('hidden');
                            update();
                        }
                    }

                    async function search() {
                        const query = document.getElementById('search-input').value;
                        if (!query) return;
                        
                        toggleView(true);
                        document.getElementById('search-query-title').innerText = 'Searching for "' + query + '"...';
                        
                        const resultsContainer = document.getElementById('search-results');
                        const loadingIndicator = document.getElementById('search-results-loading');
                        
                        resultsContainer.innerHTML = '';
                        loadingIndicator.classList.remove('hidden');
                        
                        try {
                            const res = await fetch('/api/search?q=' + encodeURIComponent(query));
                            const results = await res.json();
                            
                            loadingIndicator.classList.add('hidden');
                            
                            if (results.length === 0) {
                                resultsContainer.innerHTML = '<div class="col-span-full text-center text-gray-400 py-8">No results found.</div>';
                            } else {
                                let html = '';
                                results.forEach(item => {
                                    const escapedUrl = escapeHtml(item.url);
                                    const escapedApiName = escapeHtml(item.apiName);
                                    const escapedName = escapeHtml(item.name);
                                    const posterUrl = item.posterUrl ? escapeHtml(item.posterUrl) : 'https://via.placeholder.com/300x450/1e1e1e/888888?text=No+Poster';
                                    
                                    html += `
                                        <div class="bg-surface border border-gray-800 rounded-lg overflow-hidden cursor-pointer hover:scale-105 hover:border-primary transition-all duration-200 shadow-md flex flex-col group" onclick="loadEpisodes('${'$'}{escapedUrl}', '${'$'}{escapedApiName}')">
                                            <div class="relative aspect-[2/3] w-full bg-gray-800">
                                                <img src="${'$'}{posterUrl}" onerror="this.src='https://via.placeholder.com/300x450/1e1e1e/888888?text=No+Poster'" class="w-full h-full object-cover">
                                                <div class="absolute inset-0 bg-black bg-opacity-0 group-hover:bg-opacity-40 transition-opacity flex items-center justify-center">
                                                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-12 h-12 text-white opacity-0 group-hover:opacity-100 transition-opacity"><path stroke-linecap="round" stroke-linejoin="round" d="M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" /><path stroke-linecap="round" stroke-linejoin="round" d="M15.91 11.672a.375.375 0 0 1 0 .656l-5.603 3.113a.375.375 0 0 1-.557-.328V8.887c0-.286.307-.466.557-.327l5.603 3.112Z" /></svg>
                                                </div>
                                            </div>
                                            <div class="p-3 flex-grow flex flex-col justify-between">
                                                <h3 class="font-semibold text-sm line-clamp-2" title="${'$'}{escapedName}">${'$'}{escapedName}</h3>
                                                <span class="text-xs text-gray-400 mt-2 truncate">${'$'}{escapedApiName}</span>
                                            </div>
                                        </div>
                                    `;
                                });
                                resultsContainer.innerHTML = html;
                            }
                            document.getElementById('search-query-title').innerText = 'Results for "' + query + '"';
                        } catch (e) {
                            console.error(e);
                            loadingIndicator.classList.add('hidden');
                            resultsContainer.innerHTML = '<div class="col-span-full text-center text-red-400 py-8">Error performing search. Please check backend logs.</div>';
                        }
                    }

                    async function loadEpisodes(url, apiName) {
                        showToast("Loading metadata...");
                        
                        document.getElementById('search-view').classList.add('hidden');
                        const episodeView = document.getElementById('episode-view');
                        episodeView.classList.remove('hidden');
                        
                        document.getElementById('metadata-title').innerText = 'Loading metadata...';
                        document.getElementById('season-selector-container').classList.add('hidden');
                        document.getElementById('episodes-list').innerHTML = '<div class="flex justify-center py-8"><div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div></div>';

                        try {
                            const res = await fetch(`/api/load?url=${'$'}{encodeURIComponent(url)}&apiName=${'$'}{encodeURIComponent(apiName)}`);
                            const metadata = await res.json();
                            
                            if (metadata.error) {
                                showToast("Error: " + metadata.error);
                                episodeView.classList.add('hidden');
                                document.getElementById('search-view').classList.remove('hidden');
                                return;
                            }
                            
                            currentMetadata = metadata;
                            currentApiName = apiName;
                            
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
                            
                        } catch (e) {
                            console.error(e);
                            showToast("Failed to load metadata.");
                            episodeView.classList.add('hidden');
                            document.getElementById('search-view').classList.remove('hidden');
                        }
                    }

                    function renderEpisodesForSeason(seasonVal) {
                        if (!currentMetadata || !currentMetadata.episodes) return;
                        
                        const episodesList = document.getElementById('episodes-list');
                        episodesList.innerHTML = '';
                        
                        let filteredEpisodes = currentMetadata.episodes;
                        
                        if (seasonVal !== null) {
                            const s = parseInt(seasonVal, 10);
                            filteredEpisodes = currentMetadata.episodes.filter(ep => ep.season === s);
                        }

                        filteredEpisodes.forEach(ep => {
                            const epName = escapeHtml(ep.name);
                            const epData = ep.data; // Keep original for pushing
                            
                            const btn = document.createElement('button');
                            btn.className = "flex items-center justify-between bg-gray-800 hover:bg-gray-700 p-4 rounded-lg transition-colors border border-gray-700 hover:border-gray-500 text-left w-full group";
                            
                            const epInfo = ep.episode != null ? `<span class="text-xs font-mono bg-gray-700 group-hover:bg-gray-600 px-2 py-1 rounded text-gray-300 mr-3">E${'$'}{ep.episode}</span>` : '';
                            
                            btn.innerHTML = `
                                <div class="flex items-center flex-grow overflow-hidden">
                                    ${'$'}{epInfo}
                                    <span class="font-medium truncate">${'$'}{epName}</span>
                                </div>
                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="w-6 h-6 text-primary flex-shrink-0 ml-2 group-hover:scale-110 transition-transform"><path stroke-linecap="round" stroke-linejoin="round" d="M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" /><path stroke-linecap="round" stroke-linejoin="round" d="M15.91 11.672a.375.375 0 0 1 0 .656l-5.603 3.113a.375.375 0 0 1-.557-.328V8.887c0-.286.307-.466.557-.327l5.603 3.112Z" /></svg>
                            `;
                            
                            btn.addEventListener('click', () => {
                                playEpisode(epData, currentApiName, currentMetadata.title, currentMetadata.poster, ep.season, ep.episode);
                            });
                            
                            episodesList.appendChild(btn);
                        });
                        
                        if (filteredEpisodes.length === 0) {
                            episodesList.innerHTML = '<div class="text-center text-gray-400 py-4">No episodes found for this season.</div>';
                        }
                    }

                    async function playEpisode(dataUrl, apiName, title, poster, season, episode) {
                        showToast("Pushing stream to app... Loading links...");
                        try {
                            const res = await fetch('/api/load_links', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ data: dataUrl, apiName: apiName, title: title, poster: poster, season: season, episode: episode })
                            });
                            const playRes = await res.json();
                            
                            if (playRes.error) {
                                showToast("Error: " + playRes.error);
                                return;
                            }
                            
                            if (playRes.success) {
                                showToast("Stream pushed to app!");
                                toggleView(false);
                                update();
                            }
                        } catch (e) {
                            console.error(e);
                            showToast("Failed to load links.");
                        }
                    }

                    function showToast(text) {
                        const toast = document.getElementById("toast");
                        const msg = document.getElementById("toast-msg");
                        msg.innerText = text;
                        
                        toast.classList.remove("show");
                        void toast.offsetWidth;
                        
                        toast.classList.add("show");
                        setTimeout(() => {
                            toast.classList.remove("show");
                        }, 3000);
                    }

                    function fallbackCopyTextToClipboard(text) {
                        const textArea = document.createElement("textarea");
                        textArea.value = text;
                        textArea.style.top = "0";
                        textArea.style.left = "0";
                        textArea.style.position = "fixed";
                        document.body.appendChild(textArea);
                        textArea.focus();
                        textArea.select();
                        try {
                            const successful = document.execCommand('copy');
                            showToast(successful ? "Copied!" : "Failed to copy");
                        } catch (err) {
                            showToast("Error copying");
                        }
                        document.body.removeChild(textArea);
                    }

                    function copyToClipboard(text) {
                        if (!navigator.clipboard || !navigator.clipboard.writeText) {
                            fallbackCopyTextToClipboard(text);
                            return;
                        }
                        navigator.clipboard.writeText(text).then(() => {
                            showToast("Copied to clipboard!");
                        }).catch(err => {
                            fallbackCopyTextToClipboard(text);
                        });
                    }

                    async function update() {
                        if (isSearching) return;
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
                                titleEl.innerText = 'No Stream Active';
                                posterEl.classList.add('hidden');
                                posterPlaceholder.classList.remove('hidden');
                                infoEl.innerText = '';
                                linksContainer.classList.add('hidden');
                                noLinksMsg.classList.remove('hidden');
                                return;
                            }
                            
                            titleEl.innerText = data.title || 'Unknown Title';
                            
                            if (data.poster) {
                                posterEl.src = data.poster;
                                posterEl.classList.remove('hidden');
                                posterPlaceholder.classList.add('hidden');
                            } else {
                                posterEl.classList.add('hidden');
                                posterPlaceholder.classList.remove('hidden');
                            }
                            
                            let infoText = '';
                            if (data.season) infoText += 'Season ' + data.season + ' ';
                            if (data.episode) infoText += 'Episode ' + data.episode;
                            infoEl.innerText = infoText;
                            
                            if (data.links && data.links.length > 0) {
                                linksContainer.classList.remove('hidden');
                                noLinksMsg.classList.add('hidden');
                                
                                linksList.innerHTML = '';
                                data.links.forEach((link, index) => {
                                    const m3uUrl = window.location.origin + '/play.m3u?index=' + index;
                                    const linkName = escapeHtml(link.name || 'Unknown');
                                    const quality = escapeHtml(link.quality || 'Auto');
                                    
                                    const item = document.createElement('div');
                                    item.className = "bg-gray-800 p-4 rounded-lg flex flex-col xl:flex-row gap-4 items-start xl:items-center justify-between border border-gray-700";
                                    item.innerHTML = `
                                        <div class="flex flex-col">
                                            <span class="font-bold text-white">${'$'}{linkName}</span>
                                            <span class="text-sm text-primary font-mono">${'$'}{quality}</span>
                                        </div>
                                        <div class="flex flex-wrap gap-2 w-full xl:w-auto">
                                            <a href="vlc://${'$'}{m3uUrl}" class="flex-grow xl:flex-grow-0 text-center bg-orange-600 hover:bg-orange-700 text-white px-3 py-2 rounded font-medium transition-colors text-sm flex items-center justify-center gap-1">
                                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-4 h-4"><path stroke-linecap="round" stroke-linejoin="round" d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347c-.75.412-1.667-.13-1.667-.986V5.653Z" /></svg>
                                                VLC
                                            </a>
                                            <button onclick="copyToClipboard('${'$'}{m3uUrl}')" class="flex-grow xl:flex-grow-0 bg-gray-700 hover:bg-gray-600 text-white px-3 py-2 rounded font-medium transition-colors text-sm flex items-center justify-center gap-1 border border-gray-600">
                                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-4 h-4 text-gray-300"><path stroke-linecap="round" stroke-linejoin="round" d="M15.666 3.888A2.25 2.25 0 0 0 13.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 0 1-.75.75H9a.75.75 0 0 1-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 0 1-2.25 2.25H6.75A2.25 2.25 0 0 1 4.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 0 1 1.927-.184" /></svg>
                                                M3U
                                            </button>
                                            <button onclick="copyToClipboard('${'$'}{escapeHtml(link.url)}')" class="flex-grow xl:flex-grow-0 bg-gray-700 hover:bg-gray-600 text-white px-3 py-2 rounded font-medium transition-colors text-sm flex items-center justify-center gap-1 border border-gray-600">
                                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-4 h-4 text-gray-300"><path stroke-linecap="round" stroke-linejoin="round" d="M13.19 8.688a4.5 4.5 0 0 1 1.242 7.244l-4.5 4.5a4.5 4.5 0 0 1-6.364-6.364l1.757-1.757m13.35-.622 1.757-1.757a4.5 4.5 0 0 0-6.364-6.364l-4.5 4.5a4.5 4.5 0 0 0 1.242 7.244" /></svg>
                                                URL
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
                    setInterval(update, 5000);
                    update();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
