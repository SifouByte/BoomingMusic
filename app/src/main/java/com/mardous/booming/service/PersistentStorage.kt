package com.mardous.booming.service

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.mardous.booming.providers.databases.PlaybackQueueStore
import com.mardous.booming.repository.SongRepository
import com.mardous.booming.service.playback.Playback
import com.mardous.booming.service.playback.PlaybackManager
import com.mardous.booming.service.queue.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class PersistentStorage(context: Context, private val coroutineScope: CoroutineScope) : KoinComponent {

    private val songRepository: SongRepository by inject()
    private val queueManager: QueueManager by inject()
    private val playbackManager: PlaybackManager by inject()

    private val playbackQueueStore = PlaybackQueueStore(context)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private var state = AtomicReference(RestorationState.Awaiting)
    val restorationState get() = state.load()

    enum class RestorationState(val isRestored: Boolean = false) {
        Awaiting(false),
        Restoring(false),
        Restored(true)
    }

    fun restoreState() {
        queueManager.restoreState(
            shuffleMode = Playback.ShuffleMode.fromOrdinal(
                ordinal = sharedPreferences.getInt(SAVED_SHUFFLE_MODE, -1)
            ),
            repeatMode = Playback.RepeatMode.fromOrdinal(
                ordinal = sharedPreferences.getInt(SAVED_REPEAT_MODE, -1)
            )
        )
    }

    suspend fun restoreQueue(onCompleted: (restored: Boolean, restoredPositionInTrack: Int) -> Unit) {
        val movedToRestoringState = state.compareAndSet(RestorationState.Awaiting, RestorationState.Restoring)
        if (queueManager.isEmpty && movedToRestoringState) {
            withContext(IO) {
                val restoredQueue = playbackQueueStore.getSavedPlayingQueue(songRepository)
                val restoredOriginalQueue = playbackQueueStore.getSavedOriginalPlayingQueue(songRepository)
                val restoredPosition = sharedPreferences.getInt(SAVED_QUEUE_POSITION, -1)
                val restoredPositionInTrack = sharedPreferences.getInt(SAVED_POSITION_IN_TRACK, -1)

                val restored = queueManager.restoreQueues(
                    restoredQueue,
                    restoredOriginalQueue,
                    restoredPosition
                )

                withContext(Main) {
                    onCompleted(restored, restoredPositionInTrack)
                }

                state.store(RestorationState.Restored)
            }
        } else {
            withContext(Main) {
                onCompleted(!queueManager.isEmpty, sharedPreferences.getInt(SAVED_POSITION_IN_TRACK, -1))
            }
        }
    }

    fun saveState() = coroutineScope.launch {
        queueManager.saveQueues(playbackQueueStore)
        savePosition()
        savePositionInTrack()
    }

    fun savePosition() {
        sharedPreferences.edit { putInt(SAVED_QUEUE_POSITION, queueManager.position) }
    }

    fun savePositionInTrack() {
        sharedPreferences.edit { putInt(SAVED_POSITION_IN_TRACK, playbackManager.position()) }
    }

    fun saveRepeatMode(repeatMode: Playback.RepeatMode) {
        sharedPreferences.edit { putInt(SAVED_REPEAT_MODE, repeatMode.ordinal) }
    }

    fun saveShuffleMode(shuffleMode: Playback.ShuffleMode) {
        sharedPreferences.edit { putInt(SAVED_SHUFFLE_MODE, shuffleMode.ordinal) }
    }

    companion object {
        const val SAVED_REPEAT_MODE = "SAVED_REPEAT_MODE"
        const val SAVED_SHUFFLE_MODE = "SAVED_SHUFFLE_MODE"
        const val SAVED_POSITION_IN_TRACK = "SAVED_POSITION_IN_TRACK"
        const val SAVED_QUEUE_POSITION = "SAVED_QUEUE_POSITION"
    }
}