package modtools.annotations;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.builder.DataBoolFieldInit;

import static modtools.annotations.BaseProcessor.*;

public interface DataUtils {
	default JCMethodInvocation selfData(Type type) {
		return mMaker.Apply(List.nil(), mMaker.Select(mMaker.This(type), names.fromString("data")), List.nil());
	}
	ClassSymbol[] symbols = {
	 null/* MySettings */,
	 null/* SettingUI */
	};
	default ClassSymbol SettingUI() {
		if (symbols[1] == null)
			symbols[1] = mSymtab.getClass(mSymtab.unnamedModule, names.fromString("modtools.ui.content.SettingsUI"));
		return symbols[1];
	}
	default JCFieldAccess internalData(String key) {
		if (symbols[0] == null)
			symbols[0] = mSymtab.getClass(mSymtab.unnamedModule, names.fromString("modtools.utils.MySettings"));
		return mMaker.Select(mMaker.Ident(symbols[0]), names.fromString(key));
	}
	default JCExpression getData(String data, Type type) {
		return data.isEmpty() ? selfData(type) : internalData(data);
	}

}
