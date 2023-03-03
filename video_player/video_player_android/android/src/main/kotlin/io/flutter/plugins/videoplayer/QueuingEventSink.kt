// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.videoplayer

import io.flutter.plugin.common.EventChannel

/**
 * And implementation of [EventChannel.EventSink] which can wrap an underlying sink.
 *
 *
 * It delivers messages immediately when downstream is available, but it queues messages before
 * the delegate event sink is set with setDelegate.
 *
 *
 * This class is not thread-safe. All calls must be done on the same thread or synchronized
 * externally.
 */
internal class QueuingEventSink : EventChannel.EventSink {
    private var delegate: EventChannel.EventSink? = null
    private val eventQueue = ArrayList<Any>()
    private var done = false
    fun setDelegate(delegate: EventChannel.EventSink?) {
        this.delegate = delegate
        maybeFlush()
    }

    fun endOfStream() {
        enqueue(EndOfStreamEvent())
        maybeFlush()
        done = true
    }

    fun error(code: String?, message: String?, details: Any?) {
        enqueue(ErrorEvent(code, message, details))
        maybeFlush()
    }

    fun success(event: Any) {
        enqueue(event)
        maybeFlush()
    }

    private fun enqueue(event: Any) {
        if (done) {
            return
        }
        eventQueue.add(event)
    }

    private fun maybeFlush() {
        if (delegate == null) {
            return
        }
        for (event in eventQueue) {
            if (event is EndOfStreamEvent) {
                delegate.endOfStream()
            } else if (event is ErrorEvent) {
                val errorEvent = event
                delegate.error(errorEvent.code, errorEvent.message, errorEvent.details)
            } else {
                delegate.success(event)
            }
        }
        eventQueue.clear()
    }

    private class EndOfStreamEvent
    private class ErrorEvent internal constructor(
        var code: String?,
        var message: String?,
        var details: Any?
    )
}