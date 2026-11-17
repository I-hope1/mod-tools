---
layout: home

hero:
  name: Mod Tools
  text: Mindustry v7 开发者工具箱
  tagline: 游戏内 JS 编辑器、UI 审查器、内容浏览器、单位生成器、任务执行器，以及桌面端热重载。
  image:
    src: /assets/icon.png
    alt: Mod Tools
  actions:
    - theme: brand
      text: 开始阅读
      link: /guide/
    - theme: alt
      text: GitHub
      link: https://github.com/I-hope1/mod-tools

features:
  - icon: 🧪
    title: Tester
    details: 游戏内 Rhino JS 编辑器与执行器，支持历史、启动脚本和 `$` 快捷 API。
    link: /guide/tester
    linkText: 阅读
  - icon: 🧵
    title: Executor
    details: 任务树执行器：执行、循环、打断、状态过滤和自定义 JS 任务。
    link: /guide/executor
    linkText: 阅读
  - icon: 🔍
    title: ReviewElement
    details: 审查 Arc UI 元素树，实时改字段，复制为 JS 变量，跑布局体检。
    link: /guide/review-element
    linkText: 阅读
  - icon: 🧱
    title: Selection & UnitSpawn
    details: 框选地块/建筑/单位并执行命令，或按队伍和坐标批量生成单位。
    link: /guide/selection
    linkText: 阅读
  - icon: 🧰
    title: 更多工具
    details: 内容浏览器、游戏内日志、性能分析、窗口管理和实验性暂停。
    link: /guide/misc
    linkText: 阅读
  - icon: ⚡
    title: HotSwap / Remold
    details: 桌面端 JBR 下把编译产物热替换进游戏，由 Remold 提供。
    link: /guide/hotswap
    linkText: 阅读
---

## 为什么用它

- **改 UI 不用重启**：ReviewElement 可以直接改 `Element` 字段，改完立即生效。
- **试 API 不用写完整模组**：Tester 里一段 JS 就能访问 Mindustry/Arc 的类。
- **任务可观察**：Executor 把启动脚本、自定义任务和 UnitSpawn 任务放在同一棵树上，状态和异常一目了然。
- **热重载**：桌面端 JBR 下由 [Remold](https://github.com/I-hope1/Remold) 负责类/资源热替换与存量实例初始化。

> 从左侧「快速开始」进入正文；右上角可以切换明暗主题或搜索。
