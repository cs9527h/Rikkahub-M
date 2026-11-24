package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import java.io.File

/**
 * 桌面快捷方式「拍照提问」的入口。
 *
 * 这个 Activity 在清单里是导出的（快捷方式必须是 VIEW 深链），所以不能一进 onCreate 就拉起相机：
 * 先校验调用确实是 `rikkahub://shortcut`，再让用户点一次确认，避免其它应用一个 Intent 就把相机顶到前台。
 */
class ShortcutHandlerActivity : ComponentActivity() {
    private var photoURI: Uri? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                finish()
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                photoURI?.let {
                    val intent = Intent(this, RouteActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, it.toString())
                    }
                    startActivity(intent)
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isShortcutIntent(intent)) {
            finish()
            return
        }
        setContent {
            MaterialExpressiveTheme {
                var confirmed by remember { mutableStateOf(false) }
                if (!confirmed) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("拍照提问") },
                        text = { Text("打开相机拍一张照片，然后发送到会话里。") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmed = true
                                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }) { Text("拍照") }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text("取消") }
                        },
                    )
                } else {
                    Text("正在打开相机…")
                }
            }
        }
    }

    private fun isShortcutIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        return data.scheme == "rikkahub" && data.host == "shortcut"
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "shortcut_camera_image.jpg")
        photoURI = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", imageFile)
        photoURI?.let {
            takePictureLauncher.launch(it)
        } ?: finish()
    }
}
