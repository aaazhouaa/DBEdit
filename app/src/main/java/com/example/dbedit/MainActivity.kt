package com.example.dbedit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.dbedit.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private var edgeToEdgeEnabled = false
    private var insetsApplied = false

    private lateinit var binding: ActivityMainBinding

    init {
        // 必须在 super.onCreate 之前处理系统栏（详见 EdgeToEdge.setup）
        addOnContextAvailableListener {
            edgeToEdgeEnabled = EdgeToEdge.setup(this)
        }
    }

    private val pickDb = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.data?.let { openUri(it) }
        }
    }

    private val createDb =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val empty = DemoDb.createEmpty(this)
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    empty.inputStream().use { it.copyTo(out) }
                }
                openUri(uri)
            } catch (e: Exception) {
                toast("新建失败：${e.message ?: e.javaClass.simpleName}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = getString(R.string.app_name)

        setupHomeActions()
        renderRecent()
    }

    // ---------------- 首页操作 ----------------

    private fun setupHomeActions() {
        ActionRow(binding.rowPick.root).bind(
            getString(R.string.main_row_pick),
            getString(R.string.main_row_pick_sub),
            R.drawable.ic_folder_open
        ) { pickDb.launch(pickIntent()) }

        ActionRow(binding.rowDemo.root).bind(
            getString(R.string.main_row_demo),
            getString(R.string.main_row_demo_sub),
            R.drawable.ic_play_circle
        ) { openDemo() }

        ActionRow(binding.rowCreate.root).bind(
            getString(R.string.main_row_create),
            getString(R.string.main_row_create_sub),
            R.drawable.ic_file_plus
        ) { createDb.launch("new_database.db") }

        ActionRow(binding.rowRecent.root).bind(
            getString(R.string.main_group_recent),
            null,
            R.drawable.ic_clock
        ) { showRecent() }
    }

    private fun pickIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/x-sqlite3",
                "application/vnd.sqlite3",
                "application/octet-stream",
                "application/x-db",
                "*/*"
            )
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun openUri(uri: Uri) {
        try {
            DbSession.openUri(this, uri)
            RecentStore.add(this, uri, DbSession.get(this).sourceName)
            startActivity(Intent(this, TableListActivity::class.java))
            renderRecent()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("打开失败")
                .setMessage(e.message ?: "未知错误")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun openDemo() {
        try {
            val file = DemoDb.buildFor(this)
            DbSession.openLocalFile(this, file, "demo.db")
            startActivity(Intent(this, TableListActivity::class.java))
        } catch (e: Exception) {
            toast("演示库打开失败：${e.message}")
        }
    }

    private fun renderRecent() {
        val last = RecentStore.last(this)
        val row = ActionRow(binding.rowRecent.root)
        if (last == null) {
            binding.groupRecent.visibility = View.GONE
        } else {
            binding.groupRecent.visibility = View.VISIBLE
            row.bind(
                getString(R.string.main_group_recent),
                last.first,
                R.drawable.ic_clock
            ) { showRecent() }
        }
    }

    private fun showRecent() {
        val items = RecentStore.list(this)
        if (items.isEmpty()) {
            toast("暂无记录")
            return
        }
        val labels = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("最近打开")
            .setItems(labels) { _, which -> openUri(Uri.parse(items[which].second)) }
            .setNeutralButton("清空记录") { _, _ ->
                RecentStore.clear(this)
                renderRecent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- inset ----------------

    override fun onContentChanged() {
        super.onContentChanged()
        applyInsetsOnce()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        applyInsetsOnce()
    }

    /** 主内容区按状态栏/导航栏/输入法留白 */
    private fun applyInsetsOnce() {
        if (insetsApplied || !edgeToEdgeEnabled) return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        insetsApplied = true
        // 顶栏自己承担状态栏高度，底部按导航栏留白
        EdgeToEdge.applyInsetsTo(this, content.getChildAt(0))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
