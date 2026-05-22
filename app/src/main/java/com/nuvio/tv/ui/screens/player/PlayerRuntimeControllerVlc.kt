package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.MediaPlayer

internal fun PlayerRuntimeController.attachVlcView(view: NuvioVlcSurfaceView?) {
    if (vlcView === view) {
        return
    }

    // Clear callbacks from old view
    vlcView?.let { oldView ->
        oldView.onPlaybackStateChanged = null
        oldView.onBufferingChanged = null
        oldView.onTimeChanged = null
        oldView.onLengthChanged = null
        oldView.onEndReached = null
        oldView.onError = null
        oldView.onFirstFrameRendered = null
        oldView.onTracksChanged = null
    }

    vlcView = view

    // Setup event callbacks on new view
    view?.let { setupVlcEventCallbacks(it) }
}

private fun PlayerRuntimeController.setupVlcEventCallbacks(view: NuvioVlcSurfaceView) {
    var lastBufferingPercentBucket = -1

    view.onPlaybackStateChanged = { isPlaying ->
        Log.d(PlayerRuntimeController.TAG, "[VLC] onPlaybackStateChanged: isPlaying=$isPlaying")
        _uiState.update { state ->
            state.copy(
                isPlaying = isPlaying,
                isBuffering = if (isPlaying) false else state.isBuffering
            )
        }
        if (isPlaying && !hasRenderedFirstFrame) {
            hasRenderedFirstFrame = true
            _uiState.update { it.copy(showLoadingOverlay = false) }
        }
    }

    view.onBufferingChanged = { bufferPercent ->
        val normalizedPercent = bufferPercent.coerceIn(0f, 100f)
        val percentBucket = normalizedPercent.toInt()
        val isBuffering = normalizedPercent < 100f
        if (percentBucket != lastBufferingPercentBucket || _uiState.value.isBuffering != isBuffering) {
            lastBufferingPercentBucket = percentBucket
            _uiState.update { state ->
                state.copy(
                    isBuffering = isBuffering,
                    loadingProgress = normalizedPercent / 100f
                )
            }
        }
    }

    view.onTimeChanged = { timeMs ->
        if (pendingPreviewSeekPosition == null) {
            val duration = view.getDuration()
            if (duration > 0) {
                updatePlaybackTimeline(currentPosition = timeMs, duration = duration)
            }
        }
    }

    view.onLengthChanged = { lengthMs ->
        if (lengthMs > lastKnownDuration) {
            lastKnownDuration = lengthMs
        }
        updatePlaybackTimeline(duration = lengthMs)

        applyPendingVlcSeekIfNeeded(view, durationMs = lengthMs)

        // Update tracks and apply preferences in a single coroutine to avoid race conditions
        enqueueVlcTrackRefresh { 
            // Update tracks when duration is known (media is parsed)
            updateVlcAvailableTracks()

            // Apply persisted track preferences and auto-select subtitles
            val state = _uiState.value
            applyPersistedTrackPreference(
                audioTracks = state.audioTracks,
                subtitleTracks = state.subtitleTracks
            )
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        }
    }

    view.onEndReached = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onEndReached")
        _uiState.update { it.copy(playbackEnded = true, isPlaying = false) }
        emitCompletionScrobbleStop(progressPercent = 99.5f)
        saveWatchProgress()
        resetPostPlayStateAfterPlaybackEnded()
    }

    view.onError = { message ->
        Log.e(PlayerRuntimeController.TAG, "[VLC] onError: $message")
        _uiState.update { state ->
            state.copy(
                error = message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed),
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }

    view.onFirstFrameRendered = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onFirstFrameRendered")
        hasRenderedFirstFrame = true
        applyPendingVlcSeekIfNeeded(view)
        _uiState.update { state ->
            state.copy(
                showLoadingOverlay = false,
                isBuffering = false,
                loadingProgress = if (state.loadingProgress != null) 1f else null
            )
        }
    }

    view.onTracksChanged = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onTracksChanged")
        // Launch coroutine to avoid race conditions with onLengthChanged track updates
        enqueueVlcTrackRefresh {
            updateVlcAvailableTracks()
        }
    }
}

private fun PlayerRuntimeController.enqueueVlcTrackRefresh(
    block: suspend () -> Unit
) {
    vlcTrackRefreshJob?.cancel()
    vlcTrackRefreshJob = scope.launch {
        block()
    }
}

