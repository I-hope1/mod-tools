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

		public String compute(Object s) {
			return "str:" + s;
		}
	}

	@Test
	public void testES6ClassExtendArrayList() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class MyList extends java.util.ArrayList {
			    constructor(prefix) {
			        super();
			        this.prefix = prefix;
			    }
			
			    add(val) {
			        return super.add(this.prefix + ':' + val);
			    }
			}
			
			let list = new MyList('item');
			list.add('apple');
			list.add('banana');
			list;
			"""
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
		 """
			let SubList = Java.extend(java.util.ArrayList, {
			    add(val) {
			        return this.__magic_super_add('ext:' + val);
			    }
			});
			let list = new SubList();
			list.add('hello');
			list.add('world');
			list;
			"""
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
		 """
			executed = false;
			threadName = '';
			class MyTask extends java.lang.Runnable {
			    run() {
			        executed = true;
			        threadName = java.lang.Thread.currentThread().getName();
			    }
			}
			task = new MyTask();
			"""
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
		 """
			class ConcreteWorker extends AbstractWorker {
			    constructor(name, suffix) {
			        super(name);
			        this.suffix = suffix;
			    }
			    doWork(count) {
			        return this.name + ' did ' + count + ' ' + this.suffix;
			    }
			}
			let worker = new ConcreteWorker('Alice', 'tasks');
			worker;
			"""
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
		JSContext cx            = new JSContext();
		Class<?>  firstSubClass = null;

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
		 """
			class MyCalc extends Calculator {
			    compute(arg) {
			        if (typeof arg === 'number') {
			            return super.compute(arg) + 10;
			        }
			        return super.compute(arg) + '!';
			    }
			}
			new MyCalc();
			"""
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
		 """
			class Animal {
			    constructor(name) {
			        this.name = name;
			    }
			    speak() {
			        return this.name + ' makes a sound';
			    }
			}
			class Dog extends Animal {
			    constructor(name) {
			        super(name);
			    }
			    speak() {
			        return super.speak() + ', bark!';
			    }
			}
			let d = new Dog('Rex');
			d.speak();
			"""
		);

		Assertions.assertEquals("Rex makes a sound, bark!", res);
	}

	@Test
	public void testES6MultiLevelPureJSInheritance() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class Animal {
			    constructor(name) {
			        this.name = name;
			    }
			    speak() {
			        return this.name + ':sound';
			    }
			}
			class Dog extends Animal {
			    constructor(name) {
			        super(name);
			    }
			    speak() {
			        return super.speak() + ':bark';
			    }
			}
			class Puppy extends Dog {
			    constructor(name) {
			        super(name);
			    }
			    speak() {
			        return super.speak() + ':yip';
			    }
			}
			let p = new Puppy('Toby');
			p.speak();
			"""
		);

		Assertions.assertEquals("Toby:sound:bark:yip", res);
	}

	@Test
	public void testES6MultiTierJavaSubclassing() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class BaseList extends java.util.ArrayList {
			    constructor() { super(); }
			    tag() { return 'base'; }
			}
			
			class AdvancedList extends BaseList {
			    tag() { return super.tag() + ' -> adv'; }
			}
			
			let list = new AdvancedList();
			list.add('test');
			let t = list.tag();
			[list, t];
			"""
		);

		Assertions.assertInstanceOf(hope.magic.js.runtime.JSArray.class, res);
		var arr = (hope.magic.js.runtime.JSArray) res;
		Object listObj = arr.getElement(0);
		Object tagObj = arr.getElement(1);

		// 验证最终生成的物理实例依然是原生 ArrayList
		Assertions.assertInstanceOf(ArrayList.class, listObj);
		@SuppressWarnings("unchecked")
		List<Object> list = (List<Object>) listObj;
		Assertions.assertEquals(1, list.size());
		Assertions.assertEquals("test", list.get(0));

		// 验证多层继承中的 super.method() 调度链
		Assertions.assertEquals("base -> adv", tagObj);
	}
}
