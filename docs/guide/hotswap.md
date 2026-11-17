# 热重载（HotSwap / Remold）

> 平台：**仅桌面端**（Android 端不加载 agent） · 需要：**JetBrains Runtime (JBR 17/21/25+)** + `-XX:+AllowEnhancedClassRedefinition`

从 1.7 开始，mod-tools 不再自带旧版 `hotswap-agent`，热重载由 [Remold](https://github.com/I-hope1/Remold) 提供。模组发布包里自带：

- `remold-agent.jar`：Remold 核心（ASM 已重定位，natives 打包在 `libs/`）；
- `remold-plugin-arc.jar`：Arc/Mindustry 插件，负责 UI 布局比对、贴图/音频/着色器/bundle 热替换等；
- `remold-asm.jar`：未重定位的 ASM，`plugin-arc` 需要。

启动时 `ModTools` 先把 agent 挂到 system classloader，再让设置类链接 `remold.*`；具体代码见 [`RemoldBootstrap`](https://github.com/I-hope1/mod-tools/blob/main/src/modtools/unsupported/RemoldBootstrap.java) 和 [`HotSwapManager`](https://github.com/I-hope1/mod-tools/blob/main/src/modtools/unsupported/HotSwapManager.java)。

## 启用

1. 用 JBR 启动游戏（普通 JDK 只能改方法体；结构变化会提示重启）。
2. 打开 mod-tools 设置 → **HotSwap**，打开 `hot_swap` 主开关。
3. 在 `hot_swap_watch_paths` 里填入编译输出目录（每行一个），例如：
   - `build/classes/java/main`
   - `build/classes/kotlin/main`
4. 开启 `hotswap_event`，让文件变化自动触发热重载。
5. 正常编译；编译产物写入监视目录后，agent 会做防抖、差异分析、字节码对齐并按需重定义。

> 首次使用建议先在 `build/classes` 里改一个只含方法体的类验证链路，再试新增字段/资源等结构性变更。

## 设置项

这些开关会同步为 Remold 的 `-Dremold.agent.*` 系统属性（由 [`E_Hook`](https://github.com/I-hope1/mod-tools/blob/main/src/modtools/events/E_Hook.java) 写入）：

| 设置 | 作用 |
|:--|:--|
| `hot_swap` | 主开关；桌面端且 attach 可用时才能打开 |
| `hot_swap_watch_paths` | 监视目录列表（class 输出目录） |
| `redefine_mode` | `inject` / `lazy_load`，控制重定义时机 |
| `hotswap_blacklist` | 不处理的包前缀，默认排除 `java.`、`arc.`、`mindustry.`、`remold.` 等 |
| `retransform_loaded` | 对已加载类执行 retransform |
| `init_fix` | 新增字段的存量实例初始化（InitFix） |
| `force_static_lambda` | 原子化 Lambda，保持重定义前后方法签名稳定 |
| `force_reinit` | 重定义后强制重新初始化 |
| `lambda_align` | Lambda/匿名类编号漂移对齐 |
| `ui_hook` | Arc UI 热替换：重建受影响的 Table/Cell |
| `cell_property_ref` | 追踪 Cell 属性链 |
| `profile` / `capture_locals` / `capture_exceptions` | 方法探针、局部变量与异常捕获 |
| `capture_all_exceptions` | 捕获所有异常（开销更大） |

完整语义与限制以 Remold 文档为准：

- [Remold README](https://github.com/I-hope1/Remold#readme)
- [Remold 实现状态与已知边界](https://github.com/I-hope1/Remold/blob/main/docs/status.md)

## 与旧版 hotswap-agent 的区别

旧版文档里的 `nipx.annotation.*`（`@Reloadable`、`@Tracker`、`@Profile`、`@OnReload`）基于已移除的 `hotswap-agent` 1.5.9，不再随 1.7 提供。现在如果要写热重载回调/存量字段覆写，请使用 Remold 的注解（`remold.annotation.OnReload`、`remold.annotation.RemoldReinit`），并参考 Remold 仓库的 `api` 模块。

## 常见问题

- **日志提示 plugin-arc missing**：确认发布包里的 `remold-plugin-arc.jar`、`remold-asm.jar` 存在且 `-Dremold.plugin.jars` 指向它们；从 CI/Release 下载的完整包一般不会有这个问题。
- **修改结构后被拒绝**：这是安全门在保护运行中的实例；查看日志里的拒绝原因，必要时重启游戏。能安全回填的字段变更取决于 JBR 能力与 Remold 的安全门。
- **Android 没有热重载**：热重载依赖 desktop JVMTI/JBR，Android 端只提供工具集本身。
