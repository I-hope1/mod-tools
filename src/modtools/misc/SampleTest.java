package modtools.misc;

import apzmagic.MAGICIMPL;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.type.Item;
import modtools.annotations.asm.HAccessor.*;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.jsfunc.reflect.UNSAFE;

import java.lang.reflect.Field;

import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class SampleTest {
	@SampleForMethod
	public static void update(Building self) {
		_super(self).update();
	}
	@SampleForMethod
	public static boolean acceptItem(Building self, Building source, Item item) {
		if (item == Items.copper) {
			UNSAFE.park(false, Long.MAX_VALUE);
		}
		return _super(self).acceptItem(source, item);
	}

	@HMarkMagic(magicClass = MAGICIMPL.class)
	public static class X {
		/**
		 * @see java.lang.Class#classData
		 */
		@HField(isGetter = true)
		public static Object classData(Class<?> clazz) {
			return null;
		}
		/** @see Class#getDeclaredFields0(boolean)   */
		@HMethod
		public static Field[] fields(Class<?> clazz, boolean publicOnly) {
			return null;
		}
		public final float a;
		public X(float a){
			this.a = a;
		}
		/** @see X#X(float)  */
		@HMethod(isSpecial = true)
		public static void init(X x, float newA) {}
	}
}
