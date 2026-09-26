# Magic JS

`magic-js` 是专为高性能 JVM 打造的轻量级、现代化的 JavaScript 运行时引擎。基于 **ASM 动态字节码编译 + invokedynamic (indy) 多态内联缓存 + 隐藏类 (Shape) 架构 + 原始类型特化栈** 实现，具备极高吞吐量与极低损耗的 Java 互操作能力。

---

## 核心架构特性

1. **动态链接与内联缓存 (Inline Caching)**
   * 基于 `JSLinker` 与 `invokedynamic`，实现单态 (Monomorphic)、多态 (Polymorphic, 扁平化 Switch 守卫) 与超态 (Megamorphic) 快速调用。
   * 支持原型链属性查找的 `SwitchPoint` 失效保护。
2. **隐藏类对象系统 (Shape / Hidden Class)**
   * 基于 `JSShape` 的变迁树（Transition Tree）管理属性偏移量（offset），对象属性以紧凑扁平数组存储。
3. **原始类型特化执行 (Primitive Specialization)**
   * 支持针对 `double`、`long`、`int` 的专用调用协议（如 `call0Double`、`call1Double`、`call2Double` 等），大幅减少数值装箱与拆箱损耗。
4. **无缝 Java 双向互操作 (Java Interop)**
   * 自动支持 Java 类字段/方法直调、单抽象方法接口（SAM）与 JS 函数的双向适配、以及 JS 原型链向装箱原始类型的无缝派发。

---

## 数值体系与浮点转换（dtoa）路线选型

在 JavaScript 引擎中，数值转字符串（`Number.prototype.toString`、`toFixed`、`toPrecision`、`toExponential`）不仅是高频热点操作，而且受 **ECMA-262 规范** 的严格约束。

#### 1. 当前架构落地方案

```
                          ┌───────────────────────────┐
                          │   JSOps.numberToString    │
                          └─────────────┬─────────────┘
                                        │
                         [Safe Integer 快速格式化]
                         (命中区间: [-2^53+1, 2^53-1])
                                        │ (非安全整数 / 浮点小数)
                                        ▼
                          ┌───────────────────────────┐
                          │  DoubleConversion (V8)    │
                          ├─────────────┬─────────────┤
                          │ Schubfach   │ 抽屉原理 (100% 确定性单趟极速最短表示)
                          │ FixedDtoa   │ toFixed() 专用 128 位定点生成
                          │ BignumDtoa  │ toFixed/toPrecision 超长精度兜底
                          │ DtoaBuffer  │ 100% 遵从 ECMA-262 输出格式状态机
                          └───────────────────────────┘
```

* **安全整数快道**：当 double 为整数且处于安全整数区间 $[-9007199254740991, 9007199254740991]$ 内时，快速提取 `long` 格式化，小整数直接命中全局字符串缓存池（`SMALL_INT_STRINGS`），实现 0 内存开销。
* **浮点格式化套件**：引入工业级 `doubleconv` 模块（结合 **Schubfach**、`FixedDtoa`、`BignumDtoa`、`DtoaBuffer`），100% 遵从 ECMA-262 规范要求：
  * 最短十进制表示：采用 **Schubfach** 抽屉原理算法，纯 64 位 `long` 与 `Math.multiplyHigh` 硬件指令加速，**100% 单趟产出，彻底消灭 BigNum 回退**；
  * 规范平铺：在 $[-6, 21]$ 区间内精确平铺为纯小数或大整数（例如 `1e-6` $\to$ `"0.000001"`，`1e20` $\to$ `"100000000000000000000"`）；
  * 科学计数法：在 $\le -6$ 或 $> 21$ 时使用小写 `e+` / `e-`（例如 `1e-7`、`1e+21`）；
  * 次正规数（Subnormals）：针对 ES 规范输出严格最短表示（如 `Number.MIN_VALUE` 输出 `"5e-324"`）；
  * 开箱完整支持 `Number.prototype.toFixed(digits)` 与 `Number.prototype.toPrecision(precision)`。

---

### 2. 主流 dtoa 算法深度对比与路线抉择

在调研与选型过程中，针对业界主流算法（Grisu3、Ryū、Dragonbox、Schubfach）的特性进行了详尽权衡：