internal suspend fun PlayerRuntimeController.initializeVlcPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    // Release ExoPlayer resources
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    try {
        currentMediaSession?.release()
    } catch (_: Exception) {}
    currentMediaSession = null
    notifyAudioSessionUpdate(false)

    val view = vlcView
    if (view == null) {
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        // Setup event callbacks (in case view was attached before engine was set)
        setupVlcEventCallbacks(view)

        // Yield to allow UI updates before heavy operations
        yield()

        // Following the official example: attachViews() BEFORE setMedia()
        view.attachMediaPlayer()
        view.setMedia(url, headers)

        // Apply playback settings
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)

        // Apply preferred audio language preferences
        view.applyAudioLanguagePreferences(preferredAudioLanguages)

        // Start playback
        view.setPaused(startPaused)

        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = !startPaused,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()

        // Event-based updates are handled by callbacks, but we still need progress updates for skip intervals etc.
        startProgressUpdates()
        startWatchProgressSaving()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libVLC initialize failed: ${error.message}", error)
        val detailedError = error.message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed)
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return@onFailure
        }
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseVlcPlayer() {
    vlcTrackRefreshJob?.cancel()
    vlcTrackRefreshJob = null

    // Clear callbacks first
    vlcView?.let { view ->
        view.onPlaybackStateChanged = null
        view.onBufferingChanged = null
        view.onTimeChanged = null
        view.onLengthChanged = null
        view.onEndReached = null
        view.onError = null
        view.onFirstFrameRendered = null
        view.onTracksChanged = null
    }

    runCatching {
        vlcView?.detachMediaPlayer()
        vlcView?.release()
    }
}

internal fun PlayerRuntimeController.updateVlcAvailableTracks() {
    val view = vlcView ?: return

    try {
        // Get audio tracks using typed API
        val vlcAudioTracks = view.getAudioTracks() ?: emptyArray()
        Log.d(PlayerRuntimeController.TAG, "[VLC] Raw audio tracks count: ${vlcAudioTracks.size}")

        val selectedAudioTrack = view.getSelectedAudioTrack()
        val selectedAudioId = selectedAudioTrack?.id
        Log.d(PlayerRuntimeController.TAG, "[VLC] Selected audio track ID: $selectedAudioId")

        val audioTracks = vlcAudioTracks.mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                // For audio tracks, try to get channel info from AudioTrack subclass
                if (track is IMedia.AudioTrack) {
                    track.channels.takeIf { it > 0 }?.let { add("${it}ch") }
                }
            }.joinToString(" ")

            val displayName = if (codecSuffix.isBlank()) {
                track.name ?: track.description ?: "Audio ${index + 1}"
            } else {
                "${track.name ?: track.description ?: "Audio ${index + 1}"} ($codecSuffix)"
            }

            Log.d(PlayerRuntimeController.TAG, "[VLC] Audio track $index: id=${track.id} name=${track.name} lang=${track.language}")
            TrackInfo(
                index = index,
                name = displayName,
                language = track.language,
                trackId = track.id,
                codec = track.codec,
                channelCount = if (track is IMedia.AudioTrack) track.channels else null,
                isSelected = track.id == selectedAudioId || track.selected,
                isForced = false
            )
        }

        // Get subtitle tracks using typed API
        val vlcSubtitleTracks = view.getSubtitleTracks() ?: emptyArray()
        Log.d(PlayerRuntimeController.TAG, "[VLC] Raw subtitle tracks count: ${vlcSubtitleTracks.size}")

        val selectedSubtitleTrack = view.getSelectedSubtitleTrack()
        val selectedSubtitleId = selectedSubtitleTrack?.id

        val subtitleTracks = vlcSubtitleTracks.mapIndexed { index, track ->
            val trackTexts = listOfNotNull(track.name, track.language, track.id)
            val nameHintForced = trackTexts.any { it.contains("forced", ignoreCase = true) }
            val isSongsAndSigns = trackTexts.any {
                it.contains("songs", ignoreCase = true) && it.contains("sign", ignoreCase = true)
            }

            Log.d(PlayerRuntimeController.TAG, "[VLC] Subtitle track $index: id=${track.id} name=${track.name} lang=${track.language}")
            TrackInfo(
                index = index,
                name = track.name ?: track.description ?: "Subtitle ${index + 1}",
                language = track.language,
                trackId = track.id,
                codec = track.codec,
                isSelected = track.id == selectedSubtitleId || track.selected,
                isForced = nameHintForced || isSongsAndSigns
            )
        }

        // Find selected indices
        val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: -1
        val selectedSubtitleIndex = subtitleTracks.indexOfFirst { it.isSelected }

        Log.d(PlayerRuntimeController.TAG, "[VLC] Final tracks - audio=${audioTracks.size} subtitle=${subtitleTracks.size} selectedAudio=$selectedAudioIndex selectedSub=$selectedSubtitleIndex")

        hasScannedTextTracksOnce = true

        _uiState.update { state ->
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = selectedSubtitleIndex
            )
        }

        updateAudioControlAvailability(audioTracks, selectedAudioIndex)
    } catch (e: Exception) {
        Log.e(PlayerRuntimeController.TAG, "[VLC] Track extraction failed", e)
    }
}

