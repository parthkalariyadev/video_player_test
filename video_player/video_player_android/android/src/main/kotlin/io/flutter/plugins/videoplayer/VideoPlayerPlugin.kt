// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.videoplayer

import android.content.Context
import android.os.Build
import android.util.LongSparseArray
import io.flutter.FlutterInjector
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugins.videoplayer.Messages.AndroidVideoPlayerApi
import io.flutter.plugins.videoplayer.Messages.CreateMessage
import io.flutter.plugins.videoplayer.Messages.LoopingMessage
import io.flutter.plugins.videoplayer.Messages.MixWithOthersMessage
import io.flutter.plugins.videoplayer.Messages.PlaybackSpeedMessage
import io.flutter.plugins.videoplayer.Messages.PositionMessage
import io.flutter.plugins.videoplayer.Messages.TextureMessage
import io.flutter.plugins.videoplayer.Messages.VolumeMessage
import io.flutter.view.TextureRegistry
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.HttpsURLConnection


/** Android platform implementation of the VideoPlayerPlugin.  */
class VideoPlayerPlugin : FlutterPlugin, AndroidVideoPlayerApi {
    private val videoPlayers: LongSparseArray<VideoPlayer> = LongSparseArray<VideoPlayer>()
    private var flutterState: FlutterState? = null
    private val options = VideoPlayerOptions()

    /** Register this with the v2 embedding for the plugin to respond to lifecycle callbacks.  */
    constructor() {}
    private constructor(registrar: io.flutter.plugin.common.PluginRegistry.Registrar) {
        flutterState = FlutterState(
            registrar.context(),
            registrar.messenger(),
            registrar::lookupKeyForAsset,
            registrar::lookupKeyForAsset,
            registrar.textures()
        )
        flutterState!!.startListening(this, registrar.messenger())
    }

