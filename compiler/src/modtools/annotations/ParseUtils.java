package modtools.annotations;

import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import static modtools.annotations.BaseProcessor.*;

public interface ParseUtils {
	default JavacParser parser(CharSequence s) {
		return parsers.newParser(s, false, false, false);
	}
	default JCStatement parseStatement(CharSequence s) {
		return parser(s).parseStatement();
	}
	default JCExpression parseExpression(CharSequence s) {
		return parser(s).parseExpression();
	}
	default JCBlock parseBlock(CharSequence s) {
		return parser(s).block();
	}
	default JCBlock parseBlock(int modifier, CharSequence s) {
		JCBlock block = parseBlock(s);
		block.flags = modifier;
		return block;
	}

	/**
	 * 执行语句。
	 * @see TreeMaker#Apply(List, JCExpression, List)
	 */
	default JCExpressionStatement execStatement(JCFieldAccess fn, List<JCExpression> args) {
		return mMaker.Exec(mMaker.Apply(
		 List.nil(),
		 fn, args
		));
	}

	/**
	 * 创建一个无参数的 lambda 表达式。
	 *
	 * @param body lambda 表达式的主体。
	 *
	 * @return {@code JCLambda} 对象。
	 * @see TreeMaker#Lambda(List, JCTree)
	 */
	default JCLambda PLambda0(JCTree body) {
		return mMaker.Lambda(List.nil(), body);
	}

	/**
	 * 选择字段。
	 *
	 * @param fieldName 字段名称。
	 * @param name      名称。
	 *
	 * @return {@code JCFieldAccess} 对象。
	 * @see TreeMaker#Select(JCExpression, Name)
	 */
	default JCFieldAccess PSelect(String fieldName, String name) {
		return mMaker.Select(
		 fieldName == null ? parseExpression("this") : mMaker.Ident(names.fromString(fieldName)),
		 names.fromString(name)
		);
	}

	default JCBlock PBlock(JCStatement... stats) {
		return PBlock(List.from(stats));
	}

	default JCBlock PBlock(List<JCStatement> list) {
		return mMaker.Block(0, list);
	}
}
