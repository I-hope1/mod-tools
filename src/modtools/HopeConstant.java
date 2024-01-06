package modtools;

import arc.KeyBinds.KeybindValue;
import mindustry.input.Binding;
import modtools.utils.Tools.CProv;
import modtools.utils.reflect.FieldUtils;

import java.lang.invoke.*;

import static ihope_lib.MyReflect.lookup;

/** 处理一些不安全的常量 */
public class HopeConstant {
	public static final MethodHandle BINDING_CTOR = nl(() ->
	 lookup.findConstructor(Binding.class,
	 MethodType.methodType(void.class, String.class, int.class, KeybindValue.class, String.class)));
	public static final long BINDING_VALUES = FieldUtils.fieldOffset(FieldUtils.getFieldAccess(Binding.class,"$VALUES"));

	static <R> R nl(CProv<R> prov) {
		try {
			return prov.get();
		} catch (Throwable e) {
			return null;
		}
	}
	public static <R> R iv(MethodHandle handle, Object... args) {
		try {
			return (R) handle.invokeWithArguments(args);
		} catch (Throwable e) {
			return null;
		}
	}
}
