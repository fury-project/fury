import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SerializerTest extends AnyFlatSpec with Matchers {
  "ScalaCollectionSerializer" should "copy collections correctly" in {
    val fury = new Fury()
    val original = Seq(1, 2, 3)
    val serializer = new ScalaCollectionSerializer[Int, Seq[Int]](fury, classOf[Seq[Int]])

    val copy = serializer.copy(original)
    copy shouldEqual original
    copy should not be theSameInstanceAs (original)
  }

  "ScalaMapSerializer" should "copy maps correctly" in {
    val fury = new Fury()
    val original = Map("a" -> 1, "b" -> 2)
    val serializer = new ScalaMapSerializer[String, Int, Map[String, Int]](fury, classOf[Map[String, Int]])

    val copy = serializer.copy(original)
    copy shouldEqual original
    copy should not be theSameInstanceAs (original)
  }
}
