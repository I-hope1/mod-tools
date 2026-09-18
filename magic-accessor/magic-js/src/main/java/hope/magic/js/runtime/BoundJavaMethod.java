package hope.magic.js.runtime;

import java.util.List;
import java.util.Objects;

public class BoundJavaMethod extends JSObject implements JSFunction {

	private static final List<String> BUILTIN_METHOD_PROPS = List.of("name", "length");
	private static final JSShape METHOD_SHAPE = JSShape.createStaticPrototypeShape(
		BUILTIN_METHOD_PROPS,
		new byte[]{
			(byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE),
			(byte) (JSShape.TYPE_DOUBLE | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE)
		}
	);

	public final Object target;
	public final Class<?> targetClass;
	public final String methodName;
	public final boolean isStatic;

	public BoundJavaMethod(Object target, Class<?> targetClass, String methodName, int length, boolean isStatic) {
		super(METHOD_SHAPE, JSContext.LazyFunction.FUNCTION_PROTOTYPE);
		this.target = target;
		this.targetClass = targetClass;
		this.methodName = Objects.requireNonNull(methodName, "methodName");
		this.isStatic = isStatic;
		this.realm = JSContext.current();
		this.obj0 = methodName;
		this.prim1 = Double.doubleToRawLongBits((double) Math.max(0, length));
		this.doubleFieldMask = (1L << 1);
	}

	public String getMethodName() {
		return methodName;
	}

	public Object getTarget() {
		return target;
	}

	public Class<?> getTargetClass() {
		return targetClass;
	}

	public boolean isStatic() {
		return isStatic;
	}

	@Override
	public JSObject getPrototype() {
		JSObject p = super.getPrototype();
		return p != null ? p : JSContext.LazyFunction.FUNCTION_PROTOTYPE;
	}

	@Override
	public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
		JSContext effectiveCx = (cx != null) ? cx : (this.realm != null ? this.realm : JSContext.current());
		JSContext prev = (effectiveCx != null) ? MagicJIT.enterContext(effectiveCx) : null;
		try {
			Object effectiveTarget;
			if (isStatic) {
				effectiveTarget = targetClass;
			} else {
				effectiveTarget = (thisObj != null && thisObj != JSUndefined.INSTANCE && !(thisObj instanceof JSContext.JSGlobalThis) && targetClass.isInstance(thisObj))
					? thisObj
					: target;
			}
			Object[] safeArgs = (args != null) ? args : EMPTY_ARGS;
			return JSLinker.invokeJavaMethod(effectiveTarget, methodName, safeArgs);
		} finally {
			if (effectiveCx != null) {
				MagicJIT.exitContext(effectiveCx, prev);
			}
		}
	}

	@Override
	public String toString() {
		return "function " + methodName + "() { [native code] }";
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj instanceof BoundJavaMethod other) {
			return isStatic == other.isStatic
				&& targetClass == other.targetClass
				&& methodName.equals(other.methodName)
				&& Objects.equals(target, other.target);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return Objects.hash(target, targetClass, methodName, isStatic);
	}
}
