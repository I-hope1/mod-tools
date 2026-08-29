# Magic Accessor

基于 **Javac 编译期 AST 重写 + ASM 字节码生成 + JVM MagicAccessorImpl 特权** 实现的高性能私有成员访问工具库。

可以在无常规反射性能损耗的前提下，直接读取/修改私有/受保护字段（Getter / Setter）以及调用私有/受保护方法。

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
    compileOnly "com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0"
    implementation "com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0"

    // 2. 编译期注解处理器 (APT)
    annotationProcessor "com.github.I-hope1.mod-tools:magic-accessor-compiler:v1.0.0"
}
```

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    compileOnly("com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0")
    implementation("com.github.I-hope1.mod-tools:magic-accessor-annotations:v1.0.0")
    annotationProcessor("com.github.I-hope1.mod-tools:magic-accessor-compiler:v1.0.0")
}
```

> **注意**：如果是在 JDK 17+ 编译环境，需在 `build.gradle` 中为 `JavaCompile` 任务添加 Javac 内部模块导出参数：
```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.addAll([
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.reflect=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.module=ALL-UNNAMED",
    ])
}
```

---

## 使用指南

### 1. 目标类（包含私有成员）

```java
package com.example;

public class User {
    private int id = 1001;
    private String name = "Alice";

    private void printSecret(String prefix) {
        System.out.println(prefix + " -> " + name);
    }

    private static String staticHelper(String str) {
        return "Processed: " + str;
    }
}
```

### 2. 定义访问器接口/类

在方法上使用 `@HField` 或 `@HMethod`，并在 Javadoc 中通过 `/** @see TargetClass#member */` 指向目标私有成员：

```java
package com.example;

import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;

@HMarkMagic
public class UserAccessor {

    /** @see User#id */
    @HField(isGetter = true)
    public static int getId(User user) { return 0; }

    /** @see User#id */
    @HField(isGetter = false)
    public static void setId(User user, int id) {}

    /** @see User#name */
    @HField(isGetter = true)
    public static String getName(User user) { return null; }

    /** @see User#name */
    @HField(isGetter = false)
    public static void setName(User user, String name) {}

    /** @see User#printSecret(String) */
    @HMethod
    public static void callPrintSecret(User user, String prefix) {}

    /** @see User#staticHelper(String) */
    @HMethod
    public static String callStaticHelper(String str) { return null; }
}
```

### 3. 运行期初始化并调用

在应用程序入口处调用一次 `Magic.install()`：

```java
package com.example;

import hope.magic.runtime.Magic;

public class Main {
    public static void main(String[] args) {
        // 1. 初始化 Magic 运行时（加载特权基类）
        Magic.install();

        User user = new User();

        // 2. 读取私有字段
        System.out.println(UserAccessor.getName(user)); // Alice

        // 3. 修改私有字段
        UserAccessor.setName(user, "Bob");
        System.out.println(UserAccessor.getName(user)); // Bob

        // 4. 调用私有方法
        UserAccessor.callPrintSecret(user, "DEBUG");    // DEBUG -> Bob

        // 5. 调用静态私有方法
        System.out.println(UserAccessor.callStaticHelper("test")); // Processed: test
    }
}
```

---

## 原理简介

1. **`@HMarkMagic`**：告知编译器该类需要使用 MagicAccessorImpl 体系。
2. **`@HField` / `@HMethod` + Javadoc `@see`**：APT 编译器自动解析目标类的成员类型和签名。
3. **编译期 ASM 生成**：编译器生成一个继承自 `MagicAccessorImpl` 的动态辅助类，利用直接字节码（`GETFIELD`, `PUTFIELD`, `INVOKEVIRTUAL`, `INVOKESPECIAL`, `INVOKESTATIC`）进行访问。
4. **AST 重写**：编译器自动将声明的方法体替换为对生成的辅助类静态方法的直接调用。
5. **运行期特权**：JVM 在执行 `MagicAccessorImpl` 子类的字节码指令时会跳过 Java 访问权限检查，从而达到接近原生代码的直接执行效率。
