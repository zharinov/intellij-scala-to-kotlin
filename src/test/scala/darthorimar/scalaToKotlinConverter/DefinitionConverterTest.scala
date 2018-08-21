package darthorimar.scalaToKotlinConverter

class DefinitionConverterTest extends ConverterTestBase {
  
  /// TODO: add case class tests 
  def testTraitDef(): Unit =
    doTest(
      """
        |class A(a: Int)
        |trait B
        |class C extends A(1) with B
      """.stripMargin,
      """
        |open class A(private val a: Int)
        |interface B
        |open class C() : A(1), B
      """.stripMargin)

  def companionObjectTest(): Unit =
    doTest(
      """class A {
        |}
        |object A {def a = 5}
        |object B
      """.stripMargin,
      """open class A() {
        |  companion object {
        |    public fun a(): Int =5
        |  }
        |}
        |object B
        |
        |}""".stripMargin)

  def testMultipleConstctorParams(): Unit =
    doTest(
      """
        |class A(a: Int, b: String)
        |class C extends A(1, "nya")
      """.stripMargin,
      """open class A(private val a: Int, private val b: String)
        |open class C() : A(1, "nya")
      """.stripMargin)

  def testClassModifiers(): Unit =
    doTest(
      """
        |final class A
        |class B
        |abstract class C
      """.stripMargin,
      """class A()
        |open class B()
        |abstract class C()
      """.stripMargin)

  class A(a: Int)

}
