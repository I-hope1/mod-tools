package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.util.Context;

import java.io.*;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.PrintHelper.SPrinter.println;

public class MyClassWriter extends ClassWriter {
	public MyClassWriter(Context context) {super(context);}

	public void writeClassFile(OutputStream out, ClassSymbol c) throws IOException, PoolOverflow, StringOverflow {
		l:
		if (c.isEnum()) {
			MethodSymbol valueOf = (MethodSymbol) c.members().findFirst(names.valueOf,
			 s -> s instanceof MethodSymbol ms && !ms.params.isEmpty()
						&& ms.params.get(0).type == stringType);
			if (valueOf == null) break l;
			println(valueOf);
			valueOf.params.get(0).flags_field &= ~(Flags.SYNTHETIC | Flags.MANDATED); // 去除Synthetic和Mandated
		}
		super.writeClassFile(out, c);
	}
}
