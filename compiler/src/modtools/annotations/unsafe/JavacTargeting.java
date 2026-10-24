package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

import java.util.HashSet;

import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.PrintHelper.SPrinter.println;
import static modtools.annotations.PrintHelper.errs;
import static modtools.annotations.unsafe.ContextPatcher.removeKey;
import static modtools.annotations.unsafe.ContextPatcher.runIgnoredException;

/**
 * 强制 javac 的目标版本与预览特性开关。
 * <p>
 * 整个模块是拿 source 25 写的，但要产出低版本（Android / d8）能吃的字节码，
 * 所以得在处理器里把 {@link Target}、{@link Lower}、{@link Gen} 这些组件的字段改掉，
 * 并且把高版本才有的语法特性提前“解锁”。
 */
@SuppressWarnings("removal")
public final class JavacTargeting {
	/** 本处理器自己的选项名，命令行上是 {@code -AtargetVersion=<n>} */
	public static final String FORCE_JAVA_VERSION_ORI = "targetVersion";
	public static final String FORCE_JAVA_VERSION     = "-A" + FORCE_JAVA_VERSION_ORI;

	/**
	 * 解锁特性时使用的源码级别，由 {@link #replaceSource()} 消费。
	 * <p>
	 * 默认 {@link Source#JDK8}：把 8 之后才允许的特性全部标成“8 就可用”。
	 * 注意它<b>不</b>随 {@code -AtargetVersion} 变化——那个只改字节码的目标版本。
	 * <p>
	 * 另外有顺序约束：{@code replaceSource()} 必须在 javac 解析源码之前跑
	 * （见 {@code AINIT} 的静态块），那时还读不到 {@code -AtargetVersion}，
	 * 所以它只能依赖这个默认值。
	 */
	public static Source targetVersion = Source.JDK8;

	private JavacTargeting() {}

	/**
	 * 消费 {@code -AtargetVersion=<n>}：把目标版本切过去，然后把选项从 {@link Options} 里摘掉。
	 * <p>
	 * 选项值非法时只打一行日志、什么都不改——写错一个数字不该让整个编译挂掉。
	 * 但补丁本身失败（{@link #setTarget} 抛异常）会照常往上抛，那是处理器自己的问题，得炸。
	 */
	public static void forceJavaVersion(Context context) {
		Options options = Options.instance(context);
		String  hit     = findOption(options, FORCE_JAVA_VERSION + "=");
		if (hit == null) return;
		// 这个选项只服务本处理器，读完就摘掉，别留在选项表里
		options.remove(hit);

		String raw = hit.substring(FORCE_JAVA_VERSION.length() + 1);
		Target target;
		try {
			target = Target.lookup(raw);
		} catch (Throwable e) {
			errs("[MISC]: bad " + FORCE_JAVA_VERSION + " value '" + raw + "': " + e);
			return;
		}
		if (target == null) {
			errs("[MISC]: unknown " + FORCE_JAVA_VERSION + " value '" + raw + "', ignored");
			return;
		}
		setTarget(context, target);
	}

	/** @return 第一个以 {@code prefix} 开头的选项键；没有则 {@code null} */
	public static String findOption(Options options, String prefix) {
		for (String key : options.keySet()) {
			if (key.startsWith(prefix)) return key;
		}
		return null;
	}

	/**
	 * 让 source 8 也能通过高版本特性的检查：把还不允许的 {@link Feature} 的
	 * {@code minLevel} 直接改成 {@link #targetVersion}。
	 */
	public static void replaceSource() {
		long off = fieldOffset(Feature.class, "minLevel");
		for (Feature feature : Feature.values()) {
			if (!feature.allowedInSource(targetVersion)) {
				unsafe.putObject(feature, off, targetVersion);
			}
		}
	}

	/**
	 * 打开预览特性，并屏蔽预览相关的检查与告警。
	 * <p>
	 * 尽力而为：这个补丁依赖 {@code Preview} 的私有字段名，换 JDK 版本就可能变。
	 * 失败只打一行日志、不往上抛——否则一个可选优化会把整轮编译带走。
	 * 只吞 {@link Exception} 和 {@link LinkageError}（字段/方法不存在都是它），
	 * OOM、StackOverflowError 这类还是让它正常冒出来。
	 */
	public static void forcePreview(Context context) {
		try {
			forcePreview0(context);
		} catch (Exception | LinkageError e) {
			errs("[MISC]: failed to enable preview features, ignored: " + e);
		}
	}

	private static void forcePreview0(Context context) {
		Preview preview = Preview.instance(context);
		if (!preview.isEnabled()) {
			setAccess(Preview.class, preview, "enabled", true);
		}
		Lint.instance(context).suppress(LintCategory.PREVIEW);
		runIgnoredException(() -> Check.instance(context).disablePreviewCheck = true);
		// 预览特性用到的类，javac 会记下来并在最后检查；换成永不命中的集合就等于关掉这项检查
		setAccess(Preview.class, preview, "sourcesWithPreviewFeatures", new HashSet<>() {
			public boolean contains(Object o) {
				return false;
			}
		});
	}

	private static void setTarget(Context context, Target target) {
		removeKey(context, Target.class, () -> target);
		println("targetVersion: @ (@)", target.ordinal() + 1, target);

		// 删除旧实例让 StringConcat 按新 Target 重建：
		// target < 9 → Inline，>= 9 → indy（依赖 javac 内部的 makeConcat 逻辑）
		// 不删的话，下面 setAccess(Gen, "concat", ...) 拿到的还是按旧 Target 造出来的那个实例。
		removeKey(context, "concatKey", StringConcat.class, null);

		// 下面几项都是为了让产出能被低版本的 d8 / ART 接受
		runIgnoredException(() -> setAccess(Lower.class, Lower.instance(context), "useMatchException", false));
		setAccess(Lower.class, Lower.instance(context), "target", target);
		runIgnoredException(() -> setAccess(LambdaToMethod.class, LambdaToMethod.instance(context), "nestmateLambdas", false));
		setAccess(Gen.class, Gen.instance(context), "concat", StringConcat.instance(context));
		setAccess(ClassWriter.class, ClassWriter.instance(context), "target", target);
	}
}
