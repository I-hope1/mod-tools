package oracle

/** S3 新版本：object 新增 val l by lazy { 42 }（只加字段）。 */
object CaseKtLazy {
	val l: Int by lazy { 42 }
}
