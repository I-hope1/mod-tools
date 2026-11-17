# Mod Tools 开发规则

Mod Tools 是面向 **Mindustry v7** 的开发者工具箱模组（Java 25 + Arc/Mindustry）。

## 先读

- 构建、运行、测试：[docs/guide/development.md](docs/guide/development.md)
- 文档站与 CI：同上；文档源在 `docs/`，使用 VitePress。
- 热重载（Remold）：[docs/guide/hotswap.md](docs/guide/hotswap.md)

## 常用命令

```bash
./gradlew compileJava    # 快速编译
./gradlew test           # JUnit
./gradlew check          # 完整检查（CI 使用）
./gradlew jar            # 桌面端 mod jar
./gradlew ZCompileAll    # 发布包（含 Android classes.dex）
./gradlew playGame       # 部署到 MdtDataDir/mods 并启动游戏
```

## 目录

| 路径 | 内容 |
|:--|:--|
| `src/modtools/content/` | 游戏内工具（Tester、Executor、Selection、UnitSpawn、ReviewElement…） |
| `src/modtools/events/` | 任务树、事件、设置项 |
| `src/modtools/ui/` | Arc/Mindustry UI 组件与窗口 |
| `src/modtools/utils/`、`src/modtools/jsfunc/` | 工具类、JS 桥接 |
| `src/modtools/unsupported/` | Remold/hotswap 引导、Android 兼容 |
| `annotations/`、`compiler/`、`utils-agent/`、`magic-accessor/` | 注解处理器、编译期工具、agent |
| `test/`、`jmh/` | JUnit 与 JMH |
| `assets/`、`docs/` | 模组资源与 VitePress 文档 |

## CI

- `Build project`：仅在构建相关路径变化时构建发布产物。
- `Test`：仅在源码/测试/Gradle 配置变化时跑 `./gradlew check`。
- `Deploy docs`：仅在文档相关路径变化时构建并部署 VitePress 到 GitHub Pages。

## 约定

- 不要提交 `build/`、`node_modules/`、`docs/.vitepress/dist/`。
- 部分代码依赖 JavaDoc（注解处理器读取注释），不要批量删除 JavaDoc。
- 改 UI 后用 `./gradlew playGame` 在游戏内验证；改热重载相关逻辑还需在 JBR 环境验证 attach 与重定义日志。
- 文档专题页以中文维护，英文入口见 `README_en.md` 与 `docs/index_en.md`。
