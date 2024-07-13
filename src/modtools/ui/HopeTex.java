package modtools.ui;

import arc.func.Prov;
import arc.scene.style.Drawable;
import mindustry.gen.Tex;

public interface HopeTex {
	Drawable alphaBgLine = nl(() -> Tex.alphaBgLine, Tex.alphaBg);
	Drawable paneRight   = nl(() -> Tex.paneRight, Tex.pane);
	static Drawable nl(Prov<Drawable> prov) {
		return nl(prov, null);
	}

	static Drawable nl(Prov<Drawable> prov, Drawable def) {
		try {
			return prov.get();
		} catch (Throwable e) {return def;}
	}
}