// VLC track selection functions
internal fun PlayerRuntimeController.selectVlcAudioTrack(index: Int) {
    val view = vlcView ?: return
    val tracks = _uiState.value.audioTracks
    if (index < 0 || index >= tracks.size) return

    val track = tracks[index]
    val trackId = track.trackId
    if (trackId == null) {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - trackId is null for index=$index, skipping selection")
        return
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - index=$index trackId=$trackId")
    if (view.selectTrackById(trackId)) {
        _uiState.update { it.copy(selectedAudioTrackIndex = index) }
    } else {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - failed to select trackId=$trackId")
    }
}

internal fun PlayerRuntimeController.selectVlcSubtitleTrack(index: Int) {
    val view = vlcView ?: return
    val tracks = _uiState.value.subtitleTracks

    if (index < 0) {
        // Disable subtitles
        Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - disabling subtitles")
        view.disableSubtitles()
        _uiState.update { it.copy(selectedSubtitleTrackIndex = -1) }
        return
    }

    if (index >= tracks.size) return

    val track = tracks[index]
    val trackId = track.trackId
    if (trackId == null) {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - trackId is null for index=$index, skipping selection")
        return
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - index=$index trackId=$trackId")
    if (view.selectTrackById(trackId)) {
        _uiState.update { it.copy(selectedSubtitleTrackIndex = index) }
    } else {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - failed to select trackId=$trackId")
    }
}

// Add external subtitle to VLC player
internal fun PlayerRuntimeController.addVlcExternalSubtitle(url: String, select: Boolean = true): Boolean {
    val view = vlcView ?: return false
    Log.d(PlayerRuntimeController.TAG, "[VLC] addVlcExternalSubtitle - url=$url select=$select")
    return view.addExternalSubtitleUrl(url, select)
}

internal fun PlayerRuntimeController.isUsingVlcEngine(): Boolean {
    return _uiState.value.internalPlayerEngine == com.nuvio.tv.data.local.InternalPlayerEngine.VLC
}

internal fun PlayerRuntimeController.applyPendingVlcSeekIfNeeded(
    view: NuvioVlcSurfaceView,
    currentPositionMs: Long = view.getCurrentPosition().coerceAtLeast(0L),
    durationMs: Long = view.getDuration().coerceAtLeast(0L)
) {
    if (!isUsingVlcEngine()) return

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val targetPosition = state.pendingSeekPosition ?: savedResume?.position
    if (targetPosition == null) return

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            // Use saved position directly even if duration is unknown
            savedResume.position.coerceAtLeast(0L)
        }
        else -> targetPosition.coerceAtLeast(0L)
    }

    if (target <= 1000L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val tolerance = 2000L
    val isAlreadyAtTarget = currentPositionMs >= target ||
        kotlin.math.abs(currentPositionMs - target) <= tolerance

    if (isAlreadyAtTarget) {
        if (state.pendingSeekPosition != null || savedResume != null) {
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    Log.d(PlayerRuntimeController.TAG, "[VLC] applyPendingVlcSeekIfNeeded: initiating seek to $target")

    // CRITICAL FIX: Clear pending states BEFORE initiating the seek
    // to prevent re-triggering this function from subsequent events while buffering.
    // Use synchronized block to ensure atomicity with state update.
    synchronized(this) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        view.seekTo(target)
    }
}
