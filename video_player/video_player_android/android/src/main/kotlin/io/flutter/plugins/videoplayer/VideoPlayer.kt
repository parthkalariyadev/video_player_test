// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.videoplayer

import com.google.android.exoplayer2.Player.REPEAT_MODE_ALL
import com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.NonNull
import androidx.annotation.VisibleForTesting
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.Listener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import io.flutter.plugin.common.EventChannel
import io.flutter.view.TextureRegistry
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import io.flutter.Log
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.source.dash.DashChunkSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import java.util.UUID


internal class VideoPlayer {
    private var exoPlayer: ExoPlayer? = null
    private var surface: Surface? = null
    private val textureEntry: TextureRegistry.SurfaceTextureEntry
    private var eventSink: QueuingEventSink? = null
    private val eventChannel: EventChannel

    @VisibleForTesting
    var isInitialized = false
    private val options: VideoPlayerOptions

    constructor(
        context: Context,
        eventChannel: EventChannel,
        textureEntry: TextureRegistry.SurfaceTextureEntry,
        dataSource: String?,
        formatHint: String?,
        drmURL: String?,
        drmType: String?,
        @NonNull httpHeaders: Map<String, String>?,
        options: VideoPlayerOptions
    ) {
        this.eventChannel = eventChannel
        this.textureEntry = textureEntry
        this.options = options
        val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
        val uri = Uri.parse(dataSource)
        val dataSourceFactory: DataSource.Factory
        dataSourceFactory = if (isHTTP(uri)) {
            val httpDataSourceFactory: DefaultHttpDataSource.Factory =
                DefaultHttpDataSource.Factory()
                    .setUserAgent("ExoPlayer")
                    .setAllowCrossProtocolRedirects(true)
            if (httpHeaders != null && !httpHeaders.isEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(httpHeaders)
            }
            httpDataSourceFactory
        } else {
            DefaultDataSource.Factory(context)
        }
        val mediaSource: MediaSource =
            buildMediaSource(uri, dataSourceFactory, formatHint, context, drmURL, drmType)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        setUpVideoPlayer(exoPlayer, QueuingEventSink())
    }

    // Constructor used to directly test members of this class.
    @VisibleForTesting
    constructor(
        exoPlayer: ExoPlayer,
        eventChannel: EventChannel,
        textureEntry: TextureRegistry.SurfaceTextureEntry,
        options: VideoPlayerOptions,
        eventSink: QueuingEventSink?
    ) {
        this.eventChannel = eventChannel
        this.textureEntry = textureEntry
        this.options = options
        setUpVideoPlayer(exoPlayer, eventSink)
    }

