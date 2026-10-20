package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.BaseProcessor;
import modtools.annotations.watch.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

import static java.lang.reflect.Modifier.STATIC;

@AutoService(Processor.class)
public class WatchProcessor extends BaseProcessor<Element> {
	private static final String STATIC_SIG   = "$T$";
	private static final String WATCH_STRING = "modtools.utils.ui.WatchWindow";
	private static final String WATCH_SIG    = "$W-0";
	private static final String TEST_CON     = "$condition_test$";

	public static ClassSymbol timeSymbol;
	public void init() throws Throwable {
		timeSymbol = findClassSymbol("arc.util.Time");
	}

	public void process() {
		classFields.forEach(this::processClass);
		classFields.clear();
	}

	private void processClass(Element dcls, ArrayList<Element> fieldSeq) {
		JCClassDecl classDecl = (JCClassDecl) trees.getTree(dcls);
		// 增量编译时这个类可能不在当前 round 的源码里，拿不到 tree 就不能改。
		if (classDecl == null) return;

		addConstantField(classDecl, mSymtab.stringType.tsym.type.constType(dcls), "NAME",
		 dcls.toString().replace('.', '_'));

		WatchClass watchClass = getAnnotationByElement(WatchClass.class, dcls, true);
		Objects.requireNonNull(watchClass);

		addGroupFields(classDecl, watchClass);
		collectFields(fieldSeq).forEach((group, fields) -> addGroupWatch(dcls, classDecl, group, fields));

		try {
			buildVar(dcls, classDecl);
		} catch (Throwable e) { err(e); }
	}

	/** 每个 group 一个 {@code WatchWindow} 字段。 */
	private void addGroupFields(JCClassDecl classDecl, WatchClass watchClass) {
		for (String group : watchClass.groups()) {
			addField(classDecl, watchClass._static() ? STATIC : 0,
			 findType(WATCH_STRING), group + WATCH_SIG, "new " + WATCH_STRING + "()");
		}
	}

	/** group → (字段, 注解)。 */
	private Map<String, Map<Element, WatchField>> collectFields(Iterable<Element> fieldSeq) {
		Map<String, Map<Element, WatchField>> fieldMap = new LinkedHashMap<>();
		for (Element field : fieldSeq) {
			WatchField watchField = getAnnotationByElement(WatchField.class, field, true);
			Objects.requireNonNull(watchField);
			fieldMap.computeIfAbsent(watchField.group(), k -> new HashMap<>()).put(field, watchField);
		}
		return fieldMap;
	}

	/**
	 * 为同一个 group 的字段生成一段 {@code Time.run(0, ...)}：
	 * 逐个 {@code group$W-0.watch(...)}，最后 {@code showIfOk()}。
	 */
	private void addGroupWatch(Element dcls, JCClassDecl classDecl, String group,
	                           Map<Element, WatchField> fields) {
		String fieldName = group + WATCH_SIG;
		if (!checkField(dcls, classDecl, fieldName, group)) return;

		JCMethodDecl condition = findChild(classDecl, Tag.METHODDEF,
		 m -> m.name.toString().equals(TEST_CON));
		if (condition != null && !isValidConditionMethod(condition, classDecl)) return;

		ArrayList<JCStatement> statements = new ArrayList<>();
		fields.forEach((el, field) -> {
			/* element.watch("name", () -> element, @interval) */
			JCStatement watch = execStatement(PSelect(fieldName, "watch"),
			 List.of(
				mMaker.Literal(el.getSimpleName().toString()),
				PLambda0(PSelect(null, el.getSimpleName().toString())),
				mMaker.Literal(field.interval())));

			// 有 $condition_test$ 就包一层 if (条件) watch(...)
			if (condition != null) {
				watch = mMaker.If(mMaker.Apply(List.nil(), mMaker.QualIdent(condition.sym),
				 List.of(mMaker.This(classDecl.sym.type))), watch, null);
			}
			statements.add(watch);
		});
		statements.add(execStatement(PSelect(fieldName, "showIfOk"), List.nil()));

		classDecl.defs = classDecl.defs.append(PBlock(
		 execStatement(
			mMaker.Select(mMaker.Ident(timeSymbol), ns("run")),
			List.of(mMaker.Literal(0),
			 PLambda0(mMaker.Block(0, List.from(statements))))
		 )
		));
	}