    fun onAttachedToEngine(binding: FlutterPluginBinding) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(CustomSSLSocketFactory())
            } catch (e: KeyManagementException) {
                Log.w(
                    TAG,
                    """
                        Failed to enable TLSv1.1 and TLSv1.2 Protocols for API level 19 and below.
                        For more information about Socket Security, please consult the following link:
                        https://developer.android.com/reference/javax/net/ssl/SSLSocket
                        """.trimIndent(),
                    e
                )
            } catch (e: NoSuchAlgorithmException) {
                Log.w(
                    TAG,
                    """
                        Failed to enable TLSv1.1 and TLSv1.2 Protocols for API level 19 and below.
                        For more information about Socket Security, please consult the following link:
                        https://developer.android.com/reference/javax/net/ssl/SSLSocket
                        """.trimIndent(),
                    e
                )
            }
        }
        val injector: FlutterInjector = FlutterInjector.instance()
        flutterState = FlutterState(
            binding.getApplicationContext(),
            binding.getBinaryMessenger(),
            injector.flutterLoader()::getLookupKeyForAsset,
            injector.flutterLoader()::getLookupKeyForAsset,
            binding.getTextureRegistry()
        )
        flutterState!!.startListening(this, binding.getBinaryMessenger())
    }

    fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        if (flutterState == null) {
            Log.wtf(TAG, "Detached from the engine before registering to it.")
        }
        flutterState!!.stopListening(binding.getBinaryMessenger())
        flutterState = null
        initialize()
    }

    private fun disposeAllPlayers() {
        for (i in 0 until videoPlayers.size()) {
            videoPlayers.valueAt(i).dispose()
        }
        videoPlayers.clear()
    }

    private fun onDestroy() {
        // The whole FlutterView is being destroyed. Here we release resources acquired for all
        // instances
        // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
        // be replaced with just asserting that videoPlayers.isEmpty().
        // https://github.com/flutter/flutter/issues/20989 tracks this.
        disposeAllPlayers()
    }

    override fun initialize() {
        disposeAllPlayers()
    }

    override fun create(arg: CreateMessage?): TextureMessage {
        val handle: TextureRegistry.SurfaceTextureEntry =
            flutterState!!.textureRegistry.createSurfaceTexture()
        val eventChannel = EventChannel(
            flutterState!!.binaryMessenger, "flutter.io/videoPlayer/videoEvents" + handle.id()
        )
        val player: VideoPlayer
        if (arg != null) {
            if (arg.asset != null) {
                val assetLookupKey: String
                assetLookupKey = if (arg.packageName != null) {
                    flutterState!!.keyForAssetAndPackageName[arg.asset, arg.packageName]
                } else {
                    flutterState!!.keyForAsset[arg.asset]
                }
                player = VideoPlayer(
                    flutterState!!.applicationContext,
                    eventChannel,
                    handle,
                    "asset:///$assetLookupKey",
                    null,
                    null,
                    null,
                    null,
                    options
                )
            } else {
                val httpHeaders: Map<String, String>? =
                    arg.getHttpHeaders()
                player = VideoPlayer(
                    flutterState!!.applicationContext,
                    eventChannel,
                    handle,
                    arg.uri,
                    arg.formatHint,
                    arg.drmUrl,
                    arg.drmType,
                    httpHeaders,
                    options
                )
            }
        } else {
            player = VideoPlayer(
                flutterState!!.applicationContext,
                eventChannel,
                handle,
                null,
                null,
                null,
                null,
                null,
                options
            )
        }
        videoPlayers.put(handle.id(), player)
        return Messages.TextureMessage.Builder().setTextureId(handle.id()).build()
    }

    override fun dispose(arg: TextureMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        player?.dispose()
        arg?.getTextureId()?.let { videoPlayers.remove(it) }
    }

    override fun setLooping(arg: LoopingMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        arg?.getIsLooping()?.let { player?.setLooping(it) }
    }

    override fun setVolume(arg: VolumeMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        arg?.getVolume()?.let { player?.setVolume(it) }
    }

    override fun setPlaybackSpeed(arg: PlaybackSpeedMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        arg?.getSpeed()?.let { player?.setPlaybackSpeed(it) }
    }

    override fun play(arg: TextureMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        player?.play()
    }

    override fun position(arg: TextureMessage?): PositionMessage {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        val result: PositionMessage = Messages.PositionMessage.Builder()
            .setPosition(player?.position)
            .setTextureId(arg?.getTextureId())
            .build()
        player?.sendBufferingUpdate()
        return result
    }

    override fun seekTo(arg: PositionMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        arg?.getPosition()?.let { player?.seekTo(it.toInt()) }
    }

    override fun pause(arg: TextureMessage?) {
        val player: VideoPlayer? = arg?.getTextureId()?.let { videoPlayers.get(it) }
        player?.pause()
    }

    override fun setMixWithOthers(arg: MixWithOthersMessage?) {
        options.mixWithOthers = arg?.getMixWithOthers()!!
    }

    private interface KeyForAssetFn {
        operator fun get(asset: String?): String
    }

    private interface KeyForAssetAndPackageName {
        operator fun get(asset: String?, packageName: String?): String
    }

    private class FlutterState internal constructor(
        val applicationContext: Context,
        messenger: BinaryMessenger,
        keyForAsset: KeyForAssetFn,
        keyForAssetAndPackageName: KeyForAssetAndPackageName,
        textureRegistry: TextureRegistry
    ) {
        val binaryMessenger: BinaryMessenger
        val keyForAsset: KeyForAssetFn
        val keyForAssetAndPackageName: KeyForAssetAndPackageName
        val textureRegistry: TextureRegistry

        init {
            binaryMessenger = messenger
            this.keyForAsset = keyForAsset
            this.keyForAssetAndPackageName = keyForAssetAndPackageName
            this.textureRegistry = textureRegistry
        }

        fun startListening(methodCallHandler: VideoPlayerPlugin?, messenger: BinaryMessenger?) {
            AndroidVideoPlayerApi.setup(messenger, methodCallHandler)
        }

        fun stopListening(messenger: BinaryMessenger?) {
            AndroidVideoPlayerApi.setup(messenger, null)
        }
    }

    companion object {
        private const val TAG = "VideoPlayerPlugin"

        /** Registers this with the stable v1 embedding. Will not respond to lifecycle events.  */
        fun registerWith(registrar: io.flutter.plugin.common.PluginRegistry.Registrar) {
            val plugin = VideoPlayerPlugin(registrar)
            registrar.addViewDestroyListener { view ->
                plugin.onDestroy()
                false // We are not interested in assuming ownership of the NativeView.
            }
        }
    }
}