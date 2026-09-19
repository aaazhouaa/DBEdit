package com.example.dbeditor

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 进程级数据库会话。各 Activity 共享同一个已打开的数据库，
 * 避免在 Activity 之间传递连接对象。
 */
object DbSession {

    var manager: DbManager? = null
        private set

    fun get(context: Context): DbManager =
        manager ?: DbManager(context.applicationContext).also { manager = it }

    fun isOpen(): Boolean = manager?.isOpen == true

    fun close() {
        manager?.close()
    }

    /**
     * 打开一个本地文件作为数据库（演示库 / 新建库用，没有 SAF Uri）。
     * 复制到私有目录保证可读写。
     */
    fun openLocalFile(context: Context, src: File, displayName: String): DbManager {
        val m = get(context)
        m.close()
        val dir = File(context.filesDir, "dbs").apply { mkdirs() }
        val dest = File(dir, displayName)
        src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        m.openFromPath(dest, null, displayName)
        return m
    }

    fun openUri(context: Context, uri: Uri): DbManager {
        val m = get(context)
        m.close()
        m.openFromUri(uri)
        return m
    }
}
