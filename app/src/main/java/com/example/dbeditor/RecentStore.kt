package com.example.dbeditor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

/** 最近打开记录（用 SharedPreferences 存 Uri + 文件名，SAF 持久授权） */
object RecentStore {

    private const val PREF = "recent"
    private const val KEY = "items"

    fun add(context: Context, uri: Uri, name: String) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val current = list(context).toMutableList()
        current.removeAll { it.second == uri.toString() }
        current.add(0, name to uri.toString())
        while (current.size > 8) current.removeAt(current.size - 1)
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, current.joinToString("\n") { "${it.first}\u0001${it.second}" }).apply()
    }

    /** 返回 名称 -> Uri字符串 的列表，最近在前 */
    fun list(context: Context): List<Pair<String, String>> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return raw.split("\n").mapNotNull {
            val parts = it.split("\u0001")
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    fun last(context: Context): Pair<String, String>? = list(context).firstOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** 生成演示数据库 / 空数据库（不依赖外部文件） */
object DemoDb {

    /** 生成演示数据库，返回本地文件 */
    fun buildFor(context: Context): File {
        val file = File(context.filesDir, "demo/demo.db")
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE users(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "email TEXT," +
                    "age INTEGER," +
                    "city TEXT," +
                    "note TEXT)"
        )
        val data = listOf(
            arrayOf("张三", "zhangsan@example.com", 28, "北京", "研发工程师"),
            arrayOf("李四", "lisi@example.com", 34, "上海", "产品经理"),
            arrayOf("王五", "wangwu@example.com", 41, "深圳", "销售总监"),
            arrayOf("Alice", "alice@example.com", 25, "杭州", "设计师，喜欢咖啡和猫"),
            arrayOf("Bob", "bob@example.com", 30, "成都", "后端开发"),
            arrayOf("Carol", "carol@example.com", 22, "广州", "实习生")
        )
        data.forEach {
            db.execSQL(
                "INSERT INTO users(name,email,age,city,note) VALUES(?,?,?,?,?)",
                it
            )
        }
        db.execSQL(
            "CREATE TABLE products(" +
                    "sku TEXT PRIMARY KEY," +
                    "title TEXT NOT NULL," +
                    "price REAL," +
                    "stock INTEGER," +
                    "intro TEXT)"
        )
        listOf(
            arrayOf("A-001", "机械键盘", 499.0, 32, "87 键茶轴"),
            arrayOf("A-002", "无线鼠标", 129.0, 120, "2.4G + 蓝牙"),
            arrayOf("A-003", "显示器", 1299.0, 8, "27 寸 2K 165Hz，支持升降"),
            arrayOf("A-004", "USB-C 扩展坞", 259.0, 55, "全功能 Type-C")
        ).forEach {
            db.execSQL("INSERT INTO products(sku,title,price,stock,intro) VALUES(?,?,?,?,?)", it)
        }
        db.close()
        return file
    }

    /** 空的合法 SQLite 文件（用于「新建数据库」另存） */
    fun createEmpty(context: Context): File {
        val file = File(context.cacheDir, "empty.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        // 建表再删，确保文件写出有效的 SQLite 文件头
        db.execSQL("CREATE TABLE _dbedit_init(\"x\" TEXT)")
        db.execSQL("DROP TABLE _dbedit_init")
        db.close()
        return file
    }
}
