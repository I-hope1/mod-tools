package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import java.util.*;

@AutoService(Processor.class)
public class LogCostTimeProcessor extends BaseProcessor<MethodSymbol> {

	ClassSymbol CL_Log, CL_Time;
	public void init() throws Throwable {
		CL_Log = findClassSymbol("arc.util.Log");
		CL_Time = findClassSymbol("arc.util.Time");
	}
	public void dealElement(MethodSymbol element) {
		JCMethodDecl tree = trees.getTree(element);
		addImport(element, CL_Log);
		addImport(element, CL_Time);
		CostTimeLog anno = element.getAnnotation(CostTimeLog.class);
		/* 生成结果:
		Time.mark();
    try {
        // 原始代码
    } finally {
        Log.info("Costs in @ms", Time.elapsed());
    } */
		tree.body.stats = List.of(mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(CL_Time), ns("mark")),
			 List.nil()
			)
		 ),
		 mMaker.Try(PBlock(tree.body.stats), List.nil(),
			PBlock(mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(CL_Log), ns("info")),
			 List.of(mMaker.Literal(anno.info()),
				mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(CL_Time), ns("elapsed")), List.nil())
			 )
			)))
		 )
		);
		print(tree);
	}

	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(CostTimeLog.class.getCanonicalName());
	}
}
