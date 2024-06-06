package modtools.utils.io;

import arc.files.Fi;

public class FileUtils {
	public static Fi child(Fi parent, String newName, String... oldNames) {
		Fi child = parent.child(newName);
		for (String oldName : oldNames) {
			Fi child1 = parent.child(oldName);
			if (child1.exists() && child1.isDirectory()) child1.moveTo(child);
		}
		return child;
	}
}