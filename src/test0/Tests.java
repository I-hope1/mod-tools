package test0;


public class Tests {
	public static void aMethod(AInterface aInterface) {}
	public static void m2() {}
	;
	public interface AInterface {
		void get(Object o);
	}

	public interface AnnotationInterface {
		String NAME = "<name>";
		static void $condition_test$(AnnotationInterface self) {}
	}
}