| 算法 / 方案 | 核心原理 | 最短表示 (Shortest) 表现 | `toFixed` / `toPrecision` 支持 | 当前生态与限制 |
| :--- | :--- | :--- | :--- | :--- |
| **Schubfach**<br>*(Raffaello Giulietti)* | 基于抽屉原理 (Schubfachprinzip) 的乘法逼近，纯 64 位 `long` 寄存器运算 | **100% 保证最短表示，单趟确定性完成，零 BigNum 回退，15~25ns** | ❌ 仅支持最短表示 | **当前最短表示采用**。OpenJDK 19+ 官方标准库（`Double.toString`）原生算法。 |
| **Grisu3**<br>*(V8 double-conversion)* | 64 位 DIY-FP 定点运算 + 预计算表 | 约 99.5% 命中 15~25ns 极速路径；0.5% 边界无法确定最短需回退 BigNum | **完整支持**<br>(配套 `FixedDtoa` 与 `FastDtoaCounted`) | V8 / Nashorn / Node.js 工业标准，零外部依赖，100% 匹配 ECMA 规范。 |
| **Ryū**<br>*(Ulf Adams, PLDI 2018)* | 128 位定点运算 + 严格误差边界判定 | 100% 保证最短表示，单趟完成，零 BigNum 回退 | ❌ 核心算法仅支持最短表示 | 提供了官方 Java 实现 (`info.adams.ryu`)，但在 Java 中需手工拆解 32 位模拟 128 位，较为繁琐。 |
| **Ryū Printf**<br>*(Ulf Adams, 2020)* | 基于分段大查找表的定点直接抽取 | 用于 `%f` 与 `%e` 格式化 | **支持**<br>(免除 BigNum 回退，4x~24x 快于 libc) | **官方仅提供 C 原生实现**，需维护数十 KB 查找表，Java 生态暂无官方移植版本。 |
| **Dragonbox**<br>*(Junekey Jeon, 2020)* | 建立在 Schubfach 理论上的压缩乘法表 + 最小化 128 位乘法 | 当前 C++ 基准中最快的最短表示算法 | ❌ 仅支持最短表示 | C++20 `std::format` / `{fmt}` 采用，主要活跃于 C++ 领域。 |

#### 为什么不直接用纯 Ryū 或 Dragonbox？
1. **ECMAScript 格式化非纯输出**：ES 规范对数值转字符串有强制的平铺区间（$10^{-6}\sim 10^{21}$）与大小写规范，Ryū 与 Dragonbox 输出的是基数+指数或标准科学计数法，缺失格式化状态机。
2. **`toFixed` 需求不可或缺**：Web 前端与脚本环境高频调用 `toFixed(2)` 等定点截断截取操作，Ryū 核心算法与 Dragonbox 完全无法处理指定小数位截断舍入。
3. **实现完备度与依赖成本**：Nashorn `doubleconv` 在纯 Java 内部完整实现了全套规范（包含 128 位定点快速生成的 `FixedDtoa`），无本地 JNI 依赖与三方包污染。

---

### 3. 演进路线图 (Roadmap)

* [x] **Phase 1（工业级规范闭环）**
  * 采用基于 V8 / Nashorn 的 `doubleconv` 全套组件。
  * 配合安全整数缓存池与装箱原型链调用，确保 100% 通过 ECMA-262 测试套件，保证绝对正确性与完备性。
* [x] **Phase 2（Schubfach 最短路径 100% 单趟无回退升级）**
  * 将 `DoubleConversion.toShortestString` 底层升级为基于 Raffaello Giulietti 的 **Schubfach 抽屉原理算法**（`Schubfach.java`）。
  * 配合 `DtoaBuffer` 与原生 `Math.multiplyHigh` 指令级优化，实现 100% 确定性单趟产出，完全消除了 Grisu3 那 0.5% 边界值掉入 `BignumDtoa` 的微秒级长尾回退，且针对 ECMA-262 次正规数（如 `Number.MIN_VALUE` 严格最短表示 `5e-324`）进行了完美兼容。
* [ ] **Phase 3（超长定点截断极致优化）**
  * 若后续性能剖析显示超长精度（例如 `toFixed(50~100)`）成为关键瓶颈，参考 **Ryū Printf** 算法将 C 版本的定点大表抽取机制移植为 Java 版本，替代 `FixedDtoa` 超出位数时向 `BignumDtoa` 的回退。

