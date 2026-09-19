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

	// 3. 全局核心单例槽位保护器 (Global Property Cells)
	private static final ConcurrentHashMap<Integer, SwitchPoint> GLOBAL_SLOT_SWITCH_POINTS = new ConcurrentHashMap<>();

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
		return GLOBAL_SLOT_SWITCH_POINTS.computeIfAbsent(slot, k -> new SwitchPoint());
	}

	public static boolean isGlobalSlotValid(int slot) {
		if (!isProtectedGlobalSlot(slot)) return false;
		SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS.computeIfAbsent(slot, k -> new SwitchPoint());
		return !sp.hasBeenInvalidated();
	}

	public static synchronized void invalidateGlobalSlot(int slot) {
		SwitchPoint sp = GLOBAL_SLOT_SWITCH_POINTS.get(slot);
		if (sp != null && !sp.hasBeenInvalidated()) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{ sp });
		}
	}

	public static boolean isProtectedGlobalSlot(int slot) {
		return slot == JSContext.SLOT_MATH
		    || slot == JSContext.SLOT_CONSOLE
		    || slot == JSContext.SLOT_OBJECT
		    || slot == JSContext.SLOT_ARRAY
		    || slot == JSContext.SLOT_NUMBER
		    || slot == JSContext.SLOT_STRING
		    || slot == JSContext.SLOT_BOOLEAN
		    || slot == JSContext.SLOT_PROXY
		    || slot == JSContext.SLOT_REFLECT
		    || slot == JSContext.SLOT_SYMBOL
		    || slot == JSContext.SLOT_DATE
		    || slot == JSContext.SLOT_PROMISE
		    || slot == JSContext.SLOT_REGEXP
		    || slot == JSContext.SLOT_PRINT
		    || slot == JSContext.SLOT_JAVA
		    || slot == JSContext.SLOT_ERROR
		    || slot == JSContext.SLOT_TYPE_ERROR;
	}

	public static Object getGlobalConstant(int slot) {
		if (slot == JSContext.SLOT_MATH) return JSContext.LazyMath.MATH;
		if (slot == JSContext.SLOT_CONSOLE) return JSContext.LazyConsole.CONSOLE;
		if (slot == JSContext.SLOT_OBJECT) return JSContext.LazyObject.OBJECT;
		if (slot == JSContext.SLOT_ARRAY) return JSContext.LazyArray.ARRAY;
		if (slot == JSContext.SLOT_NUMBER) return JSContext.LazyPrimitiveConstructors.NUMBER;
		if (slot == JSContext.SLOT_STRING) return JSContext.LazyPrimitiveConstructors.STRING;
		if (slot == JSContext.SLOT_BOOLEAN) return JSContext.LazyPrimitiveConstructors.BOOLEAN;
		if (slot == JSContext.SLOT_PROXY) return JSContext.LazyProxy.PROXY;
		if (slot == JSContext.SLOT_REFLECT) return JSContext.LazyReflect.REFLECT;
		if (slot == JSContext.SLOT_SYMBOL) return JSContext.LazySymbol.SYMBOL;
		if (slot == JSContext.SLOT_DATE) return JSContext.LazyDate.DATE;
		if (slot == JSContext.SLOT_PROMISE) return JSContext.LazyBuiltins.PROMISE;
		if (slot == JSContext.SLOT_REGEXP) return JSContext.LazyMisc.REGEXP;
		if (slot == JSContext.SLOT_PRINT) return JSContext.LazyMisc.PRINT;
		if (slot == JSContext.SLOT_JAVA) return JSContext.LazyMisc.JAVA;
		if (slot == JSContext.SLOT_ERROR) return JSContext.LazyErrors.ERROR;
		if (slot == JSContext.SLOT_TYPE_ERROR) return JSContext.LazyErrors.TYPE_ERROR;
		return null;
	}

	//endregion

	public static synchronized void resetAll() {
		SwitchPoint[] sps = GLOBAL_SLOT_SWITCH_POINTS.values().toArray(new SwitchPoint[0]);
		GLOBAL_SLOT_SWITCH_POINTS.clear();
		if (sps.length > 0) {
			SwitchPoint.invalidateAll(sps);
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
	}
}
