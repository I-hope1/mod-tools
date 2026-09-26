package hope.magic.js.runtime;

import java.lang.invoke.SwitchPoint;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置原型与核心全局单例的 SwitchPoint 保护器 (V8 Protector 架构)。
 * 1. 针对极高频且 99.999% 场景下绝不被篡改的内置对象（Array.prototype, String.prototype, Iterator 协议, 全局 Math/console/Object/Array），
 *    以零分支代价（Zero-Branch JIT Inlining）挂载快速路径与常量绑定；
 * 2. 一旦用户代码动态进行 Monkey-patching（修改或删除原型属性、重定义全局单例），保护器 SwitchPoint 原子失效，
 *    所有受保护的已编译 JIT 机器码瞬间去优化并回退到通用规范慢路径。
 */
public final class BuiltinProtector {

	// 1. Array.prototype 结构与原生方法保护器 (保护 push, pop, shift, slice 等原生方法未被重写)
	private static volatile SwitchPoint arrayProtoSwitchPoint = new SwitchPoint();

	// 2. 迭代器协议保护器 (保护 Array.prototype[Symbol.iterator] 与 String.prototype[Symbol.iterator] 未被重定义)
	private static volatile SwitchPoint iteratorSwitchPoint = new SwitchPoint();

	// 3. 全局核心单例槽位保护器 (Global Property Cells: 位掩码与 64 元素固定数组直接查表)
	private static final long            PROTECTED_GLOBAL_SLOT_MASK;
	private static final SwitchPoint[]   GLOBAL_SLOT_SWITCH_POINTS = new SwitchPoint[64];
	private static final Object[]        GLOBAL_CONSTANTS          = new Object[64];

	static {
		long mask = 0L;
		int[] slots = {
			JSContext.SLOT_MATH,
			JSContext.SLOT_CONSOLE,
			JSContext.SLOT_OBJECT,
			JSContext.SLOT_ARRAY,
			JSContext.SLOT_NUMBER,
			JSContext.SLOT_STRING,
			JSContext.SLOT_BOOLEAN,
			JSContext.SLOT_PROXY,
			JSContext.SLOT_REFLECT,
			JSContext.SLOT_SYMBOL,
			JSContext.SLOT_DATE,
			JSContext.SLOT_PROMISE,
			JSContext.SLOT_REGEXP,
			JSContext.SLOT_PRINT,
			JSContext.SLOT_JAVA,
			JSContext.SLOT_ERROR,
			JSContext.SLOT_TYPE_ERROR
		};
		for (int slot : slots) {
			if (slot >= 0 && slot < 64) {
				mask |= (1L << slot);
				GLOBAL_SLOT_SWITCH_POINTS[slot] = new SwitchPoint();
			}
		}
		PROTECTED_GLOBAL_SLOT_MASK = mask;
	}

	// 4. Array[Symbol.species] 协议保护器 (保护 Array[Symbol.species] 与 Array.prototype.constructor 未被重写)
	private static volatile SwitchPoint arraySpeciesSwitchPoint = new SwitchPoint();

	// 5. Promise[Symbol.species] 协议保护器 (保护 Promise[Symbol.species] 与 Promise.prototype.constructor 未被重载)
	private static volatile SwitchPoint promiseSpeciesSwitchPoint = new SwitchPoint();

	private BuiltinProtector() {}

	//region Array Protector

	public static SwitchPoint getArrayProtoSwitchPoint() {
		return arrayProtoSwitchPoint;
	}

	public static boolean isArrayProtoValid() {
		return !arrayProtoSwitchPoint.hasBeenInvalidated();
	}

	public static synchronized void invalidateArrayProtector() {
		SwitchPoint sp = arrayProtoSwitchPoint;
		if (sp != null && !sp.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
		}
	}

	//endregion
	//region Iterator Protector

	public static SwitchPoint getIteratorSwitchPoint() {
		return iteratorSwitchPoint;
	}

	public static boolean isIteratorValid() {
		return !iteratorSwitchPoint.hasBeenInvalidated();
	}

	public static synchronized void invalidateIteratorProtector() {
		SwitchPoint sp = iteratorSwitchPoint;
		if (sp != null && !sp.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
		}
	}

	//endregion
	//region Global Slot Protector (Property Cells)

	public static SwitchPoint getGlobalSlotSwitchPoint(int slot) {
		if (slot >= 0 && slot < 64) {
			SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS[slot];
			if (sp == null) {
				synchronized (BuiltinProtector.class) {
					sp = GLOBAL_SLOT_SWITCH_POINTS[slot];
					if (sp == null) {
						GLOBAL_SLOT_SWITCH_POINTS[slot] = sp = new SwitchPoint();
					}
				}
			}
			return sp;
		}
		return null;
	}

	public static boolean isGlobalSlotValid(int slot) {
		if (slot >= 0 && slot < 64 && ((PROTECTED_GLOBAL_SLOT_MASK & (1L << slot)) != 0L)) {
			SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS[slot];
			return sp != null && !sp.hasBeenInvalidated();
		}
		return false;
	}

