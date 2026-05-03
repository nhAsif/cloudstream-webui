package com.lagradost.cloudstream3.utils.webui

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CurrentStreamManager {
    data class StreamData(
        val title: String?,
        val poster: String?,
        val episode: Int?,
        val season: Int?,
        val links: List<ExtractorLink>,
        val subs: List<SubtitleData>,
        val episodeName: String? = null
    )

    private val _currentStream = MutableStateFlow<StreamData?>(null)
    val currentStream: StateFlow<StreamData?> = _currentStream

    fun updateStream(
        title: String?,
        poster: String?,
        episode: Int?,
        season: Int?,
        links: List<ExtractorLink>,
        subs: List<SubtitleData>,
        episodeName: String? = null
    ) {
        _currentStream.value = StreamData(title, poster, episode, season, links, subs, episodeName)
    }
}
