package modtools.misc;

import arc.util.Log;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;

import java.util.*;

@Sample(openPackagePrivate = true, defaultClass = "jdk.internal.reflect.MagicAccessorImpl")
public class SamplePackage {
	@SampleForMethod()
	public static <T> T[] keysToArray(HashMap self, T[] arr) {
		return (T[]) new Object[]{"123", "ausojisji"};
		// return _super(map).keysToArray(arr);
	}

	static {
		test();
	}
	static void test(){
		var map = SamplePackageInterface.changeClass(new HashMap<>());
		map.put("SamplePackage", SamplePackage.class);
		Arrays.stream(map.keySet().toArray()).forEach(Log::info); // 123 ausojisji

		Log.info(SamplePackageInterface.changeClass(new Object()).getClass());
	}
}
