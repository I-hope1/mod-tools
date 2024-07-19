package modtools.jsfunc.type;

import modtools.jsfunc.IScript;
import rhino.*;

import java.util.List;

/** 原始数据类型和基本的类
 * @see java.lang.Class#getName */
public interface PTYPE {
	Class<?> Z = boolean.class;
	Class<?> B = byte.class;
	Class<?> C = char.class;
	Class<?> D = double.class;
	Class<?> F = float.class;
	Class<?> I = int.class;
	Class<?> J = long.class;
	Class<?> S = short.class;

	Class<?> V = void.class;

	Class<?> L = Object.class;
	Class<?> CLS = Class.class;
	Class<?> CL = ClassLoader.class;
	Class<?> STR = String.class;

	Class<?> LIST = List.class;

	Object JL = ScriptableObject.getProperty((Scriptable) ScriptableObject.getProperty(IScript.scope, "java"), "lang");
}
