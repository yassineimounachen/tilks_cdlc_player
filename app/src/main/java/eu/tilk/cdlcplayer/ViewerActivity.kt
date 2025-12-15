/*
 *     Copyright (C) 2020  Marek Materzok
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.tilk.cdlcplayer

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ogg.OggExtractor
import com.google.android.material.button.MaterialButton
import eu.tilk.cdlcplayer.song.Song2014
import eu.tilk.cdlcplayer.song.Vocal
import eu.tilk.cdlcplayer.viewer.RepeaterInfo
import eu.tilk.cdlcplayer.viewer.SongGLSurfaceView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ViewerActivity : AppCompatActivity() {
    private val songViewModel : SongViewModel by viewModels()

    private lateinit var glView : SongGLSurfaceView

    private val observer : Observer<Song2014> by lazy {
        Observer {
            setContentView(constructView())
            songViewModel.song.removeObserver(observer)
        }
    }

    private val secondObserver : Observer<Song2014> by lazy {
        Observer {
            playMusic()
            songViewModel.song.removeObserver(secondObserver)
        }
    }

    private var player : ExoPlayer? = null
    private var lyricsText : TextView? = null

    @OptIn(UnstableApi::class) private fun initializePlayer() {
        val audioOnlyRenderersFactory =
            RenderersFactory {
                    handler: Handler,
                    _: VideoRendererEventListener,
                    audioListener: AudioRendererEventListener,
                    _: TextOutput,
                    _: MetadataOutput,
                ->
                arrayOf(
                    MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener)
                )
            }

        val customMediaSourceFactory = ProgressiveMediaSource.Factory(FileDataSource.Factory(),  ExtractorsFactory { arrayOf(OggExtractor()) })
            .setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 50,
                /* maxBufferMs = */ 250,
                /* bufferForPlaybackMs = */ 20,
                /* bufferForPlaybackAfterRebufferMs = */ 20
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this, audioOnlyRenderersFactory, customMediaSourceFactory).setLoadControl(loadControl).setAudioAttributes(audioAttributes, false).build()
        player!!.skipSilenceEnabled = false
        player!!.preloadConfiguration =
            ExoPlayer.PreloadConfiguration(5_000_000L)
        player!!.setSeekParameters(SeekParameters.EXACT)
    }

    private fun playMusic() {
        val ogg = File(this.filesDir, "${songViewModel.song.value!!.songKey}.ogg")

        player!!.setMediaItem(MediaItem.fromUri(ogg.toUri()))
        player!!.prepare()
        player!!.seekTo(if (this::glView.isInitialized) glView.currentTime() else 0)
        player!!.setPlaybackSpeed(songViewModel.speed.value!!)

        if (!songViewModel.paused.value!!) player!!.play()
    }

    private fun observeViewAndSyncMusic() {
        this.lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousDelta = 0L
                while (true) {
                    val currentTime = glView.currentTime()
                    val currentDelta = player!!.currentPosition - currentTime

                    if (abs(previousDelta - currentDelta) > 40) {
                        player!!.seekTo(currentTime)
                        previousDelta = currentDelta
                    }

                    delay(1000)
                }
            }
        }

        this.lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val currentTime = glView.currentTime()

                    if (songViewModel.song.value!!.vocals.isNotEmpty()) {
                        if (songViewModel.currentWord.value!! < 0 || currentTime / 1000F !in songViewModel.song.value!!.vocals[songViewModel.currentWord.value!!].time - 0.05 .. songViewModel.song.value!!.vocals[songViewModel.currentWord.value!!].time + songViewModel.song.value!!.vocals[songViewModel.currentWord.value!!].length + 0.05) {
                            songViewModel.currentWord.value =
                                songViewModel.song.value!!.vocals.binarySearch(Vocal(currentTime/1000F, 0, 0F, ""), Vocal::compareTo)
                            if (songViewModel.currentWord.value!! < 0) {
                                songViewModel.currentWord.value = -(songViewModel.currentWord.value!! + 2)
                            }
                        }

                        if (songViewModel.currentWord.value!! in 0 until songViewModel.song.value!!.vocals.size) {
                            if (songViewModel.currentWord.value!! == 0 || songViewModel.song.value!!.vocals[songViewModel.currentWord.value!! - 1].lyric.endsWith("+") || (songViewModel.song.value!!.vocals[songViewModel.currentWord.value!! - 1].time - songViewModel.song.value!!.vocals[songViewModel.sentenceStart.value!!].time >= 3 && !songViewModel.song.value!!.vocals[songViewModel.currentWord.value!! - 1].lyric.endsWith("-")))
                            {
                                songViewModel.sentenceStart.value =
                                    songViewModel.currentWord.value!!
                            }

                            var i = songViewModel.sentenceStart.value!!
                            var text = ""

                            while (i < songViewModel.song.value!!.vocals.size) {
                                val toAdd = songViewModel.song.value!!.vocals[i].lyric
                                if (i < songViewModel.currentWord.value!!) {
                                    text += "<font color='#999999'>"
                                }

                                if (i == songViewModel.currentWord.value) {
                                    text += "<b><font color='#fd686c'>"
                                }

                                text += toAdd.removeSuffix("+").removeSuffix("-")

                                if (i < songViewModel.currentWord.value!!) {
                                    text += "</font>"
                                }

                                if (i == songViewModel.currentWord.value) {
                                    text += "</font></b>"
                                }

                                if (!toAdd.endsWith("-")) {
                                    text += " "
                                }

                                i++
                                if (songViewModel.song.value!!.vocals[i - 1].lyric.endsWith("+") || (songViewModel.song.value!!.vocals[i - 1].time - songViewModel.song.value!!.vocals[songViewModel.sentenceStart.value!!].time >= 3 && !songViewModel.song.value!!.vocals[i - 1].lyric.endsWith("-")))
                                {
                                    if (i - 1 == songViewModel.currentWord.value && i < songViewModel.song.value!!.vocals.size) {
                                        songViewModel.sentenceStart.value = i
                                    }
                                    break
                                }
                            }
                            lyricsText?.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
                        }
                    }
                    delay(200)
                }
            }
        }
    }

    private fun constructView() : FrameLayout {
        glView = SongGLSurfaceView(this, songViewModel)
        observeViewAndSyncMusic()
        val frameLayout = FrameLayout(this)
        frameLayout.addView(glView)
        @SuppressLint("InflateParams")
        val pausedUI = layoutInflater.inflate(R.layout.song_paused_ui, null)
        val lyrics = layoutInflater.inflate(R.layout.lyrics, null)
        val ll = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ll.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        pausedUI.layoutParams = ll
        frameLayout.addView(pausedUI)
        frameLayout.addView(lyrics)
        val pauseButton = pausedUI.findViewById<MaterialButton>(R.id.pauseButton)
        val speedBar = pausedUI.findViewById<SeekBar>(R.id.speedBar)
        val repStartButton = pausedUI.findViewById<MaterialButton>(R.id.repStartButton)
        val repEndButton = pausedUI.findViewById<MaterialButton>(R.id.repEndButton)
        val speedText = pausedUI.findViewById<TextView>(R.id.speedText)
        lyricsText = lyrics.findViewById<TextView>(R.id.lyricsText)
        fun setVisibility(v : Int) {
            speedBar.visibility = v
            repStartButton.visibility = v
            repEndButton.visibility = v
            speedText.visibility = v
            lyricsText!!.visibility = if (v == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        }
        speedBar.max = 99
        pauseButton.setOnClickListener {
            songViewModel.paused.value = !songViewModel.paused.value!!
        }
        repStartButton.setOnClickListener {
            val beats = glView.nextBeats()
            val rep = songViewModel.repeater.value
            val beats2 = beats.take(2).toList()
            if (rep != null) {
                if (beats2.count() == 2 && beats2[0].time < rep.endBeat.time) {
                    songViewModel.repeater.value = rep.copy(
                            startBeat = beats2[0],
                            beatPeriod = beats2[1].time - beats2[0].time
                    )
                } else {
                    songViewModel.repeater.value = null
                }
            } else {
                if (beats2.count() == 2)
                    songViewModel.repeater.value =
                        RepeaterInfo(beats2[0], beats2[1], beats2[1].time - beats2[0].time)
            }
        }
        repEndButton.setOnClickListener {
            val beats = glView.nextBeats()
            val rep = songViewModel.repeater.value
            if (rep != null) {
                val beat1 = beats.firstOrNull()
                if (beat1 != null && beat1.time > rep.startBeat.time) {
                    songViewModel.repeater.value = rep.copy(endBeat = beat1)
                } else {
                    songViewModel.repeater.value = null
                }
            }
        }
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar : SeekBar, progress : Int, fromUser : Boolean) {
                if (fromUser)
                    songViewModel.speed.value = (progress + 1) / 100f
            }

            override fun onStartTrackingTouch(p0 : SeekBar?) { }
            override fun onStopTrackingTouch(p0 : SeekBar?) { }
        })
        songViewModel.paused.observeAndCall(this) {
            if (it) player?.pause() else player?.play()

            val resource =
                if (it) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause
            pauseButton.setIconResource(resource)
            setVisibility(if (it) View.VISIBLE else View.INVISIBLE)
        }

        songViewModel.speed.observeAndCall(this) {
            speedBar.progress = max(0, (100f * it).roundToInt() - 1)
            @SuppressLint("SetTextI18n")
            speedText.text = "${(100f*it).roundToInt()}%"
            player?.setPlaybackSpeed(it)
        }
        songViewModel.repeater.observeAndCall(this) {
            repEndButton.isEnabled = true
        }

        return frameLayout
    }

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        initializePlayer()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val songId = intent.getStringExtra(SONG_ID)
        if (songViewModel.song.value != null)
            setContentView(constructView())
        else {
            setContentView(R.layout.activity_viewer_loading)
            songViewModel.song.observe(this, observer)
            songViewModel.loadSong(songId!!)
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (songViewModel.song.value != null) {
            playMusic()
        } else {
            songViewModel.song.observe(this, secondObserver)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    companion object {
        const val SONG_ID = "eu.tilk.cdlcplayer.SONG_ID"
    }
}
