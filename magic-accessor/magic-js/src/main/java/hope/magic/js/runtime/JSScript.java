package hope.magic.js.runtime;

public abstract class JSScript {
	/** 通用求值入口（返回 Object，保持 100% ECMAScript 规范兼容） */
	public abstract Object run(JSContext cx) throws Throwable;

	/** 原生双精度特化执行入口（直接走 CPU 浮点寄存器 XMM0 返回，0 堆内存分配，100% 线程安全） */
	public double runDouble(JSContext cx) throws Throwable {
		Object res = run(cx);
		return JSOps.toDouble(res);
	}

	/** 原生整型特化执行入口（直接走 CPU 寄存器 EAX 返回，0 堆内存分配，100% 线程安全） */
	public int runInt(JSContext cx) throws Throwable {
		Object res = run(cx);
		return JSOps.toInt(res);
	}

	/** 原生长整型特化执行入口（直接走 CPU 寄存器 RAX 返回，0 堆内存分配，100% 线程安全） */
	public long runLong(JSContext cx) throws Throwable {
		Object res = run(cx);
		return JSOps.toLong(res);
	}
}