package modtools.extending;

import arc.util.Log;
import mindustry.gen.Entityc;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;

import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class EntitySample {
	@SampleForMethod
	public static void add(Entityc entity) {
		_super(entity).add();
		Log.info(entity);
	}
	@SampleForMethod
	public static void remove(Entityc entity) {
		_super(entity).remove();
		if (!entity.isAdded()) {
			// Log.info(entity);
			ObjectPool.reset(entity);
		}
	}
}
