package com.audiobookshelf.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import kotlin.concurrent.schedule

/*
 * Normal progress sync is handled in webview, but when using android auto webview may not be open.
 *   If webview is not open sync progress every 5s. Webview can be closed at any time so interval is always set.
 */
class AudiobookProgressSyncer constructor(playerNotificationService:PlayerNotificationService, client: OkHttpClient) {
  private val tag = "AudiobookProgressSync"
  private val playerNotificationService:PlayerNotificationService = playerNotificationService
  private val client:OkHttpClient = client

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning:Boolean = false

  private var webviewOpenOnStart:Boolean = false
  private var webviewClosedMidSession:Boolean = false
  private var listeningBookTitle:String? = ""
  private var listeningBookIsLocal:Boolean = false
  private var listeningBookId:String? = ""
  private var listeningStreamId:String? = ""

  private var lastPlaybackTime:Long = 0
  private var lastUpdateTime:Long = 0

  fun start() {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $listeningBookTitle")
      if (playerNotificationService.getCurrentBookTitle() != listeningBookTitle) {
        Log.d(tag, "start: Changed audiobook stream - resetting timer")
        listeningTimerTask?.cancel()
      }
    }
    listeningTimerRunning = true

    webviewOpenOnStart = playerNotificationService.getIsWebviewOpen()
    listeningBookTitle = playerNotificationService.getCurrentBookTitle()
    listeningBookIsLocal = playerNotificationService.getCurrentBookIsLocal()
    listeningBookId = playerNotificationService.getCurrentBookId()
    listeningStreamId = playerNotificationService.getCurrentStreamId()

    lastPlaybackTime = playerNotificationService.getCurrentTime()
    lastUpdateTime = System.currentTimeMillis() / 1000L

    listeningTimerTask = Timer("ListeningTimer", false).schedule(0L, 5000L) {
      Handler(Looper.getMainLooper()).post() {
        // Webview was closed while android auto is open - switch to native sync
        var isWebviewOpen = playerNotificationService.getIsWebviewOpen()
        if (!isWebviewOpen && webviewOpenOnStart) {
          Log.d(tag, "Listening Timer: webview closed Switching to native sync tracking")
          webviewOpenOnStart = false
          webviewClosedMidSession = true
          lastUpdateTime = System.currentTimeMillis() / 1000L
        } else if (isWebviewOpen && webviewClosedMidSession) {
          Log.d(tag, "Listening Timer: webview re-opened Switching back to webview sync tracking")
          webviewClosedMidSession = false
          webviewOpenOnStart = true
          lastUpdateTime = System.currentTimeMillis() / 1000L
        }
        if (!webviewOpenOnStart && playerNotificationService.currentPlayer.isPlaying) {
          sync()
        }
      }
    }
  }

  fun stop() {
    if (!listeningTimerRunning) return
    Log.d(tag, "stop: Stopping listening for $listeningBookTitle")

    if (!webviewOpenOnStart) {
      sync()
    }
    reset()
  }

  fun reset() {
    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    listeningBookTitle = ""
    listeningBookId = ""
    listeningBookIsLocal = false
    listeningStreamId = ""
  }

  fun sync() {
    var currTime = System.currentTimeMillis() / 1000L
    var elapsed = currTime - lastUpdateTime
    lastUpdateTime = currTime

    if (!listeningBookIsLocal) {
      Log.d(tag, "ListeningTimer: Sending sync data to server: elapsed $elapsed | $listeningStreamId | $listeningBookId")

      // Send sync data only for streaming books
      var syncData: JSObject = JSObject()
      syncData.put("timeListened", elapsed)
      syncData.put("currentTime", playerNotificationService.getCurrentTime() / 1000)
      syncData.put("streamId", listeningStreamId)
      syncData.put("audiobookId", listeningBookId)
      sendStreamSyncData(syncData) {
        Log.d(tag, "Stream sync done")
      }
    } else if (listeningStreamId == "download") {
      // TODO: Save downloaded audiobook progress & send to server if connected
      Log.d(tag, "ListeningTimer: Is listening download")

      // Send sync data only for local books
      var syncData: JSObject = JSObject()
      var duration = playerNotificationService.getAudiobookDuration() / 1000
      var currentTime = playerNotificationService.getCurrentTime() / 1000
      syncData.put("totalDuration", duration)
      syncData.put("currentTime", currentTime)
      syncData.put("progress", if (duration > 0) (currentTime / duration) else 0)
      syncData.put("isRead", false)
      syncData.put("lastUpdate", System.currentTimeMillis())
      syncData.put("audiobookId", listeningBookId)
      sendLocalSyncData(syncData) {
        Log.d(tag, "Local sync done")
      }
    }
  }

  fun sendLocalSyncData(payload:JSObject, cb: (() -> Unit)) {
    var serverUrl = playerNotificationService.getServerUrl()
    var token = playerNotificationService.getUserToken()

    if (serverUrl == "" || token == "") {
      return
    }

    Log.d(tag, "Sync Local $serverUrl | $token")
    var url = "$serverUrl/api/syncLocal"
    sendServerRequest(url, token, payload, cb)
  }

  fun sendStreamSyncData(payload:JSObject, cb: (() -> Unit)) {
    var serverUrl = playerNotificationService.getServerUrl()
    var token = playerNotificationService.getUserToken()

    if (serverUrl == "" || token == "") {
      return
    }

    Log.d(tag, "Sync Stream $serverUrl | $token")
    var url = "$serverUrl/api/syncStream"
    sendServerRequest(url, token, payload, cb)
  }

  fun sendServerRequest(url:String, token:String, payload:JSObject, cb: () -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    val request = Request.Builder().post(requestBody)
      .url(url).addHeader("Authorization", "Bearer $token")
      .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()
        cb()
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) throw IOException("Unexpected code $response")
          cb()
        }
      }
    })
  }
}
