package modtools.unsupported;

import android.content.ContentProvider;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.struct.ObjectMap;
import arc.util.*;
import arc.util.serialization.JsonValue;
import com.sun.tools.attach.VirtualMachine;
import mindustry.mod.ContentParser;
import modtools.annotations.asm.Inline;
import modtools.annotations.asm.Sample.SampleTemp.Template;
import modtools.annotations.linker.*;
import modtools.annotations.linker.LinkMethod.MTemp;
import modtools.ui.*;
import modtools.ui.comp.input.ExtendingLabel;
import modtools.ui.comp.input.ExtendingLabel.DrawType;
import modtools.utils.reflect.HopeReflect;

import java.lang.StringTemplate.Processor;
import java.util.*;

@SuppressWarnings("StringTemplateMigration")
// @DebugMark
public class HopeProcessor {
	/**
	 * 注意：
	 * <p>.interpolate()都会被替换成字符串拼接的形式
	 * <p>.values().get(i)都会被替换 为 对应表达式
	 * <p>.fragments().get(i)都会被替换 为 字符串片段
	 * <p>Processor类可能没有，所以不要加载类
	 **/
	public static final Processor<String, RuntimeException> NPX = string -> modtools.IntVars.modName + "-" + string.interpolate();

	public static final Processor<String, RuntimeException>         S_TIP = string -> "@" + IntUI.TIP_PREFIX + string.interpolate();
	public static final Processor<ExtendingLabel, RuntimeException> LABEL = template -> {
		List<String>  fragments = template.fragments();
		StringBuilder sb        = new StringBuilder();
		for (String fragment : fragments) {
			sb.append(fragment);
		}
		ExtendingLabel label        = new ExtendingLabel("");
		List<Object>   values       = template.values();
		int            currentIndex = fragments.get(0).length();
		int            valueSize    = values.size();
		for (int i = 0; i < valueSize; i++) {
			String fragment = fragments.get(i + 1);
			Object o        = values.get(i);
			if (o instanceof Color color) {
				label.colorMap.put(currentIndex, color);
				currentIndex += fragment.length();
				label.colorMap.put(currentIndex, Color.white);
			} else if (o instanceof Drawable || o instanceof TextureRegion) {
				label.colorMap.put(currentIndex, Color.clear);
				sb.insert(currentIndex, "□");
				label.colorMap.put(currentIndex + 1, Color.white);
				label.addDrawRun(currentIndex, currentIndex + 1, DrawType.icon, Color.white, o);
			}
		}
		label.setText(sb.toString());
		return label;
	};


	public static class Wrapper {
		/** @see mindustry.mod.ContentParser#read(Runnable) */
		void run(Runnable runnable) { }
		/** @see ContentParser#classParsers */
		ObjectMap<Class<?>, @Template(value = "mindustry.mod.ContentParser$FieldParser") Object> classParsers;
		static Wrapper temp(ContentParser parser) {
			return null;
		}
	}
	public static class WrapperFieldParser {
		/** @see mindustry.mod.ContentParser.FieldParser#parse(Class, JsonValue) */
		Object parse(Class<?> type, JsonValue value) throws Exception { return null; }

		static WrapperFieldParser temp(Object val) {
			return null;
		}
	}
	public static void main() {

		// temp.run();
		String aa = "10203";
		int    a  = 1, b = 2;
		Cons<String> run = d -> {
			System.out.println(NPX."aa\{aa}");
			Log.info(a + b + d);
		};
		run.get(aa);
	}
	static void vm() throws Exception {
		VirtualMachine vm0 = VirtualMachine.attach(VirtualMachine.list().stream().filter(
		 vm -> vm.displayName().endsWith(".jar") || vm.displayName().endsWith(".exe")
		).findFirst().orElseThrow());
		Properties props = new Properties();
		props.put("com.sun.management.jmxremote.port", "5000");
		vm0.startManagementAgent(props);
	}
	public static class TestInline {

