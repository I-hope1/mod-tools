package modtools.extending;

import mindustry.gen.Entityc;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;

import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class EntitySample {
	@SampleForMethod
	public static void remove(Entityc entity) {
		_super(entity).remove();
		if (!entity.isAdded()) {
			// Log.info(entity);
			ObjectPool.reset(entity);
		}
	}
}
