[English](README_en.md) | 简体中文 · [在线文档](https://i-hope1.github.io/mod-tools/) · [文档源码](docs/index.md) · [热重载 (Remold)](https://github.com/I-hope1/Remold) · [Releases](https://github.com/i-hope1/mod-tools/releases)

<div align="center"><h1>Mod Tools</h1>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/v/release/i-hope1/mod-tools?style=flat-square&include_prereleases&label=version" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/total.svg?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/latest/total?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/issues"><img src="https://img.shields.io/github/issues-raw/i-hope1/mod-tools.svg?style=flat-square&label=issues" /></a>
  <a href="https://github.com/i-hope1/mod-tools/graphs/contributors"><img src="https://img.shields.io/github/contributors/i-hope1/mod-tools?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/blob/master/LICENSE"><img src="https://img.shields.io/github/license/i-hope1/mod-tools?style=flat-square" /></a>
</div>

**Mod Tools** 是一个面向 **Mindustry v7** 的开发者工具箱：把 JavaScript 编辑器、UI 审查器、内容浏览器、单位生成器、任务执行器和开发期热重载都放进游戏内。调 UI、试 Mindustry API、写启动脚本时，不必每次都重启游戏。

<div align="center"><img src="./assets/icon.png" width="240" alt="Mod Tools"></div>

## 功能一览

| 模块 | 能做什么 | 文档 |
|:--|:--|:--|
| **Tester** | 游戏内 Rhino JS 编辑器/执行器：历史记录、启动脚本、`$` 快捷 API、补全与语法高亮 | [Tester](docs/features/01-tester.md) |
| **Executor** | 任务树执行器：单次/循环/打断/编辑任务，自定义 JS 任务，状态过滤，与启动脚本和 UnitSpawn 联动 | [Executor](docs/features/02-executor.md) |
| **Selection** | 选择 `Tile` / `Building` / `Bullet` / `Unit`，对选中项执行内置命令 | [Selection](docs/features/03-selection.md) |
| **UnitSpawn** | 多队伍、定点、批量生成单位；可把生成配置作为任务提交到 Executor | [UnitSpawn](docs/features/04-unit-spawn.md) |
| **ReviewElement** | 审查 UI 元素树、实时编辑字段、复制为 JS 变量、布局体检、Cell 详情 | [审查元素](docs/features/05-review-element.md) |
| **ShowUIList / ShowInfoWindow** | 浏览 icon / tex / styles / colors / interp / actions，查看任意对象详情与自定义 Viewer | [UI 工具](docs/features/06-ui-inspector.md) |
| **窗口体系** | Frag、Window、WindowManager：窗口切换、最小化、贴边、强制显示 | [窗口](docs/features/07-windows.md) |
| **ContentList / LogDisplay / Profiler / Pause** | 内容浏览器、游戏内日志、方法探针/火焰图/死锁检查、实验性暂停 | [其他工具](docs/features/08-misc.md) |
| **扩展** | Override Scene、HTTP 重定向 | [扩展](docs/features/09-extensions.md) |
| **热重载** | 桌面端 JBR 下把编译产物热替换进运行中的游戏，由 [Remold](https://github.com/I-hope1/Remold) 提供 | [热重载](docs/hotswap.md) |

## 安装

1. 从 [Releases](https://github.com/i-hope1/mod-tools/releases) 下载最新的 `mod-tools-*.jar`。
2. 放进 Mindustry 的 `mods/` 目录（游戏内「模组」页面可以直接打开该目录）。
3. 启动游戏，在模组设置中启用 **mod-tools**，然后从 mod-tools 菜单打开需要的工具。

要求 **Mindustry v7**（`minGameVersion` 159.2）。桌面端功能完整；Android 端也可以使用，但热重载和部分桌面专用工具不可用。热重载还需要 **JetBrains Runtime (JBR 17/21/25+)**，详见 [热重载](docs/hotswap.md)。

## 快速开始

打开 **Tester**，输入：

```js
Log.info("Hello from mod-tools!");
```

按 `Ctrl` + `Shift` + `Enter` 执行。想看某个按钮/标签是怎么搭出来的，按 `Ctrl` + `Shift` + `C` 打开 **ReviewElement**，再按 `Ctrl` + `Alt` + `D` 显示元素边界。

更完整的工具说明在 [文档导航](docs/index.md)。

## 文档

| 文档 | 内容 |
|:--|:--|
| [文档导航](docs/index.md) | 全部专题页的入口 |
| [Tester](docs/features/01-tester.md) | JS 编辑器、快捷键、`$` API、启动脚本 |
| [Executor](docs/features/02-executor.md) | 任务状态、执行/循环/打断、状态过滤、自定义任务 |
| [Selection](docs/features/03-selection.md) / [UnitSpawn](docs/features/04-unit-spawn.md) | 选择器与单位生成 |
| [ReviewElement](docs/features/05-review-element.md) | UI 审查、字段编辑、布局体检 |
| [UI 工具](docs/features/06-ui-inspector.md) / [窗口](docs/features/07-windows.md) | ShowUIList、ShowInfoWindow、Frag/Window |
| [其他工具](docs/features/08-misc.md) / [扩展](docs/features/09-extensions.md) | 内容浏览器、日志、Profiler、Pause、Override Scene 等 |
| [热重载](docs/hotswap.md) | Remold 热重载的启用方式与设置项 |
| [开发与构建](docs/development.md) | 目录结构、构建、运行、测试 |

## 构建

需要 **JDK 25**。构建桌面端 jar：

```bash
./gradlew jar
```

发布包（额外包含 Android `classes.dex`，需要配置 `ANDROID_HOME`）：

```bash
./gradlew ZCompileAll
```

本地调试可以在 `gradle.properties` 中设置 `MdtDataDir` 指向 Mindustry 安装/数据目录，然后：

```bash
./gradlew playGame          # 构建 jar、复制到 mods 并启动游戏
./gradlew test              # JUnit
./gradlew playGameDirect    # 直接使用已部署的 jar 启动
```

项目里部分代码依赖 JavaDoc（注解处理器读取注释），**不要删除 JavaDoc**。更多构建细节见 [开发与构建](docs/development.md)。

## 热重载

从 1.7 开始，mod-tools 的热重载由 [Remold](https://github.com/I-hope1/Remold) 提供：启动时把 `remold-agent.jar` 挂到 system classloader，再通过 `remold-plugin-arc` 接入 Arc 的 UI 热替换、贴图/资源刷新与 InitFix。桌面端开启 `hot_swap` 并配置监视路径即可使用，具体见 [热重载](docs/hotswap.md)。

## 许可证

本项目使用 [GNU Affero General Public License v3.0](LICENSE)。热重载依赖的 Remold 按其仓库的分模块许可证分发。
