package modtools.unsupported;

import arc.func.Cons;
import arc.util.Log;
import com.sun.tools.attach.VirtualMachine;

import java.lang.StringTemplate.Processor;
import java.util.Properties;

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

	public static void main() {
		String aa = "10203";
		int    a  = 1, b = 2;
		Cons<String> run = d -> {
			System.out.println(NPX."aa\{aa}");
			Log.info(a + b + d);
		};
		run.get(aa);
	}
	static void vm() throws Exception {
		VirtualMachine vm0 =  VirtualMachine. attach(VirtualMachine.list().stream().filter(
		 vm -> vm.displayName().endsWith(".jar") || vm.displayName().endsWith(".exe")
		).findFirst().orElseThrow());
		Properties props = new Properties();
		props.put("com.sun.management.jmxremote.port", "5000");
		vm0.startManagementAgent(props);
	}
}
