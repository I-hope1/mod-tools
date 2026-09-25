package hope.magic.runtime;

import jdk.internal.vm.annotation.*;

/**
 * Bootstrap 级别统一方法与构造器调用分发抽象类。
 * <p>继承此类以实现虚方法调用（{@code invokevirtual}，O(1) vtable 寻址），避免接口调用（{@code invokeinterface}）在多态下的 itable 查找损耗。</p>
 * <p>同时统一兼任构造器（{@code newInstance}）调用，构造器方法默认转发至 {@code target = null} 的 invoke 通道。</p>
 */
public abstract class MagicInvoker {
	public static final Object[] EMPTY_ARGS = new Object[0];

	protected MagicInvoker() {
	}

	public abstract Object invoke(Object target, Object[] args) throws Throwable;

	@Hidden
	@ForceInline
	public Object invoke0(Object target) throws Throwable {
		return invoke(target, EMPTY_ARGS);
	}

	@Hidden
	@ForceInline
	public Object invoke1(Object target, Object a0) throws Throwable {
		return invoke(target, new Object[]{a0});
	}

	@Hidden
	@ForceInline
	public Object invoke2(Object target, Object a0, Object a1) throws Throwable {
		return invoke(target, new Object[]{a0, a1});
	}

	@Hidden
	@ForceInline
	public Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
		return invoke(target, new Object[]{a0, a1, a2});
	}

	// --- 构造器别名快捷入口 (直接委托给 target = null 的调用) ---

	@Hidden
	@ForceInline
	public Object newInstance(Object[] args) throws Throwable {
		return invoke(null, args);
	}

	@Hidden
	@ForceInline
	public Object newInstance0() throws Throwable {
		return invoke0(null);
	}

	@Hidden
	@ForceInline
	public Object newInstance1(Object a0) throws Throwable {
		return invoke1(null, a0);
	}

	@Hidden
	@ForceInline
	public Object newInstance2(Object a0, Object a1) throws Throwable {
		return invoke2(null, a0, a1);
	}

	@Hidden
	@ForceInline
	public Object newInstance3(Object a0, Object a1, Object a2) throws Throwable {
		return invoke3(null, a0, a1, a2);
	}

	// --- Primitive Fast-Path (Zero-Boxing Direct Call) ---

	@Hidden
	@ForceInline
	public int invokeInt0(Object target) throws Throwable {
		return ((Number) invoke0(target)).intValue();
	}

	@Hidden
	@ForceInline
	public int invokeInt1(Object target, int a0) throws Throwable {
		return ((Number) invoke1(target, a0)).intValue();
	}

	@Hidden
	@ForceInline
	public int invokeInt2(Object target, int a0, int a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).intValue();
	}

	@Hidden
	@ForceInline
	public int invokeInt3(Object target, int a0, int a1, int a2) throws Throwable {
		return ((Number) invoke3(target, a0, a1, a2)).intValue();
	}

	@Hidden
	@ForceInline
	public boolean invokeBoolean0(Object target) throws Throwable {
		Object res = invoke0(target);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}

	@Hidden
	@ForceInline
	public boolean invokeBoolean1(Object target, Object a0) throws Throwable {
		Object res = invoke1(target, a0);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}

	@Hidden
	@ForceInline
	public boolean invokeBoolean2(Object target, Object a0, Object a1) throws Throwable {
		Object res = invoke2(target, a0, a1);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}

	@Hidden
	@ForceInline
	public double invokeDouble0(Object target) throws Throwable {
		return ((Number) invoke0(target)).doubleValue();
	}

	@Hidden
	@ForceInline
	public double invokeDouble1(Object target, double a0) throws Throwable {
		return ((Number) invoke1(target, a0)).doubleValue();
	}

	@Hidden
	@ForceInline
	public double invokeDouble2(Object target, double a0, double a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).doubleValue();
	}

	@Hidden
	@ForceInline
	public double invokeDouble3(Object target, double a0, double a1, double a2) throws Throwable {
		return ((Number) invoke3(target, a0, a1, a2)).doubleValue();
	}

	@Hidden
	@ForceInline
	public long invokeLong0(Object target) throws Throwable {
		return ((Number) invoke0(target)).longValue();
	}

	@Hidden
	@ForceInline
	public long invokeLong1(Object target, long a0) throws Throwable {
		return ((Number) invoke1(target, a0)).longValue();
	}

	@Hidden
	@ForceInline
	public long invokeLong2(Object target, long a0, long a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).longValue();
	}
}