	/**
	 * {@code $condition_test$} 必须是 {@code static boolean (类名)}。
	 * <p>
	 * 参数类型要和类本身相同；只比参数名的话，写错签名永远不会被发现。
	 */
	private boolean isValidConditionMethod(JCMethodDecl method, JCClassDecl classDecl) {
		if (!method.getModifiers().getFlags().contains(Modifier.STATIC)) {
			err(new IllegalStateException("The method " + classDecl.name + "." + method.name + " must be static"));
			return false;
		}

		Type paramType = method.params.size() == 1 ? method.params.get(0).vartype.type : null;
		if (paramType == null || !types.isSameType(paramType, classDecl.sym.type)) {
			err(new IllegalStateException("The method " + classDecl.name + "." + method.name + " sig must be ("
			                              + classDecl.name + ")Z"));
			return false;
		}
		return true;
	}

	/** @return 外层类声明；已经是顶层类（owner 是包）或拿不到声明就返回 {@code null} */
	private JCClassDecl enclosingClass(JCClassDecl decl) {
		if (decl.sym == null || !(decl.sym.owner instanceof ClassSymbol owner)) return null;
		return trees.getTree(owner) instanceof JCClassDecl outer ? outer : null;
	}

	private void buildVar(Element dcls, JCClassDecl classDecl) {
		CompilationUnitTree unit = trees.getPath(dcls).getCompilationUnit();

		classDecl.accept(new TreeScanner<JCTree, Element>() {
			public JCTree visitMethod(MethodTree node, Element parent) {
				// 匿名类里的方法在 parent 的成员里找不到，继续传 null 会让下一层 findChild NPE
				Element element = parent == null ? null : findChild(parent, node.getName() + "", ElementKind.METHOD);
				return super.visitMethod(node, element);
			}
			public JCBlock block;
			public JCTree visitBlock(BlockTree node, Element parent) {
				block = (JCBlock) node;
				JCTree tree = super.visitBlock(node, parent);
				block = null;
				return tree;
			}
			public JCTree visitVariable(VariableTree node, Element parent) {
				if (block == null) return super.visitVariable(node, parent);
				var variable = (JCVariableDecl) node;

				WatchVar watchVar = getAnnotationByTree(WatchVar.class, unit, variable, true);
				if (watchVar == null) return super.visitVariable(variable, parent);

				String      fieldName = watchVar.group() + WATCH_SIG;
				JCClassDecl owner     = classDecl;
				boolean     valid     = false;
				while (owner != null && !valid) {
					valid = findField(owner, fieldName) != null;
					owner = enclosingClass(owner);
				}
				if (!valid) {
					// 整条外层链都没有这个字段，才是真的没标注；只报一次，别每层都喊
					checkField(dcls, classDecl, fieldName, watchVar.group());
					return super.visitVariable(node, parent);
				}

				var list = new ArrayList<>(block.stats);
				// 在变量声明之后插一句 group$W-0.watch("lc-类名-变量名", () -> 变量, interval).showIfOk();
				list.add(list.indexOf(variable) + 1, execStatement(
				 mMaker.Select(
					mMaker.Apply(List.nil(),
					 PSelect(fieldName, "watch"),
					 List.of(
						mMaker.Literal("lc-" + classDecl.getSimpleName().toString() + "-" + variable.getName()),
						PLambda0(mMaker.Ident(variable)),
						mMaker.Literal(watchVar.interval()))),
					ns("showIfOk")),
				 List.nil()));

				block.stats = List.from(list);
				return super.visitVariable(variable, parent);
			}
		}, dcls);
	}
	public void dealElement(Element element) {
		// 对应WatchClass
		if (element.getKind() == ElementKind.CLASS) {
			classFields.put(element, new ArrayList<>());
		}
		// 对应WatchField
		else if (element.getKind() == ElementKind.FIELD) {
			classFields.computeIfAbsent(element.getEnclosingElement(), k -> new ArrayList<>()).add(element);
		}
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(
		 WatchField.class,
		 WatchClass.class,
		 WatchVar.class
		);
	}
	final Map<Element, ArrayList<Element>> classFields = new LinkedHashMap<>();
}
