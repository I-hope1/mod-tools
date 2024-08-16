package modtools.unsupported;

import modtools.IntVars;

import java.lang.StringTemplate.Processor;

@SuppressWarnings("StringTemplateMigration")
public class HopeString {
	public static final Processor<String, RuntimeException> NPX = string -> IntVars.modName + "-" + string.interpolate();
	public static void main() {
		System.out.println((NPX."some\{12} text"));
	}
}
