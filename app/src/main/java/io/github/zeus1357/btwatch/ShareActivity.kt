package io.github.zeus1357.btwatch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 받은 파일을 Claude 앱의 대화창에 첨부(공유)한다. 화면은 없고 공유만 실행하고 끝난다.
 * Claude 앱이 없거나 그 파일 형식을 받지 않으면 일반 공유 창을 띄운다.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_PATH)?.let(::share)
        finish()
    }

    private fun share(path: String) {
        val file = File(path)
        if (!file.isFile) {
            toast("파일을 찾을 수 없습니다: ${file.name}")
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (e: IllegalArgumentException) {
            // 내장 저장소 밖(SD 카드 등)에 있는 파일
            Log.w(TAG, "cannot share $path", e)
            toast("이 위치의 파일은 공유할 수 없습니다.")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent(send).setPackage(CLAUDE_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent.createChooser(send, "파일 보내기"))
            } catch (e2: ActivityNotFoundException) {
                toast("이 파일을 받을 수 있는 앱이 없습니다.")
            }
        }
    }

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "BtFileWatch"
        private const val EXTRA_PATH = "path"
        const val CLAUDE_PACKAGE = "com.anthropic.claude"

        fun intent(context: Context, path: String): Intent =
            Intent(context, ShareActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
