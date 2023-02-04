package modtools.byteu;

import arc.scene.ui.layout.Table;
import arc.util.Log;
import modtools_lib.MyReflect;
import rhino.classfile.ByteCode;
import rhino.classfile.ClassFileWriter;

import java.lang.reflect.Field;

import static modtools_lib.MyReflect.unsafe;

public class Test {
	public static void main() {
		String adapterName = A.class.getName();
		Class<?> superClass = Table.class;
		String superName = superClass.getName().replace('.', '/');
		ClassFileWriter cfw = new ClassFileWriter(adapterName, superClass.getName(), "Table.class");
		cfw.addField("abc", "Ljava/lang/String;",
                     (short) (ClassFileWriter.ACC_PUBLIC |
                              ClassFileWriter.ACC_FINAL));
		cfw.startMethod("<init>",
                        "(Ljava/lang/String;)V",
                        ClassFileWriter.ACC_PUBLIC);

        // Invoke base class constructor
        cfw.add(ByteCode.ALOAD_0);  // this
        cfw.addInvoke(ByteCode.INVOKESPECIAL, superName, "<init>", "()V");

        // Save parameter in instance variable "abc"
        cfw.add(ByteCode.ALOAD_0);  // this
        cfw.add(ByteCode.ALOAD_1);  // first arg: String instance
        cfw.add(ByteCode.PUTFIELD, adapterName, "abc",
                "Ljava/lang/String;");

        cfw.add(ByteCode.RETURN);
        cfw.stopMethod((short)2);

		Class<?> cls = unsafe.defineAnonymousClass(A.class, cfw.toByteArray(), null);
		try {
			Field f = cls.getField("abc");
			MyReflect.setOverride(f);
			Log.info(cls.getConstructor(String.class).newInstance("jzkazak") instanceof Table);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
}

class A {
}