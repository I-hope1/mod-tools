package hope.magic.js.runtime;

import hope.magic.js.compiler.JSCompiler;

import java.util.*;

/**
 * Standard ECMAScript / V8-compatible Error stack trace generator and formatter.
 * Provides lazy stack trace generation and Error.captureStackTrace support.
 */
public final class JSStackTrace {

	public static final int DEFAULT_STACK_TRACE_LIMIT = 10;

	/**
	 * Attach a lazy "stack" accessor property to an Error object.
	 *
	 * @param err        The JSObject error instance
	 * @param throwable  Optional Throwable containing stack frames, or null to capture current stack
	 * @param skipFrames Number of frames to skip from the top
	 */
	public static void attach(JSObject err, Throwable throwable, int skipFrames) {
		attach(err, throwable, skipFrames, null);
	}

	/**
	 * Attach a lazy "stack" accessor property to an Error object with constructorOpt support.
	 */
	public static void attach(JSObject err, Throwable throwable, int skipFrames, Object constructorOpt) {
		StackTraceElement[] rawStack = throwable != null ? throwable.getStackTrace() : Thread.currentThread().getStackTrace();
		err.defineAccessor("stack",
			(cx, thisObj, args) -> {
				if (thisObj instanceof JSObject jo) {
					Object cached = jo.get("__stack__");
					if (cached != null && cached != JSUndefined.INSTANCE) {
						return cached;
					}
					String formatted = formatStackTrace(jo, rawStack, skipFrames, constructorOpt);
					jo.put("__stack__", formatted);
					return formatted;
				}
				return JSUndefined.INSTANCE;
			},
			(cx, thisObj, args) -> {
				if (thisObj instanceof JSObject jo && args.length > 0) {
					jo.put("__stack__", args[0]);
				}
				return JSUndefined.INSTANCE;
			},
			false // non-enumerable
		);
	}

	public static void captureStackTrace(JSObject targetObject, Object constructorOpt) {
		if (targetObject == null) return;
		Throwable t = new Throwable();
		attach(targetObject, t, 1, constructorOpt);
	}

	public static String formatStackTrace(JSObject err, StackTraceElement[] elements, int skipFrames) {
		return formatStackTrace(err, elements, skipFrames, null);
	}

	public static String formatStackTrace(JSObject err, StackTraceElement[] elements, int skipFrames, Object constructorOpt) {
		StringBuilder sb = new StringBuilder();

		// Header: Name: Message
		Object nameObj = err.get("name");
		Object msgObj = err.get("message");
		String name = (nameObj != null && nameObj != JSUndefined.INSTANCE) ? JSOps.toStr(nameObj) : "Error";
		String msg = (msgObj != null && msgObj != JSUndefined.INSTANCE) ? JSOps.toStr(msgObj) : "";
		if (msg.isEmpty()) {
			sb.append(name);
		} else {
			sb.append(name).append(": ").append(msg);
		}

		int limit = DEFAULT_STACK_TRACE_LIMIT;
		try {
			Object limitObj = JSContext.LazyErrors.ERROR.get("stackTraceLimit");
			if (limitObj instanceof Number n) {
				limit = n.intValue();
			}
		} catch (Throwable ignored) {
		}
		if (limit <= 0) return sb.toString();

		String omitClass = (constructorOpt != null) ? constructorOpt.getClass().getName() : null;
		boolean hasOmitClass = false;
		if (omitClass != null && elements != null) {
			for (StackTraceElement el : elements) {
				if (el.getClassName().equals(omitClass)) {
					hasOmitClass = true;
					break;
				}
			}
		}
		boolean omitting = hasOmitClass;

		int count = 0;
		if (elements != null) {
			for (int i = skipFrames; i < elements.length && count < limit; i++) {
				StackTraceElement el = elements[i];
				String className = el.getClassName();
				String methodName = el.getMethodName();

				if (omitting) {
					if (className.equals(omitClass)) {
						omitting = false;
					}
					continue;
				}

				// 1. 过滤内部 JVM / Runtime 胶水层
				if (isInternalClass(className)) {
					continue;
				}

				// 2. 识别 JS 编译生成的脚本与函数
				if (className.startsWith("hope.magic.gen.MagicJSFunction_")) {
					if (el.getLineNumber() <= 0) {
						// 过滤 synthetic bridge 桥接转发器
						continue;
					}
					String funcName = JSCompiler.FUNCTION_NAMES.get(className);
					sb.append("\n    at ");
					if (funcName != null && !funcName.isEmpty() && !funcName.equals("anonymous")) {
						sb.append(funcName).append(" (");
						formatLocation(sb, el);
						sb.append(")");
					} else {
						formatLocation(sb, el);
					}
					count++;
				} else if (className.startsWith("hope.magic.gen.MagicJSScript_")) {
					if (el.getLineNumber() <= 0) {
						continue;
					}
					sb.append("\n    at ");
					formatLocation(sb, el);
					count++;
				} else {
					// 3. Java 互操作调用栈 (保留并友好格式化)
					sb.append("\n    at ").append(className).append(".").append(methodName).append(" (");
					formatLocation(sb, el);
					sb.append(")");
					count++;
				}
			}
		}

		return sb.toString();
	}

	private static void formatLocation(StringBuilder sb, StackTraceElement el) {
		String file = el.getFileName();
		int line = el.getLineNumber();
		if (file != null && !file.isEmpty()) {
			sb.append(file);
			if (line > 0) {
				sb.append(":").append(line);
			}
		} else {
			sb.append("<anonymous>");
			if (line > 0) {
				sb.append(":").append(line);
			}
		}
	}

	private static boolean isInternalClass(String cn) {
		return cn.startsWith("hope.magic.runtime.")
		       || cn.startsWith("hope.magic.js.runtime.")
		       || cn.startsWith("hope.magic.js.compiler.")
		       || cn.startsWith("java.lang.invoke.")
		       || cn.startsWith("jdk.internal.")
		       || cn.startsWith("java.lang.reflect.")
		       || cn.startsWith("sun.reflect.")
		       || cn.equals("java.lang.Throwable")
		       || cn.equals("java.lang.Thread");
	}
}
