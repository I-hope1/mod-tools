package modtools.misc;

import arc.func.Prov;
import arc.math.Mathf;
import arc.math.geom.Vec2;

import static modtools.utils.ui.FormatHelper.*;

/**
 * PairProv类实现了Prov接口，用于生成和提供表示向量对的字符序列
 * 它可以通过指定的分隔符连接两个浮点数，并可选择是否使用括号包围
 */
public class PairProv implements Prov<CharSequence> {
	// vecProv用于获取向量值
	public final Prov<Vec2> vecProv;
	// delimiter用于定义两个浮点数之间的分隔符
	public final String     delimiter;
	// parentheses表示结果是否被括号包围
	public final boolean    parentheses;

	// 构造函数：初始化vecProv和delimiter，使用默认的parentheses值true
	public PairProv(Prov<Vec2> vecProv, String delimiter) {
		this(vecProv, delimiter, true);
	}

	// 构造函数：初始化vecProv和parentheses，使用默认的delimiter值"\n"
	public PairProv(Prov<Vec2> vecProv, boolean parentheses) {
		this(vecProv, "\n", parentheses);
	}

	// 构造函数：初始化vecProv、delimiter和parentheses
	public PairProv(Prov<Vec2> vecProv, String delimiter, boolean parentheses) {
		this.vecProv = vecProv;
		this.delimiter = delimiter;
		this.parentheses = parentheses;
	}

	// lastX和lastY用于缓存上一次的向量值，以优化getString的调用
	float lastX, lastY;
	String lastStr;

	public String getString(float f) {
		return fixed(f);
	}

	/**
	 * 根据给定的向量生成字符序列
	 * @param vec 输入的向量值
	 * @return 格式化后的字符序列
	 */
	public String getString(Vec2 vec) {
		return parentheses ? STR."(\{getString(vec.x)}\{delimiter}\{getString(vec.y)})"
		 : STR."\{getString(vec.x)}\{delimiter}\{getString(vec.y)}";
	}

	/**
	 * 获取并格式化向量值，如果结果未变化则返回缓存的结果
	 * @return 格式化后的字符序列
	 */
	public final String get() {
		Vec2 vec;
		try {
			// 尝试获取新的向量值
			vec = vecProv.get();
		} catch (Throwable e) {
			// 如果获取过程中发生异常，返回错误提示
			return "[red]ERROR";
		}

		// 如果缓存结果有效且向量值未变化，则返回缓存结果
		if (lastStr == null || !Mathf.equal(lastX, vec.x) || !Mathf.equal(lastY, vec.y)) {
			lastStr = getString(vec);
		}
		return lastStr;
	}

	/**
	 * SizeProv是PairProv的子类，用于提供表示尺寸的字符序列
	 * 它使用特定的分隔符"×"连接两个浮点数，并不使用括号包围
	 */
	public static class SizeProv extends PairProv {
		// 构造函数：初始化vecProv和特定的delimiter"×"
		public SizeProv(Prov<Vec2> vecProv) {
			this(vecProv, "[accent]×[]");
		}

		// 构造函数：初始化vecProv为固定向量值和特定的delimiter"×"
		public SizeProv(Vec2 vec2) {
			this(() -> vec2, "[accent]×[]");
		}

		// 构造函数：初始化vecProv、自定义的delimiter，并设置parentheses为false
		public SizeProv(Prov<Vec2> vecProv, String delimiter) {
			super(vecProv, delimiter, false);
		}

		/** @see modtools.utils.ui.FormatHelper#fixedUnlessUnset(float)   */
		public String getString(float f) {
			return fixedUnlessUnset(f);
		}
	}
}
