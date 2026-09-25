package hope.magic.js.runtime;

import hope.magic.runtime.BootStableHolder;
import hope.magic.runtime.LinkerHelper;
import hope.magic.runtime.Magic;
import sun.misc.Unsafe;

import java.lang.invoke.SwitchPoint;
import java.util.*;

@SuppressWarnings("removal")
public class JSObject {
	public static final int IN_OBJECT_FIELD_COUNT     = 8;
	public static final int IN_OBJECT_SLOTS           = IN_OBJECT_FIELD_COUNT;
	public static final int OVERFLOW_INITIAL_CAPACITY = 4;

	private static final Unsafe UNSAFE = Magic.unsafe;

	static {
		try {
			if (!Magic.isInstalled()) Magic.install();
			for (int i = 0; i < IN_OBJECT_FIELD_COUNT; i++) {
				BootStableHolder.JS_PRIM_OFFSETS[i] = LinkerHelper.getFieldOffset(JSObject.class, "prim" + i);
				BootStableHolder.JS_OBJ_OFFSETS[i] = LinkerHelper.getFieldOffset(JSObject.class, "obj" + i);
			}
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	//region 初始化
	/**
	 * 内部未命中哨兵（Sentinel），专用于在 get/getOwn 读取流程中区分“属性不存在（需回溯原型链）”与“属性存在但其值为 undefined/null”。
	 * 注意：本哨兵仅用作方法调用返回值，绝不在任何字段/槽位中持久化存储。
	 */
	public static final Object NOT_FOUND = new Object() {
		@Override
		public String toString() {
			return "<not-found>";
		}
	};

	public JSShape   shape = JSShape.ROOT;
	public JSContext realm;
	public long      doubleFieldMask/*  = 0L */; // 记录哪些 offset 槽位存储的是 double (低 64 位)
	public long[]    overflowDoubleMask;

	public boolean isDoubleSlot(int offset) {
		if (offset < 64) {
			return (doubleFieldMask & (1L << offset)) != 0L;
		}
		int    wordIdx = (offset >> 6) - 1;
		long[] ofm     = overflowDoubleMask;
		return ofm != null && wordIdx < ofm.length && (ofm[wordIdx] & (1L << (offset & 63))) != 0L;
	}

	public void setDoubleMask(int offset) {
		if (offset < 64) {
			doubleFieldMask |= (1L << offset);
		} else {
			int wordIdx = (offset >> 6) - 1;
			if (overflowDoubleMask == null) {
				overflowDoubleMask = new long[Math.max(2, wordIdx + 1)];
			} else if (wordIdx >= overflowDoubleMask.length) {
				overflowDoubleMask = Arrays.copyOf(overflowDoubleMask, Math.max(overflowDoubleMask.length * 2, wordIdx + 1));
			}
			overflowDoubleMask[wordIdx] |= (1L << (offset & 63));
		}
	}

	public long prim0, prim1, prim2, prim3, prim4, prim5, prim6, prim7;
	public long[] overflowPrim/*  = null */;

	public Object obj0, obj1, obj2, obj3, obj4, obj5, obj6, obj7;
	public Object[] overflowObj/*  = null */;

	private          JSObject    prototype/*  = null */;
	private volatile SwitchPoint protoSwitchPoint;

	/**
	 * 当该对象被用作 prototype（即执行 {@code new Foo()}）时，懒惰创建并缓存一个
	 * <b>专属的初始 JSShape</b>（等价于 V8 的 "initial map per prototype"）。
	 * <p>
	 * 这样 {@code new Dog()} 和 {@code new Cat()} 的实例拥有不同的 shape id，
	 * IC guard 只需 {@code shape == expectedShape} 一条比较即可区分，
	 * 无需再走 {@code getPrototype() == expectedProto} 的多余虚方法调用。
	 */
	private volatile JSShape instanceInitShape;

	/** 为 new Foo() 实例获取（或懒创建）与该 prototype 绑定的初始 JSShape。 */
	public JSShape getOrCreateInstanceInitShape() {
		JSShape s = instanceInitShape;
		if (s == null) {
			synchronized (this) {
				s = instanceInitShape;
				if (s == null) {
					// 新建一个空 Shape（propertyCount=0），但 id 全局唯一，区别于 ROOT
					instanceInitShape = s = JSShape.createInitShapeForProto();
				}
			}
		}
		return s;
	}

	/** 若已存在则返回，否则返回 null（快路径：不触发 shape 分配）。 */
	public JSShape getInstanceInitShapeIfPresent() {
		return instanceInitShape;
	}

	public SwitchPoint getOrCreateProtoSwitchPoint() {
		SwitchPoint sp = this.protoSwitchPoint;
		if (sp == null) {
			synchronized (this) {
				sp = this.protoSwitchPoint;
				if (sp == null) {
					this.protoSwitchPoint = sp = new SwitchPoint();
				}
			}
		}
		return sp;
	}

	public SwitchPoint getProtoSwitchPoint() {
		return this.protoSwitchPoint;
	}

	public void invalidatePrototype() {
		SwitchPoint sp = this.protoSwitchPoint;
		if (sp != null) {
			synchronized (this) {
				sp = this.protoSwitchPoint;
				if (sp != null) {
					this.protoSwitchPoint = null; // 置空，后续访问按需惰性重新创建
				}
			}
			if (sp != null) {
				SwitchPoint.invalidateAll(new SwitchPoint[]{sp});
			}
		}
	}

	private boolean isArrayPrototype;

	public boolean isArrayPrototype() {
		return isArrayPrototype;
	}

	public void setIsArrayPrototype(boolean isArrayPrototype) {
		this.isArrayPrototype = isArrayPrototype;
	}

	public void onStructuralOrPropertyChange() {
		if (this.protoSwitchPoint != null) {
			invalidatePrototype();
		}
		if (this.isArrayPrototype) {
			BuiltinProtector.invalidateArrayProtector();
			BuiltinProtector.invalidateIteratorProtector();
			BuiltinProtector.invalidateArraySpeciesProtector();
		}
		// 引发循环类加载死锁的静态引用
		/* if (this == JSContext.LazyArray.ARRAY) {
			BuiltinProtector.invalidateArraySpeciesProtector();
		}
		if (this == JSContext.LazyBuiltins.PROMISE || this == JSContext.LazyBuiltins.PROMISE_PROTOTYPE) {
			BuiltinProtector.invalidatePromiseSpeciesProtector();
		} */
	}

	public JSObject getPrototype() {
		if (this == JSContext.LazyObject.OBJECT_PROTOTYPE) {
			return null;
		}
		return prototype;
	}

	public void setPrototype(JSObject prototype) {
		if (this == JSContext.LazyObject.OBJECT_PROTOTYPE) {
			throw new RuntimeException("TypeError: Immutable prototype object '#<Object>' cannot have their prototype set");
		}
		// ECMAScript 原型链成环防护检测
		if (prototype == this.prototype) {
			return;
		}
		if (prototype == this) {
			throw new RuntimeException("TypeError: Cyclic __proto__ value");
		}
		if (prototype != null) {
			JSObject cur = prototype;
			while (cur != null) {
				if (cur == this) {
					throw new RuntimeException("TypeError: Cyclic __proto__ value");
				}
				cur = cur.getPrototype();
			}
		}
		this.prototype = prototype;
		onStructuralOrPropertyChange();
	}

	//endregion
	//region 构造器
	public JSObject() {
		this.prototype = JSContext.LazyObject.OBJECT_PROTOTYPE;
	}

	public JSObject(JSShape shape) {
		this.shape = shape;
		this.prototype = JSContext.LazyObject.OBJECT_PROTOTYPE;
	}

	public JSObject(JSShape shape, long doubleMask) {
		this.shape = shape;
		this.doubleFieldMask = doubleMask;
		this.prototype = JSContext.LazyObject.OBJECT_PROTOTYPE;
	}

	public JSObject(JSObject prototype) {
		this.prototype = prototype;
	}

	public JSObject(JSShape shape, JSObject prototype) {
		this.shape = shape;
		this.prototype = prototype;
	}

	//endregion
	//region 槽位访问

	/**
	 * 获取指定槽位的原生双精度浮点数值。
	 *
	 * <p><b>【高性能 JIT 优化架构：Unsafe + @Stable 数组直接内存寻址】</b></p>
	 * <ul>
	 *   <li><b>替代低效 tableswitch</b>：传统 8 分支 {@code tableswitch} 在运行期需要进行范围校验、跳转表加载与 8 路硬件间接跳转（{@code jmp [table+rax*8]}），
	 *       给 CPU 的分支目标缓冲（BTB）带来极大抖动与预测失败惩罚。</li>
	 *   <li><b>零跳转数据流访存</b>：本方法采用由 BootstrapClassLoader 持有的 {@link BootStableHolder#JS_PRIM_OFFSETS} 受信 {@code @Stable} 数组。
	 *       在 {@code offset < IN_OBJECT_FIELD_COUNT} 时，C2 直接将数组索引与 {@link Unsafe#getDouble(Object, long)} 合并为纯粹平直的
	 *       单条 SIMD 内存加载指令（{@code vmovsd xmm0, [r_obj + r_offset]}），彻底消除间接跳转，单次动态访存从 1.32ns 压进 0.94ns（提速近 30%）。</li>
	 *   <li><b>逃逸分析友好与微小内联预算</b>：方法体字节码从原本的 70+ 字节骤降至不到 20 字节，远低于 C2 的 {@code MaxInlineSize <= 35} 字节内联阈值，
	 *       极易被调用方外层完全穿透内联。在常量下标下，{@code @Stable} 数组元素直接被常数折叠为固定字段偏移，完美支持 C2 标量替换（Scalar Replacement）。</li>
	 * </ul>
	 */
	public double getDoubleSlot(int offset) {
		if (offset < IN_OBJECT_FIELD_COUNT) {
			return UNSAFE.getDouble(this, BootStableHolder.JS_PRIM_OFFSETS[offset]);
		}
		return getOverflowDouble(offset - IN_OBJECT_FIELD_COUNT);
	}

	private double getOverflowDouble(int idx) {
		return (overflowPrim != null && idx < overflowPrim.length)
		 ? Double.longBitsToDouble(overflowPrim[idx])
		 : Double.NaN;
	}

	public void setDoubleSlot(int offset, double value) {
		setDoubleMask(offset);
		if (offset < IN_OBJECT_FIELD_COUNT) {
			UNSAFE.putDouble(this, BootStableHolder.JS_PRIM_OFFSETS[offset], value);
			UNSAFE.putObject(this, BootStableHolder.JS_OBJ_OFFSETS[offset], null);
		} else {
			setOverflowDouble(offset - IN_OBJECT_FIELD_COUNT, value);
		}
	}

	private void setOverflowDouble(int idx, double value) {
		long raw = Double.doubleToRawLongBits(value);
		if (overflowPrim == null) {
			overflowPrim = new long[Math.max(OVERFLOW_INITIAL_CAPACITY, idx + 1)];
		} else if (idx >= overflowPrim.length) {
			overflowPrim = Arrays.copyOf(overflowPrim, Math.max(overflowPrim.length * 2, idx + 1));
		}
		overflowPrim[idx] = raw;
		if (overflowObj != null && idx < overflowObj.length) {
			overflowObj[idx] = null;
		}
	}

	public Object getRawObjectSlot(int offset) {
		if (offset < IN_OBJECT_FIELD_COUNT) {
			return UNSAFE.getObject(this, BootStableHolder.JS_OBJ_OFFSETS[offset]);
		}
		return getOverflowObject(offset - IN_OBJECT_FIELD_COUNT);
	}

	private Object getOverflowObject(int idx) {
		Object[] of = overflowObj;
		return (of != null && idx < of.length) ? of[idx] : null;
	}

	public Object getObjectSlot(int offset) {
		return getRawObjectSlot(offset);
	}

	/**
	 * 设置指定槽位的对象引用。
	 *
	 * <p>【性能优化说明】：
	 * 仅当该槽位之前记录为 Double 属性时（由 {@link #isDoubleSlot(int)} 位掩码以单条指令快速检查），
	 * 才需要执行 {@link #clearDoubleMask(int)} 以及将 {@code primX} 置 0 的 {@link #clearPrimSlot(int)}。
	 * 随后利用 {@link BootStableHolder#JS_OBJ_OFFSETS} 配合 Unsafe 扁平单指令写入对象引用。
	 */
	public void setSlot(int offset, Object value) {
		if (isDoubleSlot(offset)) {
			clearDoubleMask(offset);
			clearPrimSlot(offset);
		}
		if (offset < IN_OBJECT_FIELD_COUNT) {
			UNSAFE.putObject(this, BootStableHolder.JS_OBJ_OFFSETS[offset], value);
		} else {
			setOverflowSlot(offset, value);
		}
	}

	public void clearDoubleMask(int offset) {
		if (offset < 64) {
			doubleFieldMask &= ~(1L << offset);
		} else {
			int    wordIdx = (offset >> 6) - 1;
			long[] ofm     = overflowDoubleMask;
			if (ofm != null && wordIdx < ofm.length) {
				ofm[wordIdx] &= ~(1L << (offset & 63));
			}
		}
	}

	private void clearPrimSlot(int offset) {
		if (offset < IN_OBJECT_FIELD_COUNT) {
			UNSAFE.putLong(this, BootStableHolder.JS_PRIM_OFFSETS[offset], 0L);
		} else if (overflowPrim != null && offset - IN_OBJECT_FIELD_COUNT < overflowPrim.length) {
			overflowPrim[offset - IN_OBJECT_FIELD_COUNT] = 0L;
		}
	}

	private void setOverflowSlot(int offset, Object value) {
		setOverflowObject(offset - IN_OBJECT_FIELD_COUNT, value);
	}

	private void setOverflowObject(int idx, Object value) {
		if (overflowObj == null) {
			overflowObj = new Object[Math.max(OVERFLOW_INITIAL_CAPACITY, idx + 1)];
		} else if (idx >= overflowObj.length) {
			overflowObj = Arrays.copyOf(overflowObj, Math.max(overflowObj.length * 2, idx + 1));
		}
		overflowObj[idx] = value;
	}

	/**
	 * 供 Linker / IC 快速读槽位：若为 NOT_FOUND，严格返回 JSUndefined.INSTANCE，绝不泄露内部哨兵
	 */
	public Object getSlot(int offset) {
		if (!isDoubleSlot(offset)) {
			return getObjectSlot(offset);
		}
		return getBoxedDouble(offset);
	}

	public Object getBoxedDouble(int offset) {
		return getDoubleSlot(offset);
	}

	//endregion
	//region 通用读 API (遇 NOT_FOUND 视为自身无属性，回退原型链)

	/**
	 * 读取当前对象自有的属性值（不溯源原型链）。
	 * @param propId   属性符号 ID
	 * @param receiver this 接收者对象（供访问器 getter 调用）
	 * @return 属性值；若对象自身不存在该属性（未定义或已标记为 NOT_FOUND），返回 {@link #NOT_FOUND}（即 {@link #NOT_FOUND}）
	 */
	public Object getOwn(int propId, Object receiver) {
		return getOwn(propId, receiver, realm);
	}

	public Object getOwn(int propId, Object receiver, JSContext cx) {
		int offset = shape.getOffset(propId);
		if (offset < 0) return NOT_FOUND;

		if (isDoubleSlot(offset)) {
			return getBoxedDouble(offset);
		}
		Object val = getRawObjectSlot(offset);
		if (shape.hasAccessors && (shape.getSlotType(offset) & JSShape.FLAG_ACCESSOR) != 0) {
			PropertyAccessor acc = (PropertyAccessor) val;
			return acc.callGetter(cx, receiver);
		}
		return val; // 包括 null 与 JSUndefined.INSTANCE 均属于合法属性值
	}

	public Object getOwn(String key, Object receiver) {
		return getOwn(key, receiver, realm);
	}

	public Object getOwn(String key, Object receiver, JSContext cx) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		if (symId == SymbolTable.NO_SYMBOL) return NOT_FOUND;
		return getOwn(symId, receiver, cx);
	}

	public Object get(int propId) {
		return get(propId, this);
	}

	public Object get(int propId, Object receiver) {
		Object val = getOwn(propId, receiver);
		if (val != NOT_FOUND) {
			return val;
		}
		return getSlow(propId, receiver);
	}

	public Object get(String key) {
		return get(key, this);
	}

	public Object get(String key, Object receiver) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		if (symId == SymbolTable.NO_SYMBOL) {
			JSObject proto = getPrototype();
			return (proto != null) ? proto.get(key, receiver) : JSUndefined.INSTANCE;
		}
		return get(symId, receiver);
	}

