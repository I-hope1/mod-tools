package modtools.jsfunc;

import arc.files.Fi;

import static mindustry.Vars.*;

public interface IAsset {

	static Fi file(String path) {
		return tree.get(path);
	}

	static Fi file(String modName, String path) {
		return mods.getMod(modName).root.child(path);
	}
}
