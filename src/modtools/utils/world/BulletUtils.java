package modtools.utils.world;

import mindustry.gen.*;

public interface BulletUtils {
	static boolean clear(Bullet bullet) {
		bullet.remove();
		return Groups.bullet.contains(b -> b == bullet);
	}
}
