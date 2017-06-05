package play.boilerplate.generators

import org.scalatest.{FlatSpec, Matchers}
import play.boilerplate.parser.backend.swagger.SwaggerBackend

class PlayServiceGeneratorTest extends FlatSpec with Matchers with PrintSyntaxString {

  "Full support" should "Parse petStore_v1.yaml" in {

    //val generator = new PlayServiceGenerator()
    //printSyntaxString(generator.generate("petStore_v1.yaml", "test", ""))

    val schema = SwaggerBackend.parseSchema("petStore_v1.yaml").right.get
    val ctx = DefaultGeneratorContext("petStore_v1.yaml", "test", "")
    val gen = new PlayServiceGeneratorParser(schema).generate(ctx)
    printSyntaxString(gen)

    true should be (true)

  }

  it should "Parse petStore_v2.yaml" in {

    val generator = new PlayServiceGenerator()
    printSyntaxString(generator.generate("petStore_v2.yaml", "test", ""))

    val schema = SwaggerBackend.parseSchema("petStore_v2.yaml").right.get
    val ctx = DefaultGeneratorContext("petStore_v2.yaml", "test", "")
    val gen = new PlayServiceGeneratorParser(schema).generate(ctx)
    printSyntaxString(gen)

    true should be (true)

  }

}