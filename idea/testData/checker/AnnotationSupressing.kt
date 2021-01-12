annotation class A(val i: Int)
annotation class Z(val i: Int)

@Z("BAD") @Suppress("TYPE_MISMATCH")
fun some0() {
}

@Suppress("TYPE_MISMATCH") @Z("BAD")
fun some1() {
}

@A(<error descr="[TYPE_MISMATCH] Type mismatch: inferred type is String but Int was expected">"BAD"</error>) @Suppress("TYPE_MISMATCH")
fun some2() {
}

@Suppress("TYPE_MISMATCH") @A(<error descr="[TYPE_MISMATCH] Type mismatch: inferred type is String but Int was expected">"BAD"</error>)
fun some3() {
}

@Z(<error descr="[TYPE_MISMATCH] Type mismatch: inferred type is String but Int was expected">"BAD"</error>)
fun someN() {
}