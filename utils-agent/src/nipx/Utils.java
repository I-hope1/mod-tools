package nipx;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Utils {
	public static void agentmain(String agentArgs, Instrumentation inst) throws IOException {
		String[] args = agentArgs.split("\n");
		// System.out.println("------" + agentArgs);
		switch (args[0]) {
			case "appendToBootstrap" -> inst.appendToBootstrapClassLoaderSearch(new JarFile(args[1]));
		}
	}
}