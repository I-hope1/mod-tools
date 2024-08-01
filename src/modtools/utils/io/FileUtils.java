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
	/** @return {@code true} if the file be deleted successfully */
	public static boolean delete(Fi fi) {
		return fi.exists() && (fi.isDirectory() ? fi.deleteDirectory() : fi.delete());
	}
}