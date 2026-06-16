package com.chessautoplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.chessautoplayer.chess.OpeningBook
import com.chessautoplayer.service.FloatWindowService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 象棋AI助手 - 主界面
 *
 * 简化版：仅负责启动悬浮球服务。
 * 所有棋局操作均通过悬浮球 → 模拟棋盘完成。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_IMPORT_READY = "com.chessautoplayer.IMPORT_FILE_READY"
        const val EXTRA_IMPORT_PATH = "import_path"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { importFromUri(it) }
    }

    private val exportTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportToDirectory(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)

        updateStatus()

        btnStart.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            startFloatService()
        }

        checkOpenImport(intent)
        checkOpenExport(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkOpenImport(intent)
        checkOpenExport(intent)
    }

    private fun checkOpenImport(intent: Intent?) {
        if (intent?.getBooleanExtra("open_import", false) == true) {
            openImportPicker()
        }
    }

    private fun checkOpenExport(intent: Intent?) {
        if (intent?.getBooleanExtra("open_export", false) == true) {
            openExportTreePicker()
        }
    }

    private fun openExportTreePicker() {
        try {
            exportTreeLauncher.launch(null)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportToDirectory(treeUri: Uri) {
        try {
            val entries = OpeningBook.getAllEntries()
            if (entries.isEmpty()) {
                Toast.makeText(this, "棋谱库为空，无需导出", Toast.LENGTH_SHORT).show()
                return
            }

            val pickedDir = DocumentFile.fromTreeUri(this, treeUri) ?: run {
                Toast.makeText(this, "无法访问所选目录", Toast.LENGTH_SHORT).show()
                return
            }

            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "chess_book_$time.txt"
            val newFile = pickedDir.createFile("text/plain", fileName) ?: run {
                Toast.makeText(this, "无法创建文件", Toast.LENGTH_SHORT).show()
                return
            }

            val content = StringBuilder()
            content.append("# ChessOpeningBook v1\n")
            for ((hash, entry) in entries) {
                val hex = hash.toULong().toString(16)
                val m = entry.move
                content.append("$hex:${m.fromRow},${m.fromCol},${m.toRow},${m.toCol}:${entry.wins}:${entry.losses}:${entry.draws}\n")
            }

            contentResolver.openOutputStream(newFile.uri)?.use { output ->
                output.write(content.toString().toByteArray())
            }

            Toast.makeText(this, "已导出 ${entries.size} 条记录到: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openImportPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "*/*"))
            }
            importLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val tempFile = File(getExternalFilesDir(null), "import_temp.txt")
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                // 直接调用 FloatWindowService 导入，避免广播不可靠问题
                FloatWindowService.instance?.importBookFile(tempFile)
                    ?: Toast.makeText(this, "悬浮窗服务未运行，无法导入", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = checkOverlayPermission()
        val running = FloatWindowService.instance != null

        tvStatus.text = if (running) {
            "悬浮球已启动"
        } else if (overlayOk) {
            "悬浮窗权限已就绪，点击启动"
        } else {
            "需要悬浮窗权限"
        }
        tvStatus.setTextColor(
            if (running) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
        )

        btnStart.text = if (running) "已启动" else "启动悬浮球"
        btnStart.isEnabled = !running && overlayOk
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFloatService() {
        val intent = Intent(this, FloatWindowService::class.java).apply {
            action = FloatWindowService.ACTION_SHOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus()
        Toast.makeText(this, "悬浮球已启动，可在任意界面使用", Toast.LENGTH_SHORT).show()
    }
}