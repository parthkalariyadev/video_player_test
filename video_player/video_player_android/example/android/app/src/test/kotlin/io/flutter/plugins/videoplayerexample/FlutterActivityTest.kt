// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.videoplayerexample

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugins.videoplayer.VideoPlayerPlugin
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FlutterActivityTest {
    @Test
    fun disposeAllPlayers() {
        val videoPlayerPlugin = Mockito.spy(VideoPlayerPlugin())
        val flutterLoader = Mockito.mock(FlutterLoader::class.java)
        val flutterJNI = Mockito.mock(FlutterJNI::class.java)
        val pluginBindingCaptor = ArgumentCaptor.forClass(
            FlutterPluginBinding::class.java
        )
        Mockito.`when`(flutterJNI.isAttached).thenReturn(true)
        val engine =
            Mockito.spy(FlutterEngine(RuntimeEnvironment.application, flutterLoader, flutterJNI))
        FlutterEngineCache.getInstance().put("my_flutter_engine", engine)
        engine.plugins.add(videoPlayerPlugin)
        Mockito.verify(videoPlayerPlugin, Mockito.times(1))
            .onAttachedToEngine(pluginBindingCaptor.capture())
        engine.destroy()
        Mockito.verify(videoPlayerPlugin, Mockito.times(1))
            .onDetachedFromEngine(pluginBindingCaptor.capture())
        Mockito.verify(videoPlayerPlugin, Mockito.times(1)).initialize()
    }
}