package io.github.zeus1357.btwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import java.io.File

/**
 * MediaStore를 지켜보다가 블루투스로 받은 파일이 생기면 알림을 띄우는 포그라운드 서비스.
 *
 * 블루투스 앱은 수신을 시작할 때 파일을 먼저 등록하고 내용을 채워 나가므로,
 * 크기가 더 이상 변하지 않을 때 "수신 완료"로 판단한다.
 */
class WatchService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var observer: ContentObserver

    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** 이 ID 이하의 파일은 이미 확인했다. */
    private var lastSeenId = -1L

    /** 수신 중인 것으로 보이는 파일: ID -> 직전에 확인한 크기 */
    private val inProgress = HashMap<Long, Long>()

    private val checkRunnable = Runnable { check() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = statusNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, notification)
        }

        thread = HandlerThread("bt-file-watch").apply { start() }
        handler = Handler(thread.looper)
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = scheduleCheck(DEBOUNCE_MS)
        }
        handler.post {
            // 서비스 시작 이전에 있던 파일은 알리지 않는다.
            lastSeenId = queryMaxId()
            contentResolver.registerContentObserver(filesUri, true, observer)
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        contentResolver.unregisterContentObserver(observer)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun scheduleCheck(delayMs: Long) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs)
    }

    private fun check() {
        try {
            findNewFiles()
            pollInProgress()
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
        }
        if (inProgress.isNotEmpty()) scheduleCheck(POLL_MS)
    }

    /** lastSeenId 이후에 등록된 파일 중 블루투스 파일을 inProgress에 올린다. */
    private fun findNewFiles() {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DATA,
        )
        contentResolver.query(
            filesUri, projection,
            "${MediaStore.MediaColumns._ID} > ?", arrayOf(lastSeenId.toString()),
            "${MediaStore.MediaColumns._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                lastSeenId = maxOf(lastSeenId, id)
                val data = c.getString(3)
                if (data != null && File(data).isDirectory) continue
                if (BluetoothClassifier.isBluetoothFile(c.getString(2), c.getString(1), data)) {
                    inProgress.putIfAbsent(id, -1L)
                }
            }
        }
    }

    /** 수신 중인 파일의 크기가 멈췄으면 완료로 보고 알린다. */
    private fun pollInProgress() {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
        )
        val iterator = inProgress.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val id = item.key
            var found = false
            contentResolver.query(
                filesUri, projection,
                "${MediaStore.MediaColumns._ID} = ?", arrayOf(id.toString()), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    found = true
                    val data = c.getString(2)
                    // MediaStore의 크기는 늦게 갱신될 수 있어 실제 파일 크기를 우선한다.
                    val size = data?.let { File(it).length() }?.takeIf { it > 0 } ?: c.getLong(1)
                    if (size > 0 && size == item.value) {
                        val name = c.getString(0) ?: data?.substringAfterLast('/') ?: "(이름 없음)"
                        onReceived(id, name, size, data.orEmpty())
                        iterator.remove()
                    } else {
                        item.setValue(size)
                    }
                }
            }
            // 전송이 취소/실패하면 블루투스 앱이 항목을 지운다.
            if (!found) iterator.remove()
        }
    }

    private fun onReceived(id: Long, name: String, size: Long, path: String) {
        Log.i(TAG, "received: $name ($size bytes) $path")
        EventLog.add(this, EventLog.Entry(System.currentTimeMillis(), name, size, path))

        val notification = Notification.Builder(this, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("블루투스 파일 수신 감지")
            .setContentText("$name (${Formatter.formatFileSize(this, size)})")
            .setStyle(Notification.BigTextStyle().bigText("$name (${Formatter.formatFileSize(this, size)})\n$path"))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
    }

    private fun queryMaxId(): Long {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return try {
            contentResolver.query(filesUri, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "queryMaxId failed", e)
            0L
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "감시 상태", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVED, "파일 수신 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun statusNotification(): Notification =
        Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("블루투스 파일 수신 감시 중")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "BtFileWatch"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_RECEIVED = "received"
        private const val STATUS_ID = 1
        private const val DEBOUNCE_MS = 1000L
        private const val POLL_MS = 2000L

        @Volatile
        var running = false
            private set
    }
}
