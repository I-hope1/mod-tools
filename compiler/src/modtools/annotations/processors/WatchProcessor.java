package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
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
		// Iterate over each class element
		classFields.forEach((dcls, fieldSeq) -> {
			JCClassDecl classDecl = (JCClassDecl) trees.getTree(dcls);
			// Add a constant element to the class
			addConstantField(classDecl, mSymtab.stringType.tsym.type.constType(dcls), "NAME", dcls.toString()
			 .replace('.', '_'));

			// Get the WatchClass annotation for the class
			WatchClass watchClass = getAnnotationByElement(WatchClass.class, dcls, true);
			Objects.requireNonNull(watchClass);
			// Iterate over the groups in the WatchClass annotation
			for (String s : watchClass.groups()) {
				boolean isStatic = watchClass._static();
				// Log.info("s: @" ,s);
				// Add a element to the class for each group
				addField(classDecl, isStatic ? STATIC : 0,
				 findType(WATCH_STRING), s + WATCH_SIG, "new " + WATCH_STRING + "()");
			}
			// ------------------------fields--------------------------

			/* group -> element[] */
			HashMap<String, HashMap<Element, WatchField>> fieldMap = new HashMap<>();
			// Iterate over each element in the element sequence
			fieldSeq.forEach((field) -> {
				// Get the WatchField annotation for the element
				WatchField watchField = getAnnotationByElement(WatchField.class, field, true);
				Objects.requireNonNull(watchField);
				// Add the element to the element map
				fieldMap.computeIfAbsent(watchField.group(), k -> new HashMap<>())
				 .put(field, watchField);
			});
			// Iterate over each group in the element map
			fieldMap.forEach((group, fields) -> {
				boolean isStatic  = false/* first.getModifiers().contains(Modifier.STATIC) */;
				String  fieldName = (isStatic ? STATIC_SIG : "") + group + WATCH_SIG;
				if (!checkField(dcls, classDecl, fieldName, group)) return;
				// Find the test condition method in the class
				JCMethodDecl test_con = findChild(classDecl, Tag.METHODDEF,
				 m -> m.name.toString().equals(TEST_CON));
				// Check test_con whether is valid
				if (test_con != null) {
					if (!test_con.getModifiers().getFlags().contains(Modifier.STATIC)) {
						err(new IllegalStateException("The method " + classDecl.name + "." + test_con.name + " must be static"));
						return;
					}
					if (test_con.params.length() != 1 || test_con.params.get(0).name.equals(classDecl.name)) {
						err(new IllegalStateException("The method " + classDecl.name + "." + test_con.name + " sig must be ("
						                              + classDecl.name + ")Z"));
						return;
					}
				}

				ArrayList<JCStatement> statements = new ArrayList<>();
				// Iterate over each element in the group
				fields.forEach((el, field) -> {
					/* 添加watch监控
					 * Generate: element.watch("name", () -> element, @interval);
					 **/
					JCStatement x = execStatement(PSelect(fieldName, "watch"),
					 List.of(
						mMaker.Literal(el.getSimpleName().toString()),
						PLambda0(PSelect(null, el.getSimpleName().toString())),
						mMaker.Literal(field.interval())));

					// if (test_con != null) sb.append("if(" + TEST_CON + "(this))");
					if (test_con != null) x = mMaker.If(mMaker.Apply(
					 List.nil(), mMaker.QualIdent(test_con.sym),
					 List.of(mMaker.This(classDecl.sym.type))
					), x, null);
					statements.add(x);
				});
				// Generate: element.showIfOk();
				statements.add(execStatement(PSelect(fieldName, "showIfOk"), List.nil()));

				/* Add a block of statements to the class definition
				 * Time.run(0, () -> {
				 *   x (above)
				 * ))
				 * */
				classDecl.defs = classDecl.defs.append(PBlock(
				 execStatement(
					mMaker.Select(mMaker.Ident(timeSymbol), ns("run")),
					List.of(mMaker.Literal(0),
					 PLambda0(mMaker.Block(0, List.from(statements))))
				 )
				));
			});

			try {
				buildVar(dcls, classDecl);
			} catch (Throwable e) { err(e); }
			// Log.info(classDecl);
		});

		classFields.clear();
	}

	private void buildVar(Element dcls, JCClassDecl classDecl) {
		CompilationUnitTree unit = trees.getPath(dcls).getCompilationUnit();

		classDecl.accept(new TreeScanner<JCTree, Element>() {
			public JCTree visitMethod(MethodTree node, Element parent) {
				return super.visitMethod(node, findChild(parent, node.getName() + "", ElementKind.METHOD));
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

				// symbol.resetAnnotations();
				// symbol.apiComplete();
				WatchVar watchVar = getAnnotationByTree(WatchVar.class, unit, variable, true);
				if (watchVar == null) return super.visitVariable(variable, parent);
				// Log.info(watchVar.classes()[0].getDeclaredMethods());

				String      fieldName = watchVar.group() + WATCH_SIG;
				boolean     valid     = false;
				JCClassDecl classDecl2      = classDecl;
				while (classDecl2 != null) {
					valid |= checkField(dcls, classDecl2, fieldName, watchVar.group());
					classDecl2 = (JCClassDecl) trees.getTree(classDecl2.sym.owner);
				}
				if (!valid) return super.visitVariable(node, parent);
				var list = new ArrayList<>(block.stats);
				// StringBuilder sb = new StringBuilder();
				// sb.append('{');

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

				// sb.append(fieldName).append(".watch(\"lc-%prefix%-%name%\",()->%name%,%interval%);"
				//  .replace("%prefix%", parent.getSimpleName() + "")
				//  .replaceAll("%name%", variable.getName() + "")
				//  .replace("%interval%", watchVar.interval() + "F")
				// );
				// sb.append(fieldName).append(".showIfOk();");
				// sb.append('}');
				// seq.insert(seq.indexOf((JCStatement) variable) + 1,
				//  parseBlock(sb));
				block.stats = List.from(list);
				// Log.info(block);
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
	final Map<Element, ArrayList<Element>> classFields = new HashMap<>();
}