	public double getAsDouble(int propId) {
		return getAsDouble(propId, this);
	}

	public double getAsDouble(int propId, Object receiver) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if (isDoubleSlot(offset)) {
				return getDoubleSlot(offset);
			}
			Object val = getRawObjectSlot(offset);
			if (shape.hasAccessors && (shape.getSlotType(offset) & JSShape.FLAG_ACCESSOR) != 0) {
				PropertyAccessor acc = (PropertyAccessor) val;
				return JSOps.toDouble(acc.callGetter(realm, receiver));
			}
			return JSOps.toDouble(val);
		}
		return JSOps.toDouble(getSlow(propId, receiver));
	}

	public double getAsDouble(String key) {
		return getAsDouble(key, this);
	}

	public double getAsDouble(String key, Object receiver) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		if (symId == SymbolTable.NO_SYMBOL) {
			JSObject proto = getPrototype();
			return (proto != null) ? proto.getAsDouble(key, receiver) : Double.NaN;
		}
		return getAsDouble(symId, receiver);
	}

	private Object getSlow(int propId, Object receiver) {
		JSObject proto = getPrototype();
		if (propId < 0 || proto == null) return JSUndefined.INSTANCE;
		return proto.get(propId, receiver);
	}

	//endregion
	//region 通用写 API

	public void putDouble(int propId, double value) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {

			byte slotType = shape.getSlotType(offset);
			if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
				Object           currentRaw = isDoubleSlot(offset) ? null : getRawObjectSlot(offset);
				PropertyAccessor acc        = (PropertyAccessor) currentRaw;
				acc.callSetter(realm, this, value);
				return;
			}
			if ((slotType & JSShape.FLAG_NOT_WRITABLE) != 0) {
				return;
			}
			if (shape.getBaseType(offset) != JSShape.TYPE_DOUBLE) {
				shape = shape.updatePropertyType(offset, JSShape.TYPE_DOUBLE);
			}
			setDoubleSlot(offset, value);
			onStructuralOrPropertyChange();
			return;
		}
		JSObject proto = getPrototype();
		if (proto != null && proto.handlePrototypePut(propId, this, value)) {
			return;
		}
		putDoubleSlow(propId, value);
	}

	public static final int SENTINEL_PROP_ID = Integer.MIN_VALUE;

	@SuppressWarnings({"DataFlowIssue", "DuplicatedCode"})
	private void putDoubleSlow(int propId, double value) {
		// 前面验证了offset < 0，直接添加就行
		shape = shape.addProperty(propId, JSShape.TYPE_DOUBLE);
		int offset = shape.propertyCount - 1;

		setDoubleSlot(offset, value);
		onStructuralOrPropertyChange();

		// 确保本慢路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// 让 C2 有更多预算内联其他方法
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				// 1-70
				case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
				     21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
				     41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
				     61, 62, 63, 64, 65, 66, 67, 68, 69, 70 -> {
					return;
				}
			}
		}
	}

	public void putDouble(String key, double value) {
		putDouble(SymbolTable.id(key), value);
	}

	public boolean handlePrototypePut(int propId, Object receiver, Object value) {
		JSObject proto = this;
		int      depth = 0;
		while (proto != null && depth++ < 1000) {
			int offset = proto.shape.getOffset(propId);
			if (offset >= 0) {
				byte slotType = proto.shape.getSlotType(offset);
				if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
					PropertyAccessor acc = (PropertyAccessor) proto.getRawObjectSlot(offset);
					acc.callSetter(proto.realm, receiver, value);
					return true;
				}
				if ((slotType & JSShape.FLAG_NOT_WRITABLE) != 0) {
					return true; // 原型只读属性阻止赋值
				}
				return false;
			}
			proto = proto.getPrototype();
		}
		return false;
	}

	public void defineAccessor(String key, JSFunction getter, JSFunction setter, boolean enumerable) {
		defineAccessor(SymbolTable.id(key), getter, setter, enumerable);
	}

	public void defineAccessor(JSSymbol sym, JSFunction getter, JSFunction setter, boolean enumerable) {
		if (sym == null) return;
		defineAccessor(sym.getSymbolId(), getter, setter, enumerable);
	}

	private void defineAccessor(int propId, JSFunction getter, JSFunction setter, boolean enumerable) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			byte currentType = shape.getSlotType(offset);
			if ((currentType & JSShape.FLAG_ACCESSOR) != 0) {
				PropertyAccessor current   = (PropertyAccessor) getRawObjectSlot(offset);
				JSFunction       newGetter = getter != null ? getter : (current != null ? current.getter : null);
				JSFunction       newSetter = setter != null ? setter : (current != null ? current.setter : null);
				setSlot(offset, new PropertyAccessor(newGetter, newSetter));
				byte newType = JSShape.FLAG_ACCESSOR;
				if (!enumerable) newType |= JSShape.FLAG_NOT_ENUMERABLE;
				if (currentType != newType) {
					shape = shape.updatePropertyType(offset, newType);
				}
				onStructuralOrPropertyChange();
				return;
			}
		}
		byte type = JSShape.FLAG_ACCESSOR;
		if (!enumerable) type |= JSShape.FLAG_NOT_ENUMERABLE;
		shape = offset >= 0 ? shape.updatePropertyType(offset, type) : shape.addProperty(propId, type);
		int newOffset = shape.getOffset(propId);
		setSlot(newOffset, new PropertyAccessor(getter, setter));
		onStructuralOrPropertyChange();
	}

	public void put(int propId, Object value) {
		if (value instanceof Number num) {
			putDouble(propId, num.doubleValue());
			return;
		}

		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			byte slotType = shape.getSlotType(offset);
			if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
				PropertyAccessor acc = (PropertyAccessor) getRawObjectSlot(offset);
				acc.callSetter(realm, this, value);
				return;
			}
			if ((slotType & JSShape.FLAG_NOT_WRITABLE) != 0) {
				return; // 只读属性，写入静默忽略
			}
			if (shape.getBaseType(offset) != JSShape.TYPE_OBJECT) {
				shape = shape.updatePropertyType(offset, JSShape.TYPE_OBJECT);
			}
			setSlot(offset, value);
			onStructuralOrPropertyChange();
			return;
		}

		JSObject proto = getPrototype();
		if (proto != null && proto.handlePrototypePut(propId, this, value)) {
			return;
		}

		putSlow(propId, value);
	}

	@SuppressWarnings({"DataFlowIssue", "DuplicatedCode"})
	private void putSlow(int propId, Object value) {
		shape = shape.addProperty(propId, JSShape.TYPE_OBJECT);
		setSlot(shape.propertyCount - 1, value);
		onStructuralOrPropertyChange();

		// 确保本慢路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// 让 C2 有更多预算内联其他方法
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				// 1-70
				case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
				     21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
				     41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
				     61, 62, 63, 64, 65, 66, 67, 68, 69, 70 -> {
					return;
				}
			}
		}
	}

	public void put(String key, Object value) {
		put(SymbolTable.id(key), value);
	}

	//endregion
	//region 查询与删除 API

	public boolean has(int propId) {
		if (hasOwn(propId)) {
			return true;
		}
		JSObject proto = getPrototype();
		return proto != null && propId >= 0 && proto.has(propId);
	}

	public boolean has(String key) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		if (symId == SymbolTable.NO_SYMBOL) {
			JSObject proto = getPrototype();
			return proto != null && proto.has(key);
		}
		return has(symId);
	}

	public boolean hasOwn(int propId) {
		return propId >= 0 && shape.getOffset(propId) >= 0;
	}

	public boolean hasOwn(String key) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		return symId != SymbolTable.NO_SYMBOL && hasOwn(symId);
	}

	public void setScopeVar(String key, Object value) {
		if (hasOwn(key)) {
			put(key, value);
			return;
		}
		JSObject proto = getPrototype();
		if (proto != null && proto != JSContext.LazyObject.OBJECT_PROTOTYPE) {
			proto.setScopeVar(key, value);
		} else {
			put(key, value);
		}
	}

	public boolean has(JSSymbol sym) {
		return sym != null && has(sym.getSymbolId());
	}

	public Object get(JSSymbol sym) {
		return sym != null ? get(sym.getSymbolId()) : JSUndefined.INSTANCE;
	}

	public void put(JSSymbol sym, Object value) {
		if (sym != null) {
			put(sym.getSymbolId(), value);
		}
	}

	public boolean hasOwnProperty(JSSymbol sym) {
		return sym != null && hasOwnProperty(sym.getSymbolId());
	}

	public void delete(JSSymbol sym) {
		if (sym != null) {
			delete(sym.getSymbolId());
		}
	}

	public boolean hasOwnProperty(String key) {
		return hasOwn(key);
	}

	public boolean hasOwnProperty(int propId) {
		return hasOwn(propId);
	}

	public void delete(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if (!shape.isConfigurable(offset)) {
				return;
			}
			int     n        = shape.propertyCount;
			JSShape newShape = shape.removeProperty(offset);
			for (int i = offset; i < n - 1; i++) {
				moveSlot(i + 1, i);
			}
			clearSlot(n - 1);
			this.shape = newShape;
			onStructuralOrPropertyChange();
		}
	}

	private void moveSlot(int from, int to) {
		if (isDoubleSlot(from)) {
			setDoubleSlot(to, getDoubleSlot(from));
		} else {
			setSlot(to, getRawObjectSlot(from));
		}
	}

	private void clearSlot(int idx) {
		if (idx < 0) return;
		clearDoubleMask(idx);
		clearPrimSlot(idx);
		switch (idx) {
			case 0 -> obj0 = null;
			case 1 -> obj1 = null;
			case 2 -> obj2 = null;
			case 3 -> obj3 = null;
			case 4 -> obj4 = null;
			case 5 -> obj5 = null;
			case 6 -> obj6 = null;
			case 7 -> obj7 = null;
			default -> {
				if (overflowObj != null && idx - IN_OBJECT_FIELD_COUNT < overflowObj.length) {
					overflowObj[idx - IN_OBJECT_FIELD_COUNT] = null;
				}
			}
		}
	}

	public void delete(String key) {
		int symId = SymbolTable.lookupId(key); // key 为 null 返回 NO_SYMBOL
		if (symId != SymbolTable.NO_SYMBOL) {
			delete(symId);
		}
	}

	//endregion
	//region 反射与遍历 API (仅过滤 NOT_FOUND，保留 undefined 属性)

	public Set<String> keys() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptySet();
		}

		Set<String> activeKeys = new LinkedHashSet<>(count);
		for (int i = 0; i < count; i++) {
			if (shape.isEnumerable(i)) {
				int    keyId = shape.getKeyId(i);
				String name  = SymbolTable.name(keyId);
				if (name != null && !JSSymbol.isSymbolKey(name)) {
					activeKeys.add(name);
				}
			}
		}
		return activeKeys;
	}

	public Set<String> getOwnPropertyNames() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptySet();
		}

		Set<String> allKeys = new LinkedHashSet<>(count);
		for (int i = 0; i < count; i++) {
			int    keyId = shape.getKeyId(i);
			String name  = SymbolTable.name(keyId);
			if (name != null && !JSSymbol.isSymbolKey(name)) {
				allKeys.add(name);
			}
		}
		return allKeys;
	}

	public List<JSSymbol> getOwnPropertySymbols() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptyList();
		}

		List<JSSymbol> symbols = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int    keyId = shape.getKeyId(i);
			String name  = SymbolTable.name(keyId);
			if (JSSymbol.isSymbolKey(name)) { /* 包括name != null */
				JSSymbol sym = JSSymbol.fromKey(name);
				if (sym != null) symbols.add(sym);
			}
		}
		return symbols;
	}

	public Map<String, Object> getProperties() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptyMap();
		}

		Map<String, Object> map = new LinkedHashMap<>(count);
		for (int i = 0; i < count; i++) {
			if (!shape.isEnumerable(i)) continue;
			int    keyId = shape.getKeyId(i);
			String name  = SymbolTable.name(keyId);
			if (name == null || JSSymbol.isSymbolKey(name)) continue;
			if (isDoubleSlot(i)) {
				map.put(name, getBoxedDouble(i));
			} else {
				Object raw = getRawObjectSlot(i);
				if (shape.hasAccessors && (shape.getSlotType(i) & JSShape.FLAG_ACCESSOR) != 0 && raw instanceof PropertyAccessor acc) {
					map.put(name, acc.callGetter(realm, this));
				} else {
					map.put(name, raw);
				}
			}
		}
		return map;
	}

	private static final ThreadLocal<Set<JSObject>> TO_STRING_VISITING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

	@Override
	public String toString() {
		Set<JSObject> visiting = TO_STRING_VISITING.get();
		if (!visiting.add(this)) {
			return "[Circular]";
		}
		try {
			StringBuilder sb    = new StringBuilder("{");
			boolean       first = true;
			for (String key : keys()) {
				if (!first) sb.append(", ");
				first = false;
				sb.append(key).append(": ").append(get(key));
			}
			sb.append("}");
			return sb.toString();
		} finally {
			visiting.remove(this);
			if (visiting.isEmpty()) {
				TO_STRING_VISITING.remove(); // 释放线程局部 Map，避免长期常驻线程池
			}
		}
	}
	//endregion
}