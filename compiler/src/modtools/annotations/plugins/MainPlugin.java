package modtools.annotations.plugins;

import com.google.auto.service.AutoService;
import com.sun.source.util.*;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.util.Context;

import static modtools.annotations.HopeReflect.setAccess;

@AutoService(Plugin.class)
public class MainPlugin implements Plugin {
	public String getName() {
		return "ModTools-Plugin";
	}
	public void init(JavacTask task, String... args) {
		Context context = ((JavacTaskImpl) task).getContext();
		Symtab syms = Symtab.instance(context);
		setAccess(Symtab.class, syms, "recordType", syms.objectType);
	}
	public boolean autoStart() {
		return true;
	}
}
