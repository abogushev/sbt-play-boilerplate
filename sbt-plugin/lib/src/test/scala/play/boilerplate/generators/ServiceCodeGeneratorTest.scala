package play.boilerplate.generators

import org.scalatest.{FlatSpec, Matchers}
import play.boilerplate.generators.security.{Play2AuthSecurityProvider, SecurityProvider}
import play.boilerplate.parser.backend.swagger.SwaggerBackend
import treehugger.forest

class ServiceCodeGeneratorTest extends FlatSpec with Matchers with PrintSyntaxString {

  "Service generator: Polymorphism support." should "Inheritance" in {

    val schema = SwaggerBackend.parseSchema("polymorphism/inheritance.yaml").get
    val ctx = GeneratorContext.initial(DefaultGeneratorSettings("polymorphism/inheritance.yaml", "test", Nil))
    val gen = new ServiceCodeGenerator().generate(schema)(ctx)
    printCodeFile(gen)

    true should be (true)

  }

  "Full support" should "Parse petStore.v1.yaml" in {

    val schema = SwaggerBackend.parseSchema("petStore.v1.yaml").get
    val security = new Play2AuthSecurityProvider("User", "AuthConfig", "session") {
      override def parseAuthority(scopes: Seq[SecurityProvider.SecurityScope]): Seq[forest.Tree] = Nil
    }
    val ctx = GeneratorContext.initial(DefaultGeneratorSettings(
      "petStore.v1.yaml",
      "test",
      Nil,
      securityProviders = List(security),
      useTraceId = true,
      traceIdHeader = Some("X-TraceID")
    ))
    val gen = new ServiceCodeGenerator().generate(schema)(ctx)
    printCodeFile(gen)

    true should be (true)

  }

  it should "Parse petStore.v2.yaml" in {

    val schema = SwaggerBackend.parseSchema("petStore.v2.yaml").get
    val ctx = GeneratorContext.initial(DefaultGeneratorSettings("petStore.v2.yaml", "test", Nil))
    val gen = new ServiceCodeGenerator().generate(schema)(ctx)
    printCodeFile(gen)

    true should be (true)

  }

}