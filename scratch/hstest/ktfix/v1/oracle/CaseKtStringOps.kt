package oracle

/**
 * A+ fixtures, v1: the objects exist but carry no new fields yet.
 * v2 adds the fields that exercise the Object.toString() coercion gate.
 *
 * The receiver fields of CaseKtFieldToString / CaseKtFieldTrim live in v1 on purpose:
 * only the field under test may be "new" in v2, so a rejection cannot be masked by
 * another field of the same class being patched.
 */
object CaseKtTrimStart
object CaseKtTrimEnd
object CaseKtTrimChain
object CaseKtAnyToString
object CaseKtFieldToString {
	val f: Any = Any()
}
object CaseKtFieldTrim {
	val f: String = "  a "
}
object CaseKtChainHelper
object CaseKtMethodCallToString
object CaseKtTernary {
	val flag: Boolean = true
	val alt: String = "  b "
}