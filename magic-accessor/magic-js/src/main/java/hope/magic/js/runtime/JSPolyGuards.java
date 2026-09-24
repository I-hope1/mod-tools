package hope.magic.js.runtime;

import hope.magic.js.runtime.JSLinker.PolySnapshot;
import hope.magic.runtime.Magic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;

import static hope.magic.js.runtime.JSLinker.MH_IS_EXACT_SHAPE;
import static hope.magic.js.runtime.SlotMH.*;

/**
 * 负责多态 Inline Cache (PIC) 的跳转表 (tableSwitch)、多 Shape 守卫以及扁平 Switch 守卫构建。
 */
public final class JSPolyGuards {
	private static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	/** JDK 17+ 是否可用 MethodHandles.tableSwitch（反射探测，类加载时确定）。 */
	public static final boolean SUPPORTS_TABLE_SWITCH;
	private static final Method MTH_TABLE_SWITCH;

	static {
		boolean ok = false;
		Method  m  = null;
		try {
			m = MethodHandles.class.getMethod("tableSwitch", MethodHandle.class, MethodHandle[].class);
			ok = true;
		} catch (NoSuchMethodException ignored) { }
		SUPPORTS_TABLE_SWITCH = ok;
		MTH_TABLE_SWITCH = m;
	}

	/**
	 * 统一 tableSwitch 构造分发（兼容直接调用与反射调用）。
	 * @param defaultCase 当 selector 不在 [0, targets.length) 区间时的降级 Handle（首参数必须为 int）
	 * @param targets     各 index 对应的目标 Handle 数组（首参数必须为 int）
	 * @return 签名为 {@code (int selector, TrailingArgs...) -> ReturnType} 的 switch Handle
	 */
	public static MethodHandle invokeTableSwitch(MethodHandle defaultCase, MethodHandle[] targets) throws Throwable {
		if (MTH_TABLE_SWITCH != null) {
			return (MethodHandle) MTH_TABLE_SWITCH.invoke(null, new Object[]{defaultCase, targets});
		}
		throw new UnsupportedOperationException("MethodHandles.tableSwitch is not supported on current JVM");
	}

