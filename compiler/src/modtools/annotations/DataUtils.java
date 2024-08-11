package modtools.annotations;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree.*;

import static modtools.annotations.BaseProcessor.*;

public interface DataUtils extends NameString {
	default JCFieldAccess selfData(String typeName) {
		return mMaker.Select(mMaker.Ident(ns(typeName)), ns("data"));
	}
	ClassSymbol[] symbols = {
	 null/* MySettings */,
	 null/* SettingUI */,
	 null/* Data */
	};
	default ClassSymbol C_MySettings() {
		return checkAndSet(0, "modtools.utils.MySettings");
	}
	default ClassSymbol SettingUI() {
		return checkAndSet(1, "modtools.content.SettingsUI");
	}
	default ClassSymbol C_Data() {
		return checkAndSet(2, "modtools.utils.MySettings$Data");
	}

	private ClassSymbol checkAndSet(int i, String name) {
		if (symbols[i] == null)
			symbols[i] = mSymtab.getClass(mSymtab.unnamedModule, ns(name));
		return symbols[i];
	}
	default JCFieldAccess internalData(String key) {
		return mMaker.Select(mMaker.Ident(C_MySettings()), ns(key));
	}
	default JCExpression getData(String data, String typeName) {
		return data.isEmpty() ? selfData(typeName) : internalData(data);
	}

}
