package modtools.annotations;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;

import static modtools.annotations.BaseProcessor.*;

public interface DataUtils {
	default JCMethodInvocation selfData(Type type) {
		return mMaker.Apply(List.nil(), mMaker.Select(mMaker.This(type), names.fromString("data")), List.nil());
	}
	ClassSymbol[] MySettings = {null};
	default JCFieldAccess internalData(String key) {
		if (MySettings[0] == null)
			MySettings[0] = mSymtab.getClass(mSymtab.unnamedModule, names.fromString("modtools.utils.MySettings"));
		return mMaker.Select(mMaker.Ident(MySettings[0]), names.fromString(key));
	}
}
