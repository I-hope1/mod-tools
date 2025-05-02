package modtools.unsupported;

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
import modtools.ui.IntUI;
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
		List<String>   fragments    = template.fragments();
		StringBuilder         sb         = new StringBuilder();
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
			} else if(o instanceof Drawable || o instanceof TextureRegion) {
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
	public static void aaa() {
		bbb();
	}
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
	public static void main() {
		aaa();
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
}