	/**
	 * 构建异槽多态扁平 Switch 守卫（Object getter 版）。
	 */
	public static MethodHandle buildFlatPolySwitchObject(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int off = offsets[i];
				MethodHandle fastGetter = off < 8
				 ? MH_GET_SLOT_OBJECT[off]
				 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
				MethodHandle exactTest = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchObject(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		// 路线 A：JDK 17+ 原生硬件跳转表
		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE_AS_OBJ[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchObjectLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（double getter 版）。签名 {@code (Object) -> double}。 */
	public static MethodHandle buildFlatPolySwitchDouble(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int off = offsets[i];
				MethodHandle fastGetter = off < 8
				 ? MH_GET_SLOT_DOUBLE[off]
				 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
				MethodHandle exactTest = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码
		MethodHandle maskChain = tryBuildOffsetMaskDispatchDouble(shapes, offsets, types, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchDoubleLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（int getter 版）。签名 {@code (Object) -> int}。 */
	public static MethodHandle buildFlatPolySwitchInt(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		MethodHandle maskChain = tryBuildOffsetMaskDispatchInt(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchIntLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（long getter 版）。签名 {@code (Object) -> long}。 */
	public static MethodHandle buildFlatPolySwitchLong(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		MethodHandle maskChain = tryBuildOffsetMaskDispatchLong(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchLongLinear(shapes, offsets, n, fallback);
	}

	private static int[] collectDistinctOffsets(int[] offsets, int n) {
		int[] distinctOffsets = new int[8];
		int   count           = 0;
		for (int i = 0; i < n; i++) {
			int     off   = offsets[i];
			boolean found = false;
			for (int j = 0; j < count; j++) {
				if (distinctOffsets[j] == off) {
					found = true;
					break;
				}
			}
			if (!found) {
				if (count >= 8) return null;
				distinctOffsets[count++] = off;
			}
		}
		return Arrays.copyOf(distinctOffsets, count);
	}

	private static MethodHandle tryBuildOffsetMaskDispatchDouble(JSShape[] shapes, int[] offsets, byte[] types, int n,
	                                                             int propId,
	                                                             MethodHandle fallback) {
		if (propId < 0) return null;
		for (int i = 0; i < n; i++) {
			if ((types[i] & JSShape.TYPE_MASK) != JSShape.TYPE_DOUBLE) {
				return null;
			}
		}
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int off = distinctOffsets[i];
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_DOUBLE[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchObject(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                             MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int off = distinctOffsets[i];
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_OBJECT[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchInt(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                          MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int          off        = distinctOffsets[i];
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
			MethodHandle test       = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchLong(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                           MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int          off        = distinctOffsets[i];
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
			MethodHandle test       = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	/** 构建异槽多态扁平 Switch 守卫（Object setter 版）。签名 {@code (Object, Object) -> void}。 */
	public static MethodHandle buildFlatPolySwitchSetterObject(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;

		if (!SUPPORTS_TABLE_SWITCH || n == 0) return buildFlatPolySwitchSetterObjectLinear(shapes, offsets, n, fallback);

		try {
			int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
			for (JSShape s : shapes) {
				minId = Math.min(minId, s.id);
				maxId = Math.max(maxId, s.id);
			}
			int span = maxId - minId + 1;

			if (span <= n * 4 && span <= 64) {
				MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

				MethodHandle[] targets = new MethodHandle[span];
				Arrays.fill(targets, fallbackWithSel);

				for (int i = 0; i < n; i++) {
					int idx = shapes[i].id - minId;
					int off = offsets[i];
					MethodHandle fastSetter = off < 8
					 ? MH_SET_SLOT_OBJECT[off]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, off);

					targets[idx] = MethodHandles.dropArguments(fastSetter.asType(fallback.type()), 0, int.class);
				}

				MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
				MethodHandle selector = buildShapeIdSelector(minId, span);

				return MethodHandles.foldArguments(ts, selector);
			}
		} catch (Throwable ignored) { }

		return buildFlatPolySwitchSetterObjectLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（double setter 版）。签名 {@code (Object, double) -> void}。 */
	public static MethodHandle buildFlatPolySwitchSetterDouble(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;

		if (!SUPPORTS_TABLE_SWITCH || n == 0) return buildFlatPolySwitchSetterDoubleLinear(shapes, offsets, n, fallback);

		try {
			int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
			for (JSShape s : shapes) {
				minId = Math.min(minId, s.id);
				maxId = Math.max(maxId, s.id);
			}
			int span = maxId - minId + 1;

			if (span <= n * 4 && span <= 64) {
				MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

				MethodHandle[] targets = new MethodHandle[span];
				Arrays.fill(targets, fallbackWithSel);

				for (int i = 0; i < n; i++) {
					int idx = shapes[i].id - minId;
					int off = offsets[i];
					MethodHandle fastSetter = off < 8
					 ? MH_SET_SLOT_DOUBLE[off]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, off);

					targets[idx] = MethodHandles.dropArguments(fastSetter.asType(fallback.type()), 0, int.class);
				}

				MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
				MethodHandle selector = buildShapeIdSelector(minId, span);

				return MethodHandles.foldArguments(ts, selector);
			}
		} catch (Throwable ignored) { }

		return buildFlatPolySwitchSetterDoubleLinear(shapes, offsets, n, fallback);
	}

	public static MethodHandle buildShapeIdSelector(int minId, int span) {
		return MethodHandles.insertArguments(
		 JSLinker.findStaticMH(JSPolyGuards.class, "shapeIdSelector", MethodType.methodType(int.class, int.class, int.class, Object.class)),
		 0, minId, span
		);
	}

	public static int shapeIdSelector(int minId, int span, Object target) {
		if (target instanceof JSObject jsObj) {
			int idx = jsObj.shape.id - minId;
			if (Integer.compareUnsigned(idx, span) < 0) return idx;
		}
		return -1;
	}

	public static Object polyGetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return jsObj.getSlot(offsets[i]);
			}
		}
		return fallback.invoke(target);
	}

	public static double polyGetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return jsObj.getDoubleSlot(offsets[i]);
			}
		}
		return (double) fallback.invoke(target);
	}

	public static int polyGetInt(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return JSOps.toInt(jsObj.getSlot(offsets[i]));
			}
		}
		return (int) fallback.invoke(target);
	}

	public static long polyGetLong(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return JSOps.toLong(jsObj.getSlot(offsets[i]));
			}
		}
		return (long) fallback.invoke(target);
	}

	public static MethodHandle buildFlatPolySwitchObjectLinear(JSShape[] shapes, int[] offsets, int n,
	                                                           MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polyGetObject",
			 MethodType.methodType(Object.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static MethodHandle buildFlatPolySwitchDoubleLinear(JSShape[] shapes, int[] offsets, int n,
	                                                           MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polyGetDouble",
			 MethodType.methodType(double.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static MethodHandle buildFlatPolySwitchIntLinear(JSShape[] shapes, int[] offsets, int n,
	                                                        MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polyGetInt",
			 MethodType.methodType(int.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static MethodHandle buildFlatPolySwitchLongLinear(JSShape[] shapes, int[] offsets, int n,
	                                                         MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polyGetLong",
			 MethodType.methodType(long.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static void polySetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, Object value)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) {
					jsObj.setSlot(offsets[i], value);
					return;
				}
			}
		}
		fallback.invoke(target, value);
	}

	public static void polySetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, double value)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) {
					jsObj.setDoubleSlot(offsets[i], value);
					return;
				}
			}
		}
		fallback.invoke(target, value);
	}

	public static MethodHandle buildFlatPolySwitchSetterObjectLinear(JSShape[] shapes, int[] offsets, int n,
	                                                                 MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polySetObject",
			 MethodType.methodType(void.class, JSShape[].class, int[].class, MethodHandle.class, Object.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static MethodHandle buildFlatPolySwitchSetterDoubleLinear(JSShape[] shapes, int[] offsets, int n,
	                                                                 MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSPolyGuards.class, "polySetDouble",
			 MethodType.methodType(void.class, JSShape[].class, int[].class, MethodHandle.class, Object.class, double.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	public static boolean isMatchPropAt(int propId, int offset, Object target) {
		return target instanceof JSObject jsObj && jsObj.shape.hasPropertyAt(propId, offset);
	}

	public static boolean isShapeN(JSShape[] shapes, Object target) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	public static MethodHandle buildMultiShapeGuard(JSShape[] shapes, int propId, int commonOff) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE.bindTo(shapes[0]);

		if (propId >= 0 && commonOff >= 0) {
			return MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, commonOff);
		}

		return JSLinker.findStaticMH(JSPolyGuards.class, "isShapeN", MethodType.methodType(boolean.class, JSShape[].class, Object.class)).bindTo(shapes);
	}

	public static boolean isShapeNSetterDouble(JSShape[] shapes, Object target, double val) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	public static MethodHandle buildMultiShapeGuardSetterDouble(JSShape[] shapes) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shapes[0]);
		return JSLinker.findStaticMH(JSPolyGuards.class, "isShapeNSetterDouble", MethodType.methodType(boolean.class, JSShape[].class, Object.class, double.class)).bindTo(shapes);
	}

	public static boolean isShapeNSetterObject(JSShape[] shapes, Object target, Object val) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	public static MethodHandle buildMultiShapeGuardSetterObject(JSShape[] shapes) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes[0]);
		return JSLinker.findStaticMH(JSPolyGuards.class, "isShapeNSetterObject", MethodType.methodType(boolean.class, JSShape[].class, Object.class, Object.class)).bindTo(shapes);
	}

	public static MethodHandle getAdaptiveFallback(ChainedCallSite site) {
		return (site.getPolyCount() < 64 && site.getInitialFallback() != null)
		 ? site.getInitialFallback()
		 : (site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget());
	}
}
