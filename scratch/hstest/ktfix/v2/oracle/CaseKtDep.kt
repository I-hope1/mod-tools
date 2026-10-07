package oracle

/** S4 新版本：object 新增 val a = 1 与 val b = a + 1（b 依赖新增的 a）。 */
object CaseKtDep {
	val a: Int = 1
	val b: Int = a + 1
}
