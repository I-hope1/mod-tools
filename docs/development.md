[← 文档导航](index.md)

# 开发与构建

> 适用：修改 mod-tools 源码、打包发布、跑测试

## 环境要求

- **JDK 25**：`build.gradle` 的 `sourceCompatibility` / `targetCompatibility` 是 25，CI 也用 25。
- **Android SDK**：只有 `jarAndroid` / `ZCompileAll`（发布包里的 `classes.dex`）需要，配置 `ANDROID_HOME` 或 `local.properties`。
- **Gradle**：使用仓库自带的 `gradlew`。
- **Mindustry v7 运行期依赖**：由 `build.gradle` 的 Mindustry/Arc 依赖与 `hstestImplementation` 提供；普通构建和 `playGame` 使用本地 `MdtDataDir`。

## 常用命令

```bash
./gradlew jar            # 桌面端 mod jar -> build/libs/mod-tools-<version>.jar
./gradlew test           # JUnit（根项目 test/ 下的用例）
./gradlew playGame       # 构建并复制到 MdtDataDir/mods，然后启动 MindustryX
./gradlew playGameDirect # 直接使用已部署的 jar 启动（Mindustry.jar）
./gradlew playAndroid    # 通过 adb 安装到 Android 设备（需要 Android SDK/adb）
./gradlew ZCompileAll    # 发布包：桌面 jar + Android classes.dex
./gradlew jmh -PjmhIncludes=AlignBenchmark -PjmhProfilers=gc  # JMH
./gradlew runTest -PmainClass=JSSyntaxTest                   # 跑带 main 的手动入口
```

`MdtDataDir` 在 `gradle.properties` 中配置，指向 Mindustry 的安装/数据目录；`playGame` 会构建 jar、检查目标文件是否被游戏占用、复制到 `mods/` 后启动。

> 项目里部分代码依赖 JavaDoc（注解处理器读取注释生成代码），**不要批量删除 JavaDoc**，否则可能编译失败或生成结果不同。

## 目录结构

| 路径 | 内容 |
|:--|:--|
| `src/modtools/content/` | 所有游戏内工具（Tester、Executor、Selection、UnitSpawn、ReviewElement…） |
| `src/modtools/events/` | 任务树、事件、设置项（`ExecuteTree`、`TaskNode`、`E_Hook`…） |
| `src/modtools/ui/` | Arc/Mindustry UI 组件、窗口、样式、输入框 |
| `src/modtools/utils/` | 工具类、搜索/过滤、反射、字符串、文件 IO、性能分析 |
| `src/modtools/jsfunc/` | Rhino/JS 桥接与 `IntFunc` 实现 |
| `src/modtools/struct/` | 自定义集合、调度、线程适配 |
| `src/modtools/unsupported/` | Remold/hotswap 引导、Android 兼容等平台相关代码 |
| `annotations/`、`compiler/`、`utils-agent/` | 注解处理器、编译期工具、agent 工具 |
| `magic-accessor/` | 字段访问器（annotation processor）子模块 |
| `scratch/` | 临时探针/基准，不参与正式构建 |
| `test/` | JUnit 与带 `main` 的手动测试入口 |
| `assets/` | bundles、shaders、sprites、libs、图标 |
| `docs/` | 本目录 |

本地想跳转 Mindustry/Arc 源码时，可以把上游源码放到 `.sources/mindustry`（仓库已忽略该目录）。

## 测试与 CI

- 根项目 `./gradlew test` 跑 JUnit；`runTest -PmainClass=...` 跑 `test/` 下带 `main` 的手动用例。
- GitHub Actions：
  - `.github/workflows/deploy.yml`：推送时构建发布产物；
  - `.github/workflows/hstest.yml`：JUnit / javac 多版本矩阵；
  - `.github/workflows/onpublish.yml`：Release 上传；
  - `.github/workflows/release-upload.yml`、`agent.yml`：发布与 agent 相关流程。

## 提交前检查

```bash
./gradlew compileJava
./gradlew test
```

如果改动涉及 UI，建议至少用 `./gradlew playGame` 在游戏内跑一遍相关工具；涉及热重载的改动还要在 JBR 环境验证 attach 与重定义日志。
