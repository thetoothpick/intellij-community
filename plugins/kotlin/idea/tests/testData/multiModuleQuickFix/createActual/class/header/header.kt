// "Add missing actual declarations" "true"

expect class <caret>My {
    fun foo(param: String): Int

    fun String.bar(y: Double): Boolean

    fun baz(): Unit

    constructor(flag: Boolean)

    val isGood: Boolean

    var status: Int
}