    private fun buildMediaSource(
        uri: Uri,
        mediaDataSourceFactory: DataSource.Factory,
        formatHint: String?,
        context: Context,
        drmURL: String?,
        drmType: String?
    ): MediaSource {
        val type: Int
        type = if (formatHint == null) {
            Util.inferContentType(uri.lastPathSegment!!)
        } else {
            when (formatHint) {
                FORMAT_SS -> C.TYPE_SS
                FORMAT_DASH -> C.TYPE_DASH
                FORMAT_HLS -> C.TYPE_HLS
                FORMAT_OTHER -> C.TYPE_OTHER
                else -> -1
            }
        }
        return when (type) {
            C.TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .createMediaSource(MediaItem.fromUri(uri))
            C.TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(uri) // DRM Configuration
                        .setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(drmURL).build()
                        )
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .setTag(null)
                        .build()
                )
            C.TYPE_HLS -> HlsMediaSource.Factory(mediaDataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(uri) // DRM Configuration
                        .setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(drmURL).build()
                        )
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setTag(null)
                        .build()
                )
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(
                mediaDataSourceFactory
            )
                .createMediaSource(MediaItem.fromUri(uri))
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun setUpVideoPlayer(exoPlayer: ExoPlayer, eventSink: QueuingEventSink?) {
        this.exoPlayer = exoPlayer
        this.eventSink = eventSink
        eventChannel.setStreamHandler(
            object : EventChannel.StreamHandler() {
                fun onListen(o: Any?, sink: EventChannel.EventSink?) {
                    eventSink!!.setDelegate(sink)
                }

                fun onCancel(o: Any?) {
                    eventSink!!.setDelegate(null)
                }
            })
        surface = Surface(textureEntry.surfaceTexture())
        exoPlayer.setVideoSurface(surface)
        setAudioAttributes(exoPlayer, options.mixWithOthers)
        exoPlayer.addListener(
            object : Player.Listener {
                private var isBuffering = false
                fun setBuffering(buffering: Boolean) {
                    if (isBuffering != buffering) {
                        isBuffering = buffering
                        val event: MutableMap<String, Any> = HashMap()
                        event["event"] = if (isBuffering) "bufferingStart" else "bufferingEnd"
                        eventSink!!.success(event)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        setBuffering(true)
                        sendBufferingUpdate()
                    } else if (playbackState == Player.STATE_READY) {
                        if (!isInitialized) {
                            isInitialized = true
                            sendInitialized()
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        val event: MutableMap<String, Any> = HashMap()
                        event["event"] = "completed"
                        eventSink!!.success(event)
                    }
                    if (playbackState != Player.STATE_BUFFERING) {
                        setBuffering(false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    setBuffering(false)
                    eventSink?.error("VideoError", "Video player had error $error", null)
                }
            })
    }

    fun sendBufferingUpdate() {
        val event: MutableMap<String, Any> = HashMap()
        event["event"] = "bufferingUpdate"
        val range: List<Number?> = Arrays.asList(0, exoPlayer?.bufferedPosition)
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event["values"] = listOf(range)
        eventSink!!.success(event)
    }

    fun play() {
        exoPlayer?.playWhenReady = true
    }

    fun pause() {
        exoPlayer?.playWhenReady = false
    }

    fun setLooping(value: Boolean) {
        exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun setVolume(value: Double) {
        val bracketedValue = Math.max(0.0, Math.min(1.0, value)).toFloat()
        exoPlayer?.volume = bracketedValue
    }

    fun setPlaybackSpeed(value: Double) {
        // We do not need to consider pitch and skipSilence for now as we do not handle them and
        // therefore never diverge from the default values.
        val playbackParameters = PlaybackParameters(value.toFloat())
        exoPlayer?.playbackParameters = playbackParameters
    }

    fun seekTo(location: Int) {
        exoPlayer?.seekTo(location.toLong())
    }

    val position: Long?
        get() = exoPlayer?.currentPosition

    @VisibleForTesting
    fun sendInitialized() {
        if (isInitialized) {
            val event: MutableMap<String, Any> = HashMap()
            event["event"] = "initialized"
            event["duration"] = exoPlayer?.duration!!
            if (exoPlayer?.videoFormat != null) {
                val videoFormat: Format? = exoPlayer!!.videoFormat
                var width = videoFormat?.width
                var height = videoFormat?.height
                val rotationDegrees = videoFormat?.rotationDegrees
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer!!.videoFormat?.height
                    height = exoPlayer!!.videoFormat?.width
                }
                event["width"] = width!!
                event["height"] = height!!

                // Rotating the video with ExoPlayer does not seem to be possible with a Surface,
                // so inform the Flutter code that the widget needs to be rotated to prevent
                // upside-down playback for videos with rotationDegrees of 180 (other orientations work
                // correctly without correction).
                if (rotationDegrees == 180) {
                    event["rotationCorrection"] = rotationDegrees
                }
            }
            eventSink!!.success(event)
        }
    }

    fun dispose() {
        if (isInitialized) {
            exoPlayer?.stop()
        }
        textureEntry.release()
        eventChannel.setStreamHandler(null)
        if (surface != null) {
            surface!!.release()
        }
        exoPlayer?.release()
    }

    companion object {
        private const val FORMAT_SS = "ss"
        private const val FORMAT_DASH = "dash"
        private const val FORMAT_HLS = "hls"
        private const val FORMAT_OTHER = "other"
        private fun isHTTP(uri: Uri?): Boolean {
            if (uri == null || uri.scheme == null) {
                return false
            }
            val scheme = uri.scheme
            return scheme == "http" || scheme == "https"
        }

        private fun setAudioAttributes(exoPlayer: ExoPlayer, isMixMode: Boolean) {
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(), !isMixMode
            )
        }
    }
}