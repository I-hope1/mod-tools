package modtools.annotations.unsafe;

import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.util.*;

public class MyClassWriter extends ClassWriter {
	public MyClassWriter(Context context) {
		super(context);
	}
}

