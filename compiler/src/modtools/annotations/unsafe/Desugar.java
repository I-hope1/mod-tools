package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Lower;
import com.sun.tools.javac.tree.JCTree.JCStringTemplate;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.*;

import static modtools.annotations.PrintHelper.SPrinter.println;

public class Desugar extends Lower {
	final Symtab    syms;
	final TreeMaker make;
	final Names     names;

	public Desugar(Context context) {
		super(context);
		this.syms = Symtab.instance(context);
		this.make = TreeMaker.instance(context);
		this.names = Names.instance(context);
	}
	@Override
	public void visitStringTemplate(JCStringTemplate tree) {
		if (tree.processor.type == syms.processorType) {
			// Assume we're dealing with a simple string concatenation for desugaring
			println(tree.processor);
			/* List<JCExpression> parts = new ArrayList<>();
			for (var content : tree.fragments) {
				tree.expressions
				// Add the literal part as a string
				parts.add(make.Literal(((JCStringTemplate.Literal) content).value));
			}

			// If there are multiple parts, concatenate them using the `+` operator
			JCExpression concatenated = parts.get(0);
			for (int i = 1; i < parts.size(); i++) {
				concatenated = make.Binary(JCTree.Tag.PLUS, concatenated, parts.get(i));
			}

			// Replace the original template with the desugared expression
			result = concatenated;
			return; */
		}
		super.visitStringTemplate(tree);
	}
}