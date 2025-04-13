package modtools.misc;

import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.type.Item;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.jsfunc.reflect.UNSAFE;

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
}
