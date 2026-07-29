package com.gios.lightvoice.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Plays the synthesised reply. One at a time — a new answer cuts off the old one. */
object Player {

    private var player: MediaPlayer? = null

    fun play(file: File, onDone: () -> Unit = {}) {
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { onDone() }
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) onDone()
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
