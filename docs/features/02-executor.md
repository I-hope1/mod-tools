[← 文档导航](../index.md)

# Executor：任务执行器

> 入口：mod-tools 菜单 → **Executor** · 平台：桌面端 / Android

Executor 是一棵可交互的任务树：每个节点对应一个 `TaskNode`，可以是启动脚本、自定义 JS 任务，或 UnitSpawn 提交的生成任务。可以单独执行、循环、打断、编辑和删除，并实时看到运行状态。

## 任务从哪里来

| 来源 | 说明 |
|:--|:--|
| `JS startup` | Tester 的启动脚本与收藏夹启动项 |
| `CustomJS` | 在 Executor 里点 **new task**，输入一段 JS 后创建的自定义任务 |
| `unitSpawn` | UnitSpawn 的 **Post Task** 提交的单位生成任务 |

任务按树组织：父节点可以展开/折叠子节点；删除父节点会连同子任务一起删除。

## 状态

状态图标和左侧/上下的颜色边线会随状态变化：

| 状态 | 含义 | 颜色 |
|:--|:--|:--|
| `NoTask` | 只是容器，没有可执行任务 | 浅灰 |
| `Paused` | 已暂停/未开始 | 橙色 |
| `Running` | 正在执行；图标持续旋转 | 粉色 |
| `OK` | 最近一次执行成功结束 | 绿色 |
| `Error` | 执行抛异常，异常会打印到日志 | 红色 |

## 行内操作

| 按钮 | 作用 |
|:--|:--|
| 扳手 | 编辑任务配置：执行间隔、是否使用世界计时器、重复次数 |
| 循环图标 | 一直运行（`repeatCount = -1`） |
| 方块图标 | 立即执行一次（`repeatCount = 0`） |
| 垃圾桶 | 删除任务（含子任务） |
| 打断图标 | 取消当前调度，回到 `Paused` |

右键（移动端长按）任务行还可以：

- **copy code**：把该任务的 JS 代码复制到剪贴板；
- **save code in map**：把代码保存到当前地图的自定义数据里。

## 执行配置

在任务的编辑窗口里可以改：

- **执行间隔**：两次执行之间的秒数（最小值 0.001s，默认 `1/60`）。
- **世界计时器**：勾选后使用 `worldTimer`，只在游戏未暂停时推进；不勾选则使用 Arc 默认 `Timer`，游戏暂停时仍会调度。
- **重复次数**：`0` 表示只执行一次，`-1` 表示无限循环，其它正整数表示额外执行次数。

任务由 Arc `Timer` 投递回 `Core.app` 主线程执行，因此任务代码里可以直接碰游戏/UI 状态，不需要自己切线程。

## 状态过滤

顶部 **status** 按钮按位掩码筛选任务：只显示选中的状态。过滤是**实时**的——任务从 Running 变成 OK 时，列表会自动增删，不需要手动刷新。

## 自动同步与刷新

- 面板打开时，新增/删除任务会自动反映到列表。
- 刷新按钮会完整重建列表（折叠状态也会恢复为默认展开）。
- 关闭再打开 Executor 会同步到最新的任务树。

> 任务树的变化通过 `ExecuteTree.structureVersion` / `statusVersion` 通知 UI；状态更新只在变化时重绘。相关实现见 [`Executor.java`](https://github.com/I-hope1/mod-tools/blob/main/src/modtools/content/debug/Executor.java) 与 [`ExecuteTree.java`](https://github.com/I-hope1/mod-tools/blob/main/src/modtools/events/ExecuteTree.java)。
