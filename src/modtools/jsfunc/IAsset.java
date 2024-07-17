package modtools.jsfunc;

import arc.files.Fi;
import arc.struct.Seq;

import static mindustry.Vars.mods;

public interface IAsset {

	static Fi[] file(String path) {
		Seq<Fi> searched = new Seq<>();
		mods.eachEnabled(m -> {
			Fi child = m.root.child(path);
			if (child.exists()) {
				searched.add(child);
			}
		});
		return searched.toArray(Fi.class);
	}
}
