# 其他工具

> 入口：mod-tools 菜单 → 对应工具

## ContentList（内容浏览器）

- 标签页：`Fx`、`Bullet`、`Blocks`、`Items`、`Liquids`、`Planets`。
- 顶部搜索框按名称过滤；`RebuildAll` 重新扫描内容。
- 短按条目：执行默认动作（生成特效、创建子弹、切换 `UnlockableContent` 解锁状态）。
- 长按条目：复制名称；长按图标：重新载入该内容的资源/图标。

## LogDisplay（游戏内日志）

- **last_log**：实时显示游戏控制台消息；`Source` 打开 `last_log.txt`，`Clear All` 清空控制台。
- **crashes**：列出崩溃日志（`crashes/` 目录），点击展开内容。
- 桌面端底部有 JS 输入框：按 `Ctrl` + `Enter` 把输入内容发送到游戏控制台。
- 该工具默认不加载，需要在设置里启用。

## Profiler（性能分析）

> 仅桌面端；需要热重载 agent 已加载（见 [热重载](/guide/hotswap)）。

Profiler 提供：

- **Probe Selector**：选择要插桩的方法；
- **Profiler Window**：按方法查看调用次数、平均/总耗时，可排序、搜索、清空；
- **Flame Graph**：火焰图；
- **Hot Swap Log**：热重载日志；
- **Check Deadlocks**：运行死锁检测，输出线程栈与锁环。

## Pause（暂停）

实验性工具：按子系统（UI、逻辑、Renderer、Timer）分别暂停，支持 1 tick / 10 tick / 60 tick / 10 秒的临时暂停。适合调试渲染和更新顺序，默认不加载。

## Settings / GameSettings / KeyCodeSetter

| 工具 | 用途 |
|:--|:--|
| **Settings** | mod-tools 自身设置：内容加载开关、热重载开关、快捷键等 |
| **GameSettings** | 浏览并修改 `Core.settings` 里的所有键：支持搜索、按类型/状态过滤、重置/删除/设置值 |
| **KeyCodeSetter** | 给按钮绑定自定义快捷键，也可以查看游戏内部按键表 |

> GameSettings 直接改的是 Mindustry 的全局设置，改错可能影响游戏行为；不确定的键先记录原值再改。
