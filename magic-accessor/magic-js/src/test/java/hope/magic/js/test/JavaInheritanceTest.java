package hope.magic.js.test;

import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JavaClassExtender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class JavaInheritanceTest {

	public static abstract class AbstractWorker {
		public final String name;

		public AbstractWorker(String name) {
			this.name = name;
		}

		public abstract String doWork(int count);

		public String getInfo() {
			return "Worker:" + name;
		}
	}

	public static class Calculator {
		public int compute(int a) {
			return a * 2;
		}

		public String compute(String s) {
			return "str:" + s;
		}
	}

	@Test
	public void testES6ClassExtendArrayList() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
				"class MyList extends java.util.ArrayList {\n" +
				"    constructor(prefix) {\n" +
				"        super();\n" +
				"        this.prefix = prefix;\n" +
				"    }\n" +
				"\n" +
				"    add(val) {\n" +
				"        return super.add(this.prefix + ':' + val);\n" +
				"    }\n" +
				"}\n" +
				"\n" +
				"let list = new MyList('item');\n" +
				"list.add('apple');\n" +
				"list.add('banana');\n" +
				"list;\n"
		);

		Assertions.assertInstanceOf(ArrayList.class, res);
		@SuppressWarnings("unchecked")
		List<Object> list = (List<Object>) res;

		Assertions.assertEquals(2, list.size());
		Assertions.assertEquals("item:apple", list.get(0));
		Assertions.assertEquals("item:banana", list.get(1));

		// 从 Java 侧调用 add 也必须触发 JS 覆写逻辑
		list.add("cherry");
		Assertions.assertEquals(3, list.size());
		Assertions.assertEquals("item:cherry", list.get(2));

		// 测试未覆写的方法走掩码快速短路直通原生父类
		Assertions.assertFalse(list.isEmpty());
		Assertions.assertEquals(3, list.size());
	}

	@Test
	public void testJavaExtendAPI() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
				"let SubList = Java.extend(java.util.ArrayList, {\n" +
				"    add(val) {\n" +
				"        return this.__magic_super_add('ext:' + val);\n" +
				"    }\n" +
				"});\n" +
				"let list = new SubList();\n" +
				"list.add('hello');\n" +
				"list.add('world');\n" +
				"list;\n"
		);

		Assertions.assertInstanceOf(ArrayList.class, res);
		@SuppressWarnings("unchecked")
		List<Object> list = (List<Object>) res;

		Assertions.assertEquals(2, list.size());
		Assertions.assertEquals("ext:hello", list.get(0));
		Assertions.assertEquals("ext:world", list.get(1));
	}

	@Test
	public void testInterfaceImplementationRunnable() throws InterruptedException {
		JSContext cx = new JSContext();
		cx.eval(
				"executed = false;\n" +
				"threadName = '';\n" +
				"class MyTask extends java.lang.Runnable {\n" +
				"    run() {\n" +
				"        executed = true;\n" +
				"        threadName = java.lang.Thread.currentThread().getName();\n" +
				"    }\n" +
				"}\n" +
				"task = new MyTask();\n"
		);

		Object task = cx.get("task");
		Assertions.assertInstanceOf(Runnable.class, task);

		Thread t = new Thread((Runnable) task, "MagicJS-Worker-Thread");
		t.start();
		t.join(5000);

		Assertions.assertEquals(true, cx.get("executed"));
		Assertions.assertEquals("MagicJS-Worker-Thread", cx.get("threadName"));
	}

	@Test
	public void testAbstractClassInheritance() {
		JSContext cx = new JSContext();
		cx.set("AbstractWorker", AbstractWorker.class);

		Object res = cx.eval(
				"class ConcreteWorker extends AbstractWorker {\n" +
				"    constructor(name, suffix) {\n" +
				"        super(name);\n" +
				"        this.suffix = suffix;\n" +
				"    }\n" +
				"    doWork(count) {\n" +
				"        return this.name + ' did ' + count + ' ' + this.suffix;\n" +
				"    }\n" +
				"}\n" +
				"let worker = new ConcreteWorker('Alice', 'tasks');\n" +
				"worker;\n"
		);

		Assertions.assertInstanceOf(AbstractWorker.class, res);
		AbstractWorker worker = (AbstractWorker) res;

		// 检查继承的字段和原生方法
		Assertions.assertEquals("Alice", worker.name);
		Assertions.assertEquals("Worker:Alice", worker.getInfo());

		// 检查抽象方法由 JS 实现并返回规范化字符串
		String workResult = worker.doWork(5);
		Assertions.assertEquals("Alice did 5 tasks", workResult);
	}

	@Test
	public void testMetaspaceZeroExpansion() {
		JSContext cx = new JSContext();
		Class<?> firstSubClass = null;

		for (int i = 0; i < 10; i++) {
			Object res = cx.eval(
					"class DynamicList" + i + " extends java.util.ArrayList {\n" +
					"    add(x) { return super.add(x); }\n" +
					"}\n" +
					"new DynamicList" + i + "();\n"
			);
			Assertions.assertInstanceOf(ArrayList.class, res);
			if (firstSubClass == null) {
				firstSubClass = res.getClass();
			} else {
				// 核心断言：所有动态派生子类在 JVM 层面必须复用 100% 同一个生成的 Class
				Assertions.assertSame(firstSubClass, res.getClass());
			}
		}

		JavaClassExtender.ClassInfo info = JavaClassExtender.getClassInfo(ArrayList.class);
		Assertions.assertSame(firstSubClass, info.subClass);
	}

	@Test
	public void testOverloadedMethodsAndCoercion() {
		JSContext cx = new JSContext();
		cx.set("Calculator", Calculator.class);

		Object res = cx.eval(
				"class MyCalc extends Calculator {\n" +
				"    compute(arg) {\n" +
				"        if (typeof arg === 'number') {\n" +
				"            return super.compute(arg) + 10;\n" +
				"        }\n" +
				"        return super.compute(arg) + '!';\n" +
				"    }\n" +
				"}\n" +
				"new MyCalc();\n"
		);

		Assertions.assertInstanceOf(Calculator.class, res);
		Calculator calc = (Calculator) res;

		// 重载方法分发与基本类型 int / String 返回值强制转换
		Assertions.assertEquals(20, calc.compute(5)); // (5 * 2) + 10 = 20
		Assertions.assertEquals("str:hello!", calc.compute("hello"));
	}

	@Test
	public void testES6PureJSClassInheritance() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
				"class Animal {\n" +
				"    constructor(name) {\n" +
				"        this.name = name;\n" +
				"    }\n" +
				"    speak() {\n" +
				"        return this.name + ' makes a sound';\n" +
				"    }\n" +
				"}\n" +
				"class Dog extends Animal {\n" +
				"    constructor(name) {\n" +
				"        super(name);\n" +
				"    }\n" +
				"    speak() {\n" +
				"        return super.speak() + ', bark!';\n" +
				"    }\n" +
				"}\n" +
				"let d = new Dog('Rex');\n" +
				"d.speak();\n"
		);

		Assertions.assertEquals("Rex makes a sound, bark!", res);
	}

	@Test
	public void testES6MultiLevelPureJSInheritance() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
				"class Animal {\n" +
				"    constructor(name) {\n" +
				"        this.name = name;\n" +
				"    }\n" +
				"    speak() {\n" +
				"        return this.name + ':sound';\n" +
				"    }\n" +
				"}\n" +
				"class Dog extends Animal {\n" +
				"    constructor(name) {\n" +
				"        super(name);\n" +
				"    }\n" +
				"    speak() {\n" +
				"        return super.speak() + ':bark';\n" +
				"    }\n" +
				"}\n" +
				"class Puppy extends Dog {\n" +
				"    constructor(name) {\n" +
				"        super(name);\n" +
				"    }\n" +
				"    speak() {\n" +
				"        return super.speak() + ':yip';\n" +
				"    }\n" +
				"}\n" +
				"let p = new Puppy('Toby');\n" +
				"p.speak();\n"
		);

		Assertions.assertEquals("Toby:sound:bark:yip", res);
	}
}
