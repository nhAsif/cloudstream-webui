## Local WebUI Cast

This project includes an embedded Ktor server that allows remote monitoring and desktop playback of active streams.

### Architecture
- **CurrentStreamManager**: A singleton bridging the video player to the WebUI. It uses `MutableStateFlow` to store titles, posters, and streaming links.
- **WebUIServer**: A Ktor-based server (port 8945) that serves:
    - `/`: A responsive HTML5 dashboard.
    - `/api/current_stream`: JSON metadata for external integrations.
    - `/play.m3u`: Dynamically generated M3U playlists for VLC/PotPlayer.
- **NetUtils**: Helper for IPv4 discovery on local networks.

### Guidelines
- When modifying the player logic (`GeneratorPlayer.kt`), ensure `CurrentStreamManager.updateData(...)` is called with new link data.
- The server lifecycle is tied to `MainActivity` and user preferences (`webui_server_enable`).
- Ktor version is locked to 2.3.12 (CIO) for Java 8 compatibility.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
