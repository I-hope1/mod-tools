package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.util.Context;

import java.io.*;

public class MyClassWriter extends ClassWriter {
	public MyClassWriter(Context context) {super(context);}

	public void writeClassFile(OutputStream out, ClassSymbol c) throws IOException, PoolOverflow, StringOverflow {
		// Target target = getAccess(ClassWriter.class, this, "target");
		super.writeClassFile(out, c);
	}
}
