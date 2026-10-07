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

/**
 * M1 coverage: a Kotlin *class* (not an object), so `f` is a real instance field and the
 * initializer lands in `<init>`. v2 adds `s`; only `s` is new.
 */
class CaseKtInstFieldTrim {
	val f: String = "  a "
}

/** M5 coverage: hashCode must stay blacklisted on a constant + whitelisted chain. */
object CaseKtTrimHash