package com.example.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * 所有数据页面的基类：提供共用菜单（保存 / 另存为 / 压缩）与未保存提示。
 */
abstract class BaseActivity : AppCompatActivity() {

    init {
        // 必须在 super.onCreate 之前：decor 一旦生成，edge-to-edge 开关就改不动了。
        // 放在 init 里可以保证早于 onCreate（Activity 构造时就会执行）。
        (this as AppCompatActivity).addOnContextAvailableListener {
            edgeToEdgeEnabled = EdgeToEdge.setup(this)
        }
    }

    /** 本 Activity 是否已启用 edge-to-edge；未启用时不做任何 inset 补偿 */
    private var edgeToEdgeEnabled = false
    private var insetsApplied = false

    protected val manager: DbManager get() = DbSession.get(this)

    /**
     * 设置顶栏标题，可选显示返回箭头（返回行为是直接 finish）。
     * 需要「返回前先问是否保存」的页面请自行覆盖 navigation 点击监听。
     */
    protected fun setBarTitle(title: String, showBack: Boolean = false) {
        val toolbar = findViewById<Toolbar>(R.id.toolbar) ?: return
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = title
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
            toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            toolbar.setNavigationOnClickListener { finish() }
        }
    }

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching { manager.saveAs(uri) }
                .onSuccess {
                    RecentStore.add(this, uri, manager.sourceName)
                    toast("已保存到新文件：${manager.sourceName}")
                    onDatabaseSaved()
                }
                .onFailure { showError("另存为失败", it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DbSession.isOpen()) {
            toast("数据库未打开，请重新选择文件")
            finish()
        }
    }

    /**
     * setContentView 之后回调（setContentView(View) 与 setContentView(int) 都会走到），
     * 此时视图树已就绪，可以计算并应用 inset。
     */
    override fun onContentChanged() {
        super.onContentChanged()
        applyInsetsOnce()
    }

    /** 兜底：个别路径若未触发 onContentChanged，这里再试一次 */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        applyInsetsOnce()
    }

    private fun applyInsetsOnce() {
        if (insetsApplied || !edgeToEdgeEnabled) return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        insetsApplied = true
        EdgeToEdge.applyInsetsTo(this, content.getChildAt(0))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_common, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> {
                doSave()
                return true
            }
            R.id.action_save_as -> {
                saveAsLauncher.launch(manager.sourceName.ifBlank { "database.db" })
                return true
            }
            R.id.action_discard -> {
                if (!manager.dirty) {
                    toast("当前没有未保存的改动")
                    return true
                }
                if (!manager.canSaveInPlace) {
                    toast("这是演示/新建的数据库，没有可恢复的原文件")
                    return true
                }
                confirm(
                    "丢弃未保存的改动",
                    "将放弃当前所有未保存的修改（包括改表结构），从原文件重新加载。\n" +
                            "因为还没保存，原文件本来就是完整的，所以这步是安全的。"
                ) {
                    if (manager.discardChanges()) {
                        toast("已恢复到原文件的状态")
                        recreate()
                    } else {
                        toast("恢复失败，请重新选择文件打开")
                    }
                }
                return true
            }
            R.id.action_vacuum -> {
                confirm("压缩数据库", "将执行 VACUUM 重整数据库文件，可能耗时较长，继续？") {
                    runCatching { manager.vacuum() }
                        .onSuccess { toast("压缩完成（记得保存以写回文件）") }
                        .onFailure { showError("压缩失败", it) }
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    protected open fun doSave() {
        if (!manager.canSaveInPlace) {
            toast("这是演示/新建的数据库，请用「另存为」保存")
            saveAsLauncher.launch(manager.sourceName.ifBlank { "database.db" })
            return
        }
        runCatching { manager.saveToSource() }
            .onSuccess {
                toast("已保存到 ${manager.sourceName}")
                onDatabaseSaved()
            }
            .onFailure { showError("保存失败", it) }
    }

    /** 保存成功后回调，子类可刷新列表 */
    protected open fun onDatabaseSaved() {}

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    protected fun showError(title: String, e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton("知道了", null)
            .show()
    }

    protected fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .show()
    }

    /** 离开时若有未保存修改则提示 */
    protected fun confirmLeave(onLeave: () -> Unit) {
        if (!manager.dirty) {
            onLeave()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("有未保存的修改")
            .setMessage("是否先保存再离开？")
            .setPositiveButton("保存并离开") { _, _ ->
                // 保存失败不能继续离开，否则改动就丢了
                val ok = runCatching {
                    if (manager.canSaveInPlace) {
                        manager.saveToSource()
                        true
                    } else false
                }.getOrDefault(false)
                if (ok) {
                    toast("已保存")
                    onLeave()
                } else {
                    toast("保存失败（这是演示/新建的库？请用「另存为」），已取消离开")
                }
            }
            .setNeutralButton("直接离开") { _, _ -> onLeave() }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val EXTRA_TABLE = "table"
    }
}