		@Inline
		public static void bbb() {
			Log.info("Caller: " + HopeReflect.getCaller());
		}
		@Inline
		public static int anInt() {
			return OS.isAndroid ? 1820 * 92902 : 289223;
		}
		public static void asuia() {
			Log.info("int" + anInt());
			// new Table((@Capture Table t) -> {
			// 	image();
			// });

		}

	}

	public static class LinkTest {
		static class Child {
			public int field;

			private int privateField;
			private int privateMethod() {
				return 293;
			}
			private int privateMethod2(int i) {
				return 293 * 29903 * i;
			}
		}
		static class Utils {
			public static int field;

			public static void set(Class<?> clazz, String fieldName, Object obj, Object value) {
				Reflect.set(clazz, obj, fieldName, value);
			}
			public static Object get(Class<?> clazz, String fieldName, Object obj) {
				return Reflect.get(clazz, obj, fieldName);
			}
			public static int aMethod(Child x) {
				return x.field;
			}
			public static Object invoke() {
				return Reflect.invoke(MTemp.declaring, MTemp.obj, MTemp.methodName, MTemp.args, MTemp.params);
			}
		}

		static class Example extends Child {
			/** @see Child#field */
			@LinkFieldToField // 如果是继承的类，可以访问是非静态
			 int f1; // 访问这个字段相当于访问Child.field
			/** @see Utils#field */
			@LinkFieldToField // 如果不是继承的类，必须是静态
			 int f2; // 访问这个字段相当于访问Utils.field

			/**
			 * {@link Utils#set(Class, String, Object, Object) SETTER}
			 * {@link Utils#get(Class, String, Object) GETTER}
			 **/
			@LinkFieldToMethod // 需要两个link，target的setter和getter必须是static方法
			 /*
			 如果是link是非static
			  修改相当于调用Utils.set(Example.class, "f3", this, value)
				读取相当于调用Utils.get(Example.class, "f3", this)
				*/
			 int m1;

			/**
			 * {@link Utils#set(Class, String, Object, Object) SETTER}
			 * {@link Utils#get(Class, String, Object) GETTER}
			 */
			@LinkFieldToMethod
			/* 修改相当于调用Utils.set(Example.class, "f3", null, value)
				读取相当于调用Utils.get(Example.class, "f3", null) */
			static int m2;

			/**
			 * {@link Utils#set(Class, String, Object, Object) SETTER}
			 * {@link Utils#get(Class, String, Object) GETTER}
			 */
			@LinkFieldToMethod(clazz = Child.class, fieldName = "privateField")
			/* 修改相当于调用Utils.set(Child.class, "privateField", this, value)
			 * 读取相当于调用Utils.get(Child.class, "privateField", this) */
			 int m3;

			/** {@link Utils#aMethod(Child) METHOD} */
			@LinkMethod
			int method1() { return 0; }
			/** {@link #privateMethod() METHOD} */
			@LinkMethod
			/* 会在Example创建一个字段Method method2$privateMethod = %init%;  */
			int method2() { return 0; }

			/** {@link #privateMethod2(int) METHOD} */
			@LinkMethod
			/* 检查参数类型是否匹配 */
			/* 会在Example创建一个字段Method methodArg$privateMethod = %init%;  */
			int methodArg(int i) { return 0; }

			void updateX() {
				f1 = 1; // 相当于field = 1
				f2 = 9; // 相当于Utils.field = 9
				m1 = 7; // 修改相当于调用Utils.set(Example.class, "m1", this, 7)
				m2 = 4; // 修改相当于调用Utils.set(Example.class, "m2", null, 4)
				m3 = 1; // 修改相当于调用Utils.set(Child.class, "privateField", this, 1)
				method1(); // 调用相当于调用Utils.aMethod(this);
				method2(); // 调用相当于调用method2$privateMethod.invoke(this);
				methodArg(23); // 调用相当于调用methodArg$privateMethod.invoke(this, 23);
			}
		}
	}
}
