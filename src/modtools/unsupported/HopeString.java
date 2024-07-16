package modtools.unsupported;

import modtools.IntVars;

import java.lang.StringTemplate.Processor;

public class HopeString {
	public static final Processor<String, RuntimeException> NPX = string -> STR."\{IntVars.modName}-\{string.interpolate()}";
}
