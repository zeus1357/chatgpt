package io.github.zeus1357.btwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button
    private lateinit var logContainer: LinearLayout

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 16f }
        permissionButton = Button(this).apply {
            text = "파일 접근 권한 허용하기"
            setOnClickListener { openAllFilesAccessSettings() }
        }
        toggleButton = Button(this).apply { setOnClickListener { toggle() } }
        val clearButton = Button(this).apply {
            text = "기록 지우기"
            setOnClickListener { EventLog.clear(this@MainActivity) }
        }
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
            addView(status)
            addView(permissionButton)
            addView(toggleButton)
            addView(clearButton)
            addView(logContainer)
        }
        setContentView(ScrollView(this).apply { addView(column) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        // 켜 둔 상태인데 서비스가 죽어 있으면 다시 살린다.
        if (EventLog.isEnabled(this) && hasFileAccess() && !WatchService.running) {
            startForegroundService(Intent(this, WatchService::class.java))
        }
        refresh()
    }

    override fun onPause() {
        EventLog.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun hasFileAccess() = Environment.isExternalStorageManager()

    private fun toggle() {
        val service = Intent(this, WatchService::class.java)
        if (EventLog.isEnabled(this)) {
            EventLog.setEnabled(this, false)
            stopService(service)
        } else {
            EventLog.setEnabled(this, true)
            startForegroundService(service)
        }
        refresh()
    }

    private fun openAllFilesAccessSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun refresh() {
        val access = hasFileAccess()
        val enabled = EventLog.isEnabled(this)

        permissionButton.visibility = if (access) android.view.View.GONE else android.view.View.VISIBLE
        toggleButton.isEnabled = access || enabled
        toggleButton.text = if (enabled) "감시 중지" else "감시 시작"
        status.text = when {
            !access -> "블루투스로 받은 파일을 찾으려면 '모든 파일에 대한 접근' 권한이 필요합니다."
            enabled -> "감시 중입니다. 블루투스로 파일을 받으면 알림이 표시됩니다."
            else -> "감시가 꺼져 있습니다."
        }

        val entries = EventLog.entries(this)
        logContainer.removeAllViews()
        if (entries.isEmpty()) {
            logContainer.addView(TextView(this).apply { text = "아직 감지된 파일이 없습니다." })
        }
        for (e in entries) {
            val time = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(e.timeMillis))
            logContainer.addView(TextView(this).apply {
                text = "$time\n${e.name} (${Formatter.formatFileSize(this@MainActivity, e.size)})\n${e.path}"
                setTextIsSelectable(true)
            })
            if (e.path.isNotEmpty()) {
                logContainer.addView(Button(this).apply {
                    text = "Claude로 보내기"
                    setOnClickListener { startActivity(ShareActivity.intent(this@MainActivity, e.path)) }
                })
            }
        }
    }
}
