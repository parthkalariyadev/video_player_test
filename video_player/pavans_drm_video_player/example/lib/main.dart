// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// This file is used to extract code samples for the README.md file.
// Run update-excerpts if you modify this file.

// ignore_for_file: library_private_types_in_public_api, public_member_api_docs

// #docregion basic-example
import 'package:flutter/material.dart';
import 'package:pavans_drm_video_player/video_player.dart';

void main() => runApp(const VideoApp());

/// Stateful widget to fetch and then display video content.
class VideoApp extends StatefulWidget {
  const VideoApp({Key? key}) : super(key: key);

  @override
  _VideoAppState createState() => _VideoAppState();
}

class _VideoAppState extends State<VideoApp> {
  late VideoPlayerController _controller;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.network(
      /*'YOUR_VIDEO_URL'*/
      /*'https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4'*/
      /*'https://video.gumlet.io/63be5807b03c5ea88609e4d6/63bfe56832156f9174527aa6/main.mpd'*/
      'https://video.gumlet.io/63be5807b03c5ea88609e4d6/63bfe56832156f9174527aa6/main.m3u8',
      drmType: VideoDrmType.widevine,
      licenseProxyURL: /*'YOUR_LICENCE_PROXY_URL'*/
          "https://widevine.gumlet.com/licence/63b67aa4fd4b762e422a8b3d",
      proxyURLSigningSecret: /*'YOUR_PROXY_URL_SIGNING_SECRET'*/
          "87610eee6ee466c66b4866a09c42f7f4",
    )..initialize().then((_) {
        // Ensure the first frame is shown after the video is initialized, even before the play button has been pressed.
        setState(() {});
      });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Video Demo',
      home: Scaffold(
        body: Center(
          child: _controller.value.isInitialized
              ? AspectRatio(
                  aspectRatio: _controller.value.aspectRatio,
                  child: VideoPlayer(_controller),
                )
              : Container(),
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: () {
            setState(() {
              _controller.value.isPlaying
                  ? _controller.pause()
                  : _controller.play();
            });
          },
          child: Icon(
            _controller.value.isPlaying ? Icons.pause : Icons.play_arrow,
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    super.dispose();
    _controller.dispose();
  }
}
// #enddocregion basic-example
