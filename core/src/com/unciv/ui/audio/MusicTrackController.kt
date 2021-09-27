package com.unciv.ui.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.files.FileHandle
import kotlin.concurrent.thread

/** Wraps one Gdx Music instance and manages threaded loading, playback, fading and cleanup */
class MusicTrackController(private var volume: Float) {

    /** Internal state of this Music track */
    enum class State(val canPlay: Boolean) {
        None(false),
        Loading(false),
        Idle(true),
        FadeIn(true),
        Playing(true),
        FadeOut(true),
        Error(false)
    }

    var state = State.None
        private set
    var music: Music? = null
        private set
    private var loaderThread: Thread? = null
    private var fadeStep = MusicController.defaultFadingStep
    private var fadeVolume: Float = 1f

    /** Clean up and dispose resources */
    fun clear() {
        state = State.None
        clearLoader()
        clearMusic()
    }
    private fun clearLoader() {
        if (loaderThread == null) return
        loaderThread!!.interrupt()
        loaderThread = null
    }
    private fun clearMusic() {
        if (music == null) return
        music!!.stop()
        music!!.dispose()
        music = null
    }

    /** Loads [file] into this controller's [music] and optionally calls [onSuccess] when done.
     *  Failures are silently logged to console, and [onError] is called.
     *  Callbacks run on the background thread.
     *  @throws IllegalStateException if called in the wrong state (fresh or cleared instance only)
     */
    fun load(
        file: FileHandle,
        onError: ((MusicTrackController)->Unit)? = null,
        onSuccess: ((MusicTrackController)->Unit)? = null
    ) {
        if (state != State.None || loaderThread != null || music != null)
            throw IllegalStateException("MusicTrackController.load should only be called once")
        loaderThread = thread(name = "MusicLoader") {
            state = State.Loading
            try {
                music = Gdx.audio.newMusic(file)
                if (state != State.Loading) {  // in case clear was called in the meantime
                    clearMusic()
                } else {
                    state = State.Idle
                    if (MusicController.consoleLog)
                        println("Music loaded: $file")
                    onSuccess?.invoke(this)
                }
            } catch (ex: Exception) {
                println("Exception loading $file: ${ex.message}")
                if (MusicController.consoleLog)
                    ex.printStackTrace()
                state = State.Error
                onError?.invoke(this)
            }
            loaderThread = null
        }
    }

    /** Called by the [MusicController] in its timer "tick" event handler, implements fading */
    fun timerTick(): State {
        if (state == State.FadeIn) fadeInStep()
        if (state == State.FadeOut) fadeOutStep()
        return state
    }
    private fun fadeInStep() {
        // fade-in: linearly ramp fadeVolume to 1.0, then continue playing
        fadeVolume += fadeStep
        if (fadeVolume < 1f  && music != null && music!!.isPlaying) {
            music!!.volume = volume * fadeVolume
            return
        }
        music!!.volume = volume
        fadeVolume = 1f
        state = State.Playing
    }
    private fun fadeOutStep() {
        // fade-out: linearly ramp fadeVolume to 0.0, then act according to Status (Playing->Silence/Pause/Shutdown)
        fadeVolume -= fadeStep
        if (fadeVolume >= 0.001f && music != null && music!!.isPlaying) {
            music!!.volume = volume * fadeVolume
            return
        }
        fadeVolume = 0f
        music!!.volume = 0f
        music!!.pause()
        state = State.Idle
    }

    /** Starts fadeIn or fadeOut.
     * 
     *  Note this does _not_ set the current fade "percentage" to allow smoothly changing direction mid-fade
     *  @param step Overrides current fade step only if >0
     */
    fun startFade(fade: State, step: Float = 0f) {
        if (!state.canPlay) return
        if (fadeStep > 0f) fadeStep = step
        state = fade
    }

    /** @return [Music.isPlaying] (Gdx music stream is playing) unless [state] says it won't make sense */
    fun isPlaying() = state.canPlay && music?.isPlaying == true

    /** Calls play() on the wrapped Gdx Music, catching exceptions to console.
     *  @return success
     *  @throws IllegalStateException if called on uninitialized instance
     */
    fun play(): Boolean {
        if (!state.canPlay || music == null) {
            throw IllegalStateException("MusicTrackController.play called on uninitialized instance")
        }
        return try {
            music!!.volume = volume
            if (!music!!.isPlaying)  // for fade-over this could be called by the end of the previous track
                music!!.play()
            true
        } catch (ex: Exception) {
            println("Exception playing music: ${ex.message}")
            if (MusicController.consoleLog)
                ex.printStackTrace()
            state = State.Error
            false
        }
    }

    /** Adjust master volume without affecting a fade-in/out */
    fun setVolume(newVolume: Float) {
        volume = newVolume
        music?.volume = volume * fadeVolume
    }
}