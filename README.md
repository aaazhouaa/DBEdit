# Edit（Android SQLite 编辑器）

一个原生 Android 应用：打开 `.db` 文件即可浏览数据表、搜索记录、直接修改并保存。

APK：`~/workspace/Edit-debug.apk`（debug 签名，可直接安装）

## 功能

### 浏览与编辑数据
| 功能 | 说明 |
| --- | --- |
| 选文件打开 | 系统文件选择器选 `.db`，校验 SQLite 文件头 |
| 表 / 视图列表 | 列出所有表和视图 + 行数，支持按名称过滤 |
| 分页浏览 | 每页 200 行，上一页 / 下一页 |
| 表内搜索 | 对该表所有列 `LIKE` 模糊匹配，`%` `_` 按字面量处理 |
| 全库搜索 | 在所有表的文本列中搜索，列出命中位置 |
| 单元格编辑 | 点单元格直接改值，可设为 `NULL`（NULL 与空串区分显示） |
| 新增 / 删除行 | `＋行` 插入；行尾 `✕` 删除（有确认） |
| SQL 控制台 | 「执行 SQL」，SELECT/PRAGMA 显示结果，其他语句直接执行 |
| 保存 / 另存为 | 写回原文件，或导出为新 `.db`；另有 `VACUUM` 压缩 |
| 导出 CSV | 整表导出（带 UTF-8 BOM，Excel 中文不乱码） |
| 最近打开 | 记录最近 8 个文件，持久化读授权 |

### 结构编辑（表列表页长按，或数据页右上角菜单）

入口后半段会先过一道**变更预览页**：列出新增/删除/改名/改类型/约束变化、新的列顺序、
以及每一列的「数据来源」，确认后才执行。

| 功能 | 说明 |
| --- | --- |
| 新建表 | 表名 + 任意列定义（类型、主键、NOT NULL、DEFAULT、自增） |
| 加列 | 新列可给默认值；若设 NOT NULL 必须给默认值（否则会拦下并提示） |
| 删列 | 从表定义中去掉；若该列被索引/触发器引用，会**提前给出明确提示**而不是抛 SQL 错误 |
| 改列名 | 通过「数据来源」把原列数据搬到新列名下，数据不丢 |
| 改类型 | 支持任意类型互转。**注意：重建时不做 CAST**，所以把 TEXT 列改成 INTEGER 时，非数字内容会原样保留在 INTEGER 亲和性列里（SQLite 允许），而不是被转成 0 |
| 主键调整 | 支持单列 INTEGER 主键（含 AUTOINCREMENT）与复合主键 |
| 视图支持 | 视图会被列出，但**只读**（视图没有 rowid，无法按行定位增删改） |

### 改错了怎么办

菜单里的 **「丢弃未保存的改动」** 会从原文件重新导入，完全回到上一次打开时的状态。
因为改的始终是副本、原文件在你点「保存」前不会被碰，所以只要**没保存**，任何误操作
（包括搬错列、误删列、改错类型）都能这样救回来。

结构修改采用 SQLite 官方的「重建表」流程：
`建新表(临时名) → 搬数据(含 rowid) → DROP 旧表 → 临时表改名 → 重建索引/本表触发器 → 恢复 AUTOINCREMENT 计数器 → 恢复外部触发器与视图`


## 界面与主题

视觉与设计令牌对齐 [AiCode](https://github.com/jieapi/AiCode) 的「默认蓝」主题，
取值直接来自其 `core/theme/AIEditorTheme.kt` 与 `AppThemePreset.kt`，未自行改色：

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| 主色 | `#2563EB` | 按钮、选中态、强调 |
| 页面底色 | `#F8F8F8` | 页面、顶栏、侧栏 |
| 卡片 | `#FFFFFF` | 分组卡片、列表项 |
| 描边/分隔 | `#E5E5EA`（0.5dp 线） | 卡片内分隔、表格线 |
| 弱化文字 | `#8E8E93` | 副标题、提示 |
| 输入框底 | `#F2F2F7` | 搜索/输入胶囊 |

- **扁平顶栏**：页面底色 + 深色文字（不是彩色条）。因此状态栏图标用深色。
  顶栏右侧统一留 8dp（`EdgeToEdge.TOOLBAR_END_PADDING_DP`），否则 ⋮ 会紧贴屏幕边缘。
- **分组卡片**：白色圆角 14dp 包住同类操作，卡片内 0.5dp 浅分隔线。
- **圆角**：卡片 14dp / 按钮 10dp / 输入框 10dp / 标签 8dp。
- **间距**：4 / 8 / 12 / 16 / 24 / 32 dp。
- 图标为 Feather 风格线条图标（1.9dp 描边、圆头），取自 AiCode 所用的同一套图标风格。
- **按钮只有两种底色**，避免主次不分：
  - 主要操作 —— 主色填充 + 白字（`Widget.Edit.Button`）；
  - 次要操作 —— 白底 + 1dp 描边 + 主色文字（`Widget.Edit.Button.Muted`，
    按下转主色容器色，禁用转弱化灰）。
  样式名 `Muted` 是历史名，语义现为「描边次按钮」。

### 侧边导航

左侧抽屉（宽 300dp、页面底色、朝向内容一侧 24dp 圆角），目前**只有「Edit」一项**。
抽屉顶部只按状态栏高度留白，不写标题或分组小字——只有一项时那是重复信息。

新增工具只需往 `AppNav.items` 追加一个 `NavItem`，侧栏会自动渲染条目、
分隔线与选中态，不需要改布局：

```kotlin
val items = listOf(
    NavItem(AppNav.TOOL_DB_EDITOR, R.string.nav_db_editor, R.drawable.ic_database),
    NavItem("log_viewer", R.string.nav_log_viewer, R.drawable.ic_clock),   // 直接加
)
```

## 数据安全设计

- **不直接改原文件**：先把 `.db` 复制到应用私有目录再打开，原文件在你点「保存」前不会被碰。
- **保存采用「关连接 → 整文件覆盖写 → 重开」**：保证内容完整落盘（避开 WAL 不能简单拼接的问题）。
- 打开时强制 `journal_mode=DELETE`，避免留下没有配对 `-wal` 的库，其他程序也能正常打开。
- **结构修改全在事务里**，失败整体回滚，原表和数据完好；执行前自动 `foreign_keys=OFF`、结束后恢复。
- 重建时按依赖拓扑顺序摘除/复原外部触发器与视图（含链式视图），并显式恢复 `sqlite_sequence`，避免自增 ID 从 1 重新开始。
- 「保存」「删除表」「清空表」等有明确提示；离开有未保存修改的页面会询问。

## 技术要点

- Kotlin + 经典 View（非 Compose），`compileSdk 35 / minSdk 26`，APK 约 3.5 MB。
- 多 Activity 共享一个数据库连接（`DbSession` 单例），避免跨页面传连接对象。
- 行定位分两类：`INTEGER PRIMARY KEY` / 无主键表用 `rowid`；文本或复合主键用主键列 + `IS ?`（对 NULL 主键安全）。主键列不可修改。
- 所有 SQL 构造收敛到无 Android 依赖的 `SqlUtil`，因此能在 JVM 上用真实 SQLite 引擎做端到端测试。
- 表格行固定宽度 + 单元格复用，避免 `wrap_content` 的 RecyclerView 全量测量卡顿。

## 构建

```bash
cd ~/workspace/Edit
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 单元测试
```

## 测试

**160 个测试全部通过**，`clean` 构建通过。

| 测试类 | 数量 | 验证内容 |
| --- | --- | --- |
| `SqlUtilTest` | 29 | 搜索/分页/增删改/CSV 的 SQL（真实 SQLite 引擎执行） |
| `SchemaEditTest` | 43 | 重建表：加/删/改列、索引/触发器/视图/外键保活、事务回滚 |
| `SchemaDiffTest` | 18 | 变更预览的诊断准确性 |
| `SchemaFormLogicTest` | 25 | 列编辑表单：主键编号、自增校正、NOT NULL 校验 |
| `InsetMathTest` | 8 | 系统栏 inset 计算 |
| `LayoutAndInsetsTest` | 26 | **真实加载每个布局/菜单 + 真实 Activity 跑 inset + 侧边栏** |
| `DrawerShapeTest` | 7 | 侧栏圆角/宽度/状态栏留白/无冗余标题 |
| `ToolbarAndButtonStyleTest` | 4 | 顶栏右侧留白、按钮描边与禁用态配色 |

后两类用 **Robolectric** 在 JVM 上跑真实 Android 框架，所以「顶栏不被状态栏盖住」
这类修复是被真实执行并断言的，不只是编译通过。

> 本机是 aarch64、无 KVM，也没有 arm64 版 adb/模拟器（Google 只发 `linux_x64` emulator），
> 因此无法在真机/模拟器上验证。SQLite 相关的逻辑收敛到纯 JVM 层测；
> 不碰 SQLite 的 UI 用 Robolectric 测（Robolectric 的 SQLite 原生库仅有 x86_64，故绕开）。
> **仍未覆盖：文件选择器（SAF）交互、跨 Activity 的完整流程、真实触摸操作**。

## 系统栏适配

Android 15（targetSdk 35）强制 edge-to-edge，窗口会延伸到状态栏与导航栏之下。
本应用的做法：

- 顶栏颜色延伸到状态栏下方，并把状态栏高度计入 Toolbar 自身的高度/内边距，
  这样顶栏是一整块颜色、标题垂直居中（而不是在根布局留一条底色不对的空白）。
- 左右与底部按 inset 留白；底部取「导航栏」与「输入法」的**较大者**（两者会同时上报，相加会多出一块空白）。
- 只在 API 30+ 启用；更低版本沿用系统默认排布，避免 `setDecorFitsSystemWindows`
  在 AppCompat 建立 subdecor 之前抢先安装 decor 而报错。

用真实 SQLite 引擎（sqlite-jdbc）逐条执行 `SqlUtil` 构造的 SQL，覆盖：
标识符转义、`LIKE` 通配符转义（含多列回归）、分页不重叠、`ORDER BY` 稳定性、
按 rowid / 文本主键 / 复合主键（含 NULL 主键）定位、更新/插入/删除、`NOT NULL` 约束、
CSV 转义、保存往返；表结构部分覆盖加列/删列/改名/改类型/复合主键、
索引与触发器与视图的摘除复原（含链式视图、外部触发器）、外键与级联删除、
`sqlite_sequence` 保留、rowid 保留、事务回滚，以及列名恰为 SQL 关键字时的误报防护；
变更预览部分覆盖新增/删除/改名/改类型/约束变化/列顺序/无效数据来源的诊断准确性。

> 为什么不用模拟器/Robolectric：本环境是 aarch64 容器、无 KVM，且 Robolectric 的原生运行时
> 只提供 `linux/x86_64`。因此把 SQL 构造收敛到纯逻辑层用同版本 SQLite 验证。
> **UI 层（Activity / 布局 / 文件选择器 / 结构编辑表单交互）未做自动化测试，建议真机点一遍。**

## 已知限制

- `WITHOUT ROWID` 表和虚拟表（FTS 等）不支持修改结构，会明确提示。
- 改动结构时若删除/改名被索引或触发器引用的列，需要先用「执行 SQL」处理掉那些对象
  （应用前会给出明确提示，不会静默失败）。
- BLOB 列以文本读取，可能显示为乱码；请勿编辑 BLOB 内容。
- 视图只读，不支持 `INSTEAD OF` 触发器的写入。
- 全库搜索遍历所有文本列，超大库（几十万行以上）可能较慢。
