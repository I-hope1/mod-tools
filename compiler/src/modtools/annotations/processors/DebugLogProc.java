package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import java.util.Set;

@AutoService(Processor.class)
public class DebugLogProc extends BaseProcessor<ClassSymbol> {
	public CompilationUnitTree unit;
	public String              fmt;
	public void dealElement(ClassSymbol element) {
		unit = trees.getPath(element).getCompilationUnit();
		fmt = element.getAnnotation(DebugMark.class).fmt();
		trees.getTree(element).accept(translator);
		fmt = null;
		unit = null;
	}
	MyTranslator translator;
	public void init() throws Throwable {
		super.init();
		translator = new MyTranslator();
	}
	class MyTranslator extends TreeTranslator {
		public void visitApply(JCMethodInvocation tree) {
			super.visitApply(tree);
			if (tree.meth instanceof JCFieldAccess access) {
				Symbol symbol = trees.getElement(trees.getPath(unit, access));
				if (!(symbol instanceof MethodSymbol ms
				      && ms.owner.getQualifiedName().contentEquals("arc.util.Log")
				      && ms.name.contentEquals("info")
				      && ms.params.get(0).type.tsym == mSymtab.objectType.tsym
				)) return;

				JCExpression expr = tree.args.get(0);
				if (expr instanceof JCLiteral) return;
				tree.args = List.of(mMaker.Binary(Tag.PLUS,
				 mMaker.Literal(expr.toString()), mMaker.Binary(Tag.PLUS, mMaker.Literal(" = "), expr)));
					/* fmt.replace(
					"%", tree.args.toString())
					 .replace("@", tree); */
			}
		}
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(DebugMark.class);
	}
}
