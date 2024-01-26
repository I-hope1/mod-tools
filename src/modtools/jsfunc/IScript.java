package modtools.jsfunc;

import mindustry.Vars;
import modtools.IntVars;
import rhino.*;

public interface IScript {
	ClassLoader main  = Vars.mods.mainLoader();
	Scriptable  scope = Vars.mods.getScripts().scope;

	Object vars     = new NativeJavaClass(scope, IntVars.class);
	Object Contents = new NativeJavaClass(scope, modtools.ui.Contents.class);
}
