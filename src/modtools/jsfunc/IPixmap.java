package modtools.jsfunc;

import arc.graphics.*;
import arc.graphics.gl.*;
import modtools.Constants.PIXMAP;
import modtools.jsfunc.reflect.UNSAFE;

public interface IPixmap {
	static Pixmap pixmapOf(Texture texture) {
		if (texture.getTextureData() instanceof PixmapTextureData ptd) {
			return ptd.consumePixmap();
		}
		if (texture.getTextureData() instanceof FileTextureData ftd) {
			return (Pixmap) UNSAFE.getObject(ftd, PIXMAP.PIXMAP);
		}
		return null;
	}
}
