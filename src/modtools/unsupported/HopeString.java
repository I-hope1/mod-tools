package modtools.unsupported;

import arc.util.Log;
import modtools.IntVars;

import java.lang.StringTemplate.Processor;

public class HopeString {
	public static final Processor<String, RuntimeException> NPX = string -> STR."\{IntVars.modName}-\{string.interpolate()}";
	public static void main() {
		Log.info(NPX."nasokoas");
	}
}
