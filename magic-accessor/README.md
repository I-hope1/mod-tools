# Magic Accessor

基于 **Javac 编译期 AST 重写 + ASM 字节码生成 + JVM 特权与底层机制** 实现的高性能私有成员访问工具库。

可以在无常规反射性能损耗的前提下，直接读取/修改私有/受保护字段（Getter / Setter）以及调用私有/受保护方法。

---

## 四大实现方案对比与手动配置

Magic Accessor 现已内置并保留了 **四种高性能方案**，可根据部署平台与性能需求灵活配置：

| 方案模式 (`AccessMode`) | 字段访问机制 | 方法调用机制 | 兼容环境 | 核心优势 |
| :--- | :--- | :--- | :--- | :--- |
| **`UNSAFE_AND_LINKTO`**<br>(专属原生桥接) | `Unsafe` 内存偏移量读写 | 编译期收集签名生成专属 `MagicBridge`<br>运行期注入 `java.lang.invoke` 调用原生 `linkTo*` | **HotSpot JVM**<br>(全 JDK 8 ~ 25+) | 零预热、零 LambdaForm 开销，直接执行 HotSpot JVM 原生 `linkToSpecial/Static` 指令 |
| **`UNSAFE_AND_INDY`**<br>(默认标准规范) | `Unsafe` 内存偏移量读写 | JVM 原生 `invokedynamic`<br>(绑定 `ConstantCallSite`) | **全平台**<br>(JDK 8 ~ 25+，包括 Android 8.0+) | 标准字节码规范，首次 BSM 引导解析，后续 JIT 深度内联机器码，零反射与预热损耗 |
| **`UNSAFE_AND_METHODHANDLE`**<br>(Android / 跨平台) | `Unsafe` 内存偏移量读写 | `MethodHandle.invokeExact` | **Android (ART)**<br>及所有标准 VM | 针对经典 Android 环境提供静态缓存直调适配 |
| **`MAGIC_ACCESSOR`**<br>(传统特权方案) | 继承 `MagicAccessorImpl`<br>发射 `GETFIELD/PUTFIELD` | 继承 `MagicAccessorImpl`<br>发射 `INVOKEVIRTUAL/SPECIAL` | **JDK &le; 21**<br>(JDK 22+ 移除) | 经典的 Magic 体系特权方案 |

### 手动配置方式

#### 1. 类级别配置
在访问器类上的 `@HMarkMagic` 注解中指定 `mode`：
```java
// 方式 A: 采用专属 MagicBridge 原生 linkTo 极速直调方案
@HMarkMagic(mode = AccessMode.UNSAFE_AND_LINKTO)
public class MyAccessor { ... }

// 方式 B: 采用 invokedynamic (indy) 标准直调方案
@HMarkMagic(mode = AccessMode.UNSAFE_AND_INDY)
public class MyAccessor { ... }

// 方式 C: 采用 Android (ART) / 跨平台兼容方案
@HMarkMagic(mode = AccessMode.UNSAFE_AND_METHODHANDLE)
public class MyAccessor { ... }

// 方式 D: 采用传统 MagicAccessorImpl 方案 (JDK <= 21)
@HMarkMagic(mode = AccessMode.MAGIC_ACCESSOR)
public class MyAccessor { ... }
```

#### 2. 方法/字段级别局部覆盖配置
可以在单个 `@HField` 或 `@HMethod` 上单独覆盖设置：
```java
@HMarkMagic
public class MyAccessor {

    /** @see Target#field */
    @HField(isGetter = true, mode = AccessMode.UNSAFE_AND_LINKTO)
    public static int getField(Target t) { return 0; }

    /** @see Target#method */
    @HMethod(mode = AccessMode.UNSAFE_AND_METHODHANDLE)
    public static void callMethod(Target t) {}
}
```

---

## 快速引入 (JitPack)

### 1. 配置仓库

**Gradle (settings.gradle 或 build.gradle):**
```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

### 2. 添加依赖

**Gradle (Groovy DSL):**
```groovy
dependencies {
    // 1. 注解与运行时基础支持
    implementation "com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0"

    // 2. 编译期注解处理器 (APT)
    annotationProcessor "com.github.I-hope1.mod-tools:magic-accessor-compiler:v1.0.0"
}
```

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0")
    annotationProcessor("com.github.I-hope1.mod-tools:magic-accessor-compiler:v1.0.0")
}
```

---

## 使用指南

### 1. 目标类（包含私有成员）

```java
package com.example;

public class TargetObject {
    private int secretCode = 12345;
    private String message = "Hello, Private Field!";

    private int multiply(int a, int b) {
        return a * b;
    }

    private static String staticPrivateGreet(String name) {
        return "Greetings, " + name;
    }
}
```

### 2. 定义访问器接口/类

在方法上使用 `@HField` 或 `@HMethod`，并在 Javadoc 中通过 `/** @see TargetClass#member */` 指向目标私有成员：

```java
package com.example;

import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

@HMarkMagic(mode = AccessMode.UNSAFE_AND_LINKTO) // 可选 UNSAFE_AND_LINKTO, UNSAFE_AND_METHODHANDLE, MAGIC_ACCESSOR
public class TargetAccessor {

    /** @see TargetObject#secretCode */
    @HField(isGetter = true)
    public static int getSecretCode(TargetObject target) { return 0; }

    /** @see TargetObject#secretCode */
    @HField(isGetter = false)
    public static void setSecretCode(TargetObject target, int value) {}

    /** @see TargetObject#message */
    @HField(isGetter = true)
    public static String getMessage(TargetObject target) { return null; }

    /** @see TargetObject#message */
    @HField(isGetter = false)
    public static void setMessage(TargetObject target, String value) {}

    /** @see TargetObject#multiply(int, int) */
    @HMethod
    public static int callMultiply(TargetObject target, int a, int b) { return 0; }

    /** @see TargetObject#staticPrivateGreet(String) */
    @HMethod
    public static String callStaticPrivateGreet(String name) { return null; }
}
```

### 3. 直接调用 (开箱即用，零配置)

在现代模式（`UNSAFE_AND_LINKTO`、`UNSAFE_AND_INDY`、`UNSAFE_AND_METHODHANDLE`）下，**无需手动调用任何初始化方法**，直接调用访问器即可（桥接类会在类初次加载时由 `<clinit>` 自动完成注册与注入）：

```java
package com.example;

public class Main {
    public static void main(String[] args) {
        TargetObject target = new TargetObject();

        // 1. 读取私有字段（零反射性能损耗）
        int code = TargetAccessor.getSecretCode(target); // 12345
        String msg = TargetAccessor.getMessage(target);

        // 2. 修改私有字段
        TargetAccessor.setSecretCode(target, 99999);
        TargetAccessor.setMessage(target, "Modified value!");

        // 3. 调用私有方法
        int result = TargetAccessor.callMultiply(target, 6, 7); // 42
        String greet = TargetAccessor.callStaticPrivateGreet("Developer");
    }
}
```

> **注**：仅在使用传统 `MAGIC_ACCESSOR` 方案 (JDK &le; 21) 时，才需要在程序入口处显式调用一次 `Magic.install()` 来安装特权基础类。

