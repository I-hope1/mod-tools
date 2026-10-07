package oracle

/** Top-level helper compiled into the file facade class, NOT into the object below. */
private fun helperSeq(cs: String): String = cs

/** Positive: trimStart on a literal receiver -> whitelisted StringsKt call. */
object CaseKtTrimStart {
	val s: String = "  a ".trimStart()
}

/** Positive: trimEnd on a literal receiver -> whitelisted StringsKt call. */
object CaseKtTrimEnd {
	val s: String = "  a ".trimEnd()
}

/** Positive: chained whitelisted calls on a literal receiver. */
object CaseKtTrimChain {
	val s: String = "  a ".trim().trimStart()
}

/** Negative N1: receiver produced by NEW, never by a whitelisted call. */
object CaseKtAnyToString {
	val s: String = Any().toString()
}

/** Negative N3 (Kotlin): receiver read from a field. */
object CaseKtFieldToString {
	val f: Any = Any()
	val s: String = f.toString()
}

/** Negative N10: whitelisted call whose argument is a field read, not a constant. */
object CaseKtFieldTrim {
	val f: String = "  a "
	val s: String = f.trim()
}

/** Negative N6 (Kotlin): a non-whitelisted static call in the producer chain. */
object CaseKtChainHelper {
	val s: String = helperSeq("  a ").trim()
}

/** Negative N7/N9 (Kotlin): receiver produced by a user method call. */
object CaseKtMethodCallToString {
	fun makeAny(): Any = Any()
	fun makeSeq(): CharSequence = "x"
	val sAny: String = makeAny().toString()
	val sSeq: String = makeSeq().toString()
}

/**
 * Negative: a branch-merged receiver. At the join the provenance closure is the union of both
 * arms, so the field read of `alt` lands in the closure of the coercion even though the taken
 * branch may be the constant one. Must stay rejected.
 */
object CaseKtTernary {
	val flag: Boolean = true
	val alt: String = "  b "
	val s: String = (if (flag) "  a " else alt).trim()
}