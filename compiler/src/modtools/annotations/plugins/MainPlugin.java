package modtools.annotations.plugins;

import com.google.auto.service.AutoService;
import com.sun.source.util.*;

@AutoService(Plugin.class)
public class MainPlugin implements Plugin {
	public String getName() {
		return "ModTools-Plugin";
	}
	public void init(JavacTask task, String... args) {
	}
	public boolean autoStart() {
		return true;
	}
}
