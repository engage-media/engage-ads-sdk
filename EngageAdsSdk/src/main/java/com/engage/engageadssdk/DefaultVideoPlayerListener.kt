package com.engage.engageadssdk

import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DefaultVideoPlayerListener(private val playerView: PlayerView) : EMVideoPlayerListener {


    fun Player.currentPositionFlow(
        updateFrequency: Duration = 1.seconds,
    ) = flow {
        while (true) {
            if (isPlaying) emit(currentPosition.toDuration(DurationUnit.MILLISECONDS))
            delay(updateFrequency)
        }
    }.flowOn(Dispatchers.Main)

    fun Player.remainingTimeFlow(
        updateFrequency: Duration = 1.seconds,
    ) = flow {
        while (true) {
            if (isPlaying) emit(abs(duration - currentPosition).toDuration(DurationUnit.MILLISECONDS))
            delay(updateFrequency)
        }
    }.flowOn(Dispatchers.Main)


    private val _onProgressUpdateFlow: Flow<Pair<Long, Long>> = playerView.player!!.let { player ->
        player.currentPositionFlow().combine(player.remainingTimeFlow()) { current, remaining ->
            Pair(current, remaining)
        }.map {
            it.first.toLong(
                DurationUnit.MILLISECONDS
            ) to it.second.toLong(
                DurationUnit.MILLISECONDS
            )
        }
    }

    private val _onContentEndedFlow = flow {
        while (true) {
            if (playerView.player?.isPlaying == false) {
                emit(Unit)
            }
            delay(1.seconds)
        }
    }.flowOn(Dispatchers.Main)


    override val onProgressUpdateFlow: Flow<Pair<Long, Long>>
        get() = _onProgressUpdateFlow
    override val onContentEndedFlow: Flow<Unit>
        get() = _onContentEndedFlow


}