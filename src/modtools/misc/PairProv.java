package modtools.misc;

import arc.func.*;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import modtools.utils.doubleconv.FastFormat;
import modtools.utils.ui.CellTools;

/**
 * PairProv 类实现了 Prov 接口，用于生成和提供表示向量对的字符序列。
 * 它可以通过指定的分隔符连接两个浮点数，并可选择是否使用括号包围。
 * <p>注意：为了极致性能，每次 get() 返回的是同一个 StringBuilder 实例（零 GC 分配），
 * 调用方不得在外部异步清空或长期持有该实例的内容。</p>
 */
public class PairProv implements Prov<CharSequence> {
	public final Prov<Vec2> vecProv;
	public final String     delimiter;
	public final boolean    parentheses;
	public final int        digits;

	// 复用的 StringBuilder，零 GC 分配
	protected final StringBuilder result = new StringBuilder(32);
	// 缓存上次的数值
	protected       float         lastX  = Float.NaN, lastY = Float.NaN;
	protected boolean lastSuccess = false;

	public PairProv(Prov<Vec2> vecProv, String delimiter) {
		this(vecProv, delimiter, true, 2);
	}

	public PairProv(Prov<Vec2> vecProv, String delimiter, int digits) {
		this(vecProv, delimiter, true, digits);
	}

	public PairProv(Prov<Vec2> vecProv, boolean parentheses) {
		this(vecProv, "\n", parentheses, 2);
	}

	public PairProv(Prov<Vec2> vecProv, String delimiter, boolean parentheses) {
		this(vecProv, delimiter, parentheses, 2);
	}


	public PairProv(Prov<Vec2> vecProv, String delimiter, boolean parentheses, int digits) {
		this.vecProv = vecProv;
		this.delimiter = delimiter;
		this.parentheses = parentheses;
		this.digits = digits;
	}

	public void appendTo(StringBuilder sb, float f) {
		FastFormat.autoFixed(sb, f, digits);
	}

	public void appendTo(StringBuilder sb, Vec2 vec) {
		if (parentheses) {
			sb.append('(');
			appendTo(sb, vec.x);
			sb.append(delimiter);
			appendTo(sb, vec.y);
			sb.append(')');
		} else {
			appendTo(sb, vec.x);
			sb.append(delimiter);
			appendTo(sb, vec.y);
		}
	}

	/** 判断数值是否有变动，子类可按需覆写（例如只检查 x） */
	protected boolean hasChanged(Vec2 vec) {
		return !lastSuccess || !Mathf.equal(lastX, vec.x) || !Mathf.equal(lastY, vec.y);
	}

	@Override
	public final StringBuilder get() {
		Vec2 vec;
		try {
			vec = vecProv.get();
			if (vec == null) throw new NullPointerException("vec is null");
		} catch (Throwable e) {
			if (lastSuccess || result.length() == 0) {
				result.setLength(0);
				result.append("[red]ERROR");
				lastSuccess = false;
			}
			return result;
		}

		// 数值变动或之前处于异常状态，重新构建
		if (hasChanged(vec)) {
			result.setLength(0);
			appendTo(result, vec);
			lastX = vec.x;
			lastY = vec.y;
			lastSuccess = true;
		}
		return result;
	}

	/**
	 * SizeProv 是 PairProv 的子类，用于提供表示尺寸的字符序列
	 */
	public static class SizeProv extends PairProv {
		public SizeProv(Prov<Vec2> vecProv) {
			this(vecProv, "[accent]×[]");
		}

		public SizeProv(Vec2 vec2) {
			this(() -> vec2, "[accent]×[]");
		}

		public SizeProv(Prov<Vec2> vecProv, String delimiter) {
			this(vecProv, delimiter, 2);
		}

		public SizeProv(Prov<Vec2> vecProv, String delimiter, int digits) {
			super(vecProv, delimiter, false, digits);
		}

		@Override
		public void appendTo(StringBuilder sb, float f) {
			if (f == CellTools.unset) {
				sb.append("[gray]UNSET[]");
			} else {
				FastFormat.autoFixed(sb, f, digits);
			}
		}
	}

	/** 只取第一个分量并支持后缀追加 */
	public static class SingleProv extends PairProv {
		public final Cons<StringBuilder> builder;

		public SingleProv(Prov<Vec2> vecProv, int digits) {
			this(vecProv, null, digits);
		}

		public SingleProv(Prov<Vec2> vecProv, Cons<StringBuilder> builder, int digits) {
			super(vecProv, "", false, digits); // 显式设置 parentheses 为 false
			this.builder = builder;
		}

		/** 覆写变动检测：只关心 x，避免因 y 变动导致无意义重建 */
		@Override
		protected boolean hasChanged(Vec2 vec) {
			return !lastSuccess || !Mathf.equal(lastX, vec.x);
		}

		@Override
		public void appendTo(StringBuilder sb, Vec2 vec) {
			FastFormat.autoFixed(sb, vec.x, digits);
			if (builder != null) builder.get(sb);
		}
	}
}