	public static synchronized void invalidateGlobalSlot(int slot) {
		if (slot >= 0 && slot < 64) {
			SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS[slot];
			if (sp != null && !sp.hasBeenInvalidated()) {
				SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
			}
		}
	}

	public static boolean isProtectedGlobalSlot(int slot) {
		return (slot >= 0 && slot < 64) && ((PROTECTED_GLOBAL_SLOT_MASK & (1L << slot)) != 0L);
	}

	public static Object getGlobalConstant(int slot) {
		if (slot >= 0 && slot < 64) {
			Object c = GLOBAL_CONSTANTS[slot];
			if (c != null) return c;
			return resolveGlobalConstant(slot);
		}
		return null;
	}

	private static synchronized Object resolveGlobalConstant(int slot) {
		Object c = GLOBAL_CONSTANTS[slot];
		if (c != null) return c;
		if (slot == JSContext.SLOT_MATH) c = JSContext.LazyMath.MATH;
		else if (slot == JSContext.SLOT_CONSOLE) c = JSContext.LazyConsole.CONSOLE;
		else if (slot == JSContext.SLOT_OBJECT) c = JSContext.LazyObject.OBJECT;
		else if (slot == JSContext.SLOT_ARRAY) c = JSContext.LazyArray.ARRAY;
		else if (slot == JSContext.SLOT_NUMBER) c = JSContext.LazyPrimitiveConstructors.NUMBER;
		else if (slot == JSContext.SLOT_STRING) c = JSContext.LazyPrimitiveConstructors.STRING;
		else if (slot == JSContext.SLOT_BOOLEAN) c = JSContext.LazyPrimitiveConstructors.BOOLEAN;
		else if (slot == JSContext.SLOT_PROXY) c = JSContext.LazyProxy.PROXY;
		else if (slot == JSContext.SLOT_REFLECT) c = JSContext.LazyReflect.REFLECT;
		else if (slot == JSContext.SLOT_SYMBOL) c = JSContext.LazySymbol.SYMBOL;
		else if (slot == JSContext.SLOT_DATE) c = JSContext.LazyDate.DATE;
		else if (slot == JSContext.SLOT_PROMISE) c = JSContext.LazyBuiltins.PROMISE;
		else if (slot == JSContext.SLOT_REGEXP) c = JSContext.LazyMisc.REGEXP;
		else if (slot == JSContext.SLOT_PRINT) c = JSContext.LazyMisc.PRINT;
		else if (slot == JSContext.SLOT_JAVA) c = JSContext.LazyMisc.JAVA;
		else if (slot == JSContext.SLOT_ERROR) c = JSContext.LazyErrors.ERROR;
		else if (slot == JSContext.SLOT_TYPE_ERROR) c = JSContext.LazyErrors.TYPE_ERROR;

		if (c != null) {
			GLOBAL_CONSTANTS[slot] = c;
		}
		return c;
	}

	//region Species Protectors

	public static SwitchPoint getArraySpeciesSwitchPoint() {
		return arraySpeciesSwitchPoint;
	}

	public static boolean isArraySpeciesValid() {
		return !arraySpeciesSwitchPoint.hasBeenInvalidated();
	}

	public static synchronized void invalidateArraySpeciesProtector() {
		SwitchPoint sp = arraySpeciesSwitchPoint;
		if (sp != null && !sp.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
		}
	}

	public static SwitchPoint getPromiseSpeciesSwitchPoint() {
		return promiseSpeciesSwitchPoint;
	}

	public static boolean isPromiseSpeciesValid() {
		return !promiseSpeciesSwitchPoint.hasBeenInvalidated();
	}

	public static synchronized void invalidatePromiseSpeciesProtector() {
		SwitchPoint sp = promiseSpeciesSwitchPoint;
		if (sp != null && !sp.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
		}
	}

	//endregion

	public static synchronized void resetAll() {
		java.util.List<SwitchPoint> toInvalidate = new java.util.ArrayList<>();
		for (int i = 0; i < 64; i++) {
			SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS[i];
			if (sp != null) {
				if (!sp.hasBeenInvalidated()) toInvalidate.add(sp);
				GLOBAL_SLOT_SWITCH_POINTS[i] = new SwitchPoint();
			}
		}
		if (!toInvalidate.isEmpty()) {
			SwitchPoint.invalidateAll(toInvalidate.toArray(new SwitchPoint[0]));
		}

		SwitchPoint spArr = arrayProtoSwitchPoint;
		if (spArr != null && !spArr.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ spArr });
		}
		arrayProtoSwitchPoint = new SwitchPoint();

		SwitchPoint spIter = iteratorSwitchPoint;
		if (spIter != null && !spIter.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ spIter });
		}
		iteratorSwitchPoint = new SwitchPoint();

		SwitchPoint spSpecies = arraySpeciesSwitchPoint;
		if (spSpecies != null && !spSpecies.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ spSpecies });
		}
		arraySpeciesSwitchPoint = new SwitchPoint();

		SwitchPoint spPromise = promiseSpeciesSwitchPoint;
		if (spPromise != null && !spPromise.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ spPromise });
		}
		promiseSpeciesSwitchPoint = new SwitchPoint();
	}
}
