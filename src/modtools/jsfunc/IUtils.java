package modtools.jsfunc;

import arc.func.Cons;
import rhino.Callable;

import java.util.Iterator;

public interface IUtils {
	Callable generator = (Callable) IScript.cx.compileString(
	 """
		function* range(start, end, step){
			switch (arguments.length) {
				case 1: step = 1; end = start; start = 0; break;
				case 2: step = 1; break;
				case 3: break;
				default: throw new TypeError(`Unexpected arguments: ${arguments}`);
			}
			if (step == 0) throw new Error("step can not be 0");
			if (step > 0) for (let i = start; i < end; i += step) {
				yield i;
			} else for (let i = start; i > end; i += step) {
				yield i;
			}
		}
		range
		""", "", -1000).exec(IScript.cx, IScript.scope);

	static Object range(int max) {
		return range(0, max);
	}

	static Object range(int min, int max) {
		return range(min, max, 1);
	}

	static Object range(int from, int to, int step) {
		// 判断是否同号
		// if (((from - to) ^ step) > 0) throw new IllegalArgumentException(STR."\{from} --\{step}-> \{to} is unvalid.");
		return generator.call(IScript.cx, IScript.scope, null, new Object[]{from, to, step});
	}

	@SuppressWarnings({"WhileLoopReplaceableByForEach", "rawtypes", "unchecked"})
	static void forEach(Iterable obj, Cons func) {
		Iterator iter = obj.iterator();
		while (iter.hasNext()) func.get(iter.next());
	}
}
