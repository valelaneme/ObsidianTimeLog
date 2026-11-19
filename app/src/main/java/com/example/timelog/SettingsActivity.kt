package com.example.timelog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.timelog.data.Prefs
import com.example.timelog.data.VaultRepository

/**
 * 设置页：日记文件夹 + 倒计时默认分钟数。
 *
 * 首页不再放"选择文件夹/刷新"两个按钮：选完文件夹回到首页时，
 * MainActivity 会自动重新读取当天记录和常用候选（见 onResume / onActivityResult）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: VaultRepository
    private lateinit var prefs: Prefs
    private lateinit var tvFolder: TextView
    private lateinit var tvInfo: TextView
    private lateinit var etCountdownMinutes: EditText
    private lateinit var tvCountdownInfo: TextView

    companion object {
        private const val REQ_PICK = 71
        const val EXTRA_RELOAD = "settings_reload"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repo = VaultRepository(this)
        prefs = Prefs(this)
        tvFolder = findViewById(R.id.tvSettingsFolder)
        tvInfo = findViewById(R.id.tvSettingsInfo)
        etCountdownMinutes = findViewById(R.id.etCountdownMinutes)
        tvCountdownInfo = findViewById(R.id.tvCountdownInfo)

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSettingsPick).setOnClickListener { openFolderPicker() }
        findViewById<Button>(R.id.btnSettingsReload).setOnClickListener {
            markNeedReload()
            finish()
        }
        findViewById<Button>(R.id.btnSettingsClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btnSaveCountdown).setOnClickListener { saveCountdownMinutes() }
        refresh()
        refreshCountdown()
    }

    private fun refreshCountdown() {
        etCountdownMinutes.setText(prefs.countdownMinutes.toString())
        tvCountdownInfo.text = "点首页「倒计时」时用这个数预填（当前 ${prefs.countdownMinutes} 分钟）。"
    }

    private fun saveCountdownMinutes() {
        val minutes = etCountdownMinutes.text.toString().trim().toIntOrNull()
        if (minutes == null || minutes !in 1..600) {
            Toast.makeText(this, "请输入 1~600 的整数分钟", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.countdownMinutes = minutes
        refreshCountdown()
        Toast.makeText(this, "已保存：倒计时默认 $minutes 分钟", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val name = repo.root()?.name
        if (name == null) {
            tvFolder.text = "尚未选择日记文件夹"
            tvInfo.text = "请选择 Obsidian 里存放每日 md 文件的文件夹（文件名形如 2026-11-19.md）。"
        } else {
            tvFolder.text = "当前文件夹：$name"
            tvInfo.text = "已授权。换文件夹后回到首页会自动重新读取当天记录和常用候选；App 只读写这个文件夹里的 md，不会改名或搬走文件。"
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Toast.makeText(this, "授权未持久化：${e.message}", Toast.LENGTH_SHORT).show()
        }
        repo.treeUri = uri
        refresh()
        markNeedReload()
        Toast.makeText(this, "已选好文件夹，回到首页自动刷新", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清除文件夹选择？")
            .setMessage("只取消 App 对这个文件夹的授权，不会删除任何 md 文件。之后可以到设置页重新选择。")
            .setPositiveButton("清除") { _, _ ->
                val old = repo.treeUri
                if (old != null) {
                    try {
                        contentResolver.releasePersistableUriPermission(
                            old,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // 已经不持有授权时忽略；不影响清除本地记录
                    }
                }
                repo.treeUri = null
                refresh()
                markNeedReload()
                Toast.makeText(this, "已清除文件夹选择", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 告诉首页：回来要重新读数据。按系统返回键时这个结果同样会带回去。 */
    private fun markNeedReload() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RELOAD, true))
    }
}
