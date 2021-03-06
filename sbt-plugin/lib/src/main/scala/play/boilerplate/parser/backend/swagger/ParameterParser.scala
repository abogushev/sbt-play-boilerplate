package play.boilerplate.parser.backend.swagger

import io.swagger.models.parameters.{AbstractSerializableParameter, Parameter => SwaggerParameter}
import io.swagger.models.properties.{ArrayProperty, PropertyBuilder, Property => SwaggerProperty}
import play.boilerplate.parser.backend.ParserException
import play.boilerplate.parser.model._

import scala.collection.JavaConverters._

trait ParameterParser { this: ModelParser with PropertyParser with ReferenceParser =>

  protected def parseParameter(schema: Schema, parameter: SwaggerParameter)
                              (implicit ctx: ParserContext): Parameter = {

    import io.swagger.models.{parameters => swagger}

    val out = Option(parameter).getOrElse {
      throw ParserException("Trying to resolve null parameter.")
    } match {
      case param: swagger.BodyParameter =>
        val context = ctx.withObjectCreator(ObjectDefinitionInline).withEnumCreator(EnumDefinitionInline)
        val body = parseModel(schema, getParamName(parameter), param.getSchema)(context)
        BodyParameterFactory.build(body, Option(param.getName))
      case param: swagger.HeaderParameter =>
        parseTypedParameter(schema, ASP(param), HeaderParameterFactory)
      case param: swagger.PathParameter =>
        parseTypedParameter(schema, ASP(param), PathParameterFactory)
      case param: swagger.QueryParameter =>
        parseTypedParameter(schema, ASP(param), QueryParameterFactory)
      case param: swagger.FormParameter =>
        parseTypedParameter(schema, ASP(param), FormParameterFactory)
      case param: swagger.RefParameter =>
        RefParameterFactory.build(findReferenceDef(schema, param.get$ref()))
      case param =>
        throw ParserException(s"Unsupported parameter type (${param.getClass.getName}).")
    }

    out.withDesc(Option(parameter.getDescription))

  }

  private def getParamName(parameter: SwaggerParameter): String = {
    Option(parameter.getName).getOrElse {
      throw ParserException("Parameter name is not specified.")
    }
  }

  private case class ASP[T <: AbstractSerializableParameter[T]](underlying: AbstractSerializableParameter[T]) {

    def getName: String = Option(underlying.getName).getOrElse(getParamName(underlying))

    def isOptional: Boolean = Option(underlying.getRequired).forall(_ == false)

    def isArray: Boolean = underlying.getType == ArrayProperty.TYPE

    def getItems: SwaggerProperty = Option(underlying.getItems).getOrElse {
      throw ParserException(s"Array `items` property is not specified for parameter.")
    }

    def isEnum: Boolean = Option(underlying.getEnum).isDefined

    def getEnum: Iterable[String] = underlying.getEnum.asScala

    def collectionFormat: CollectionFormat = Option(underlying.getCollectionFormat).getOrElse("csv") match {
      case "csv"   => CollectionFormat.Csv
      case "ssv"   => CollectionFormat.Ssv
      case "tsv"   => CollectionFormat.Tsv
      case "pipes" => CollectionFormat.Pipes
      case "multi" => CollectionFormat.Multi
      case other   => throw ParserException(s"Unknown `collectionFormat` property value ($other).")
    }

    def toProperty: SwaggerProperty = {

      import io.swagger.models.properties.PropertyBuilder.PropertyId._

      val args = Map(
        ENUM -> underlying.getEnum,
        PATTERN -> underlying.getPattern,
        MIN_ITEMS -> underlying.getMinItems,
        MAX_ITEMS -> underlying.getMaxItems,
        MIN_LENGTH -> underlying.getMinLength,
        MAX_LENGTH -> underlying.getMaxLength,
        MINIMUM -> underlying.getMinimum,
        MAXIMUM -> underlying.getMaximum,
        MULTIPLE_OF -> underlying.getMultipleOf,
        VENDOR_EXTENSIONS -> underlying.getVendorExtensions
      ).asJava

      val property = PropertyBuilder.build(underlying.getType, underlying.getFormat, args)
      property.setName(underlying.getName)
      property.setRequired(underlying.getRequired)
      property.setDescription(underlying.getDescription)
      Option(underlying.getDefault).foreach(d => property.setDefault(d.toString))
      property.setExample(underlying.getExample)
      property.setAllowEmptyValue(underlying.getAllowEmptyValue)

      property

    }

  }

  private def parseTypedParameter(schema: Schema,
                                  parameter: ASP[_],
                                  factory: DefinitionFactory[Parameter],
                                  canBeOption: Boolean = true)
                                 (implicit ctx: ParserContext): Parameter = {
    if (parameter.isOptional && canBeOption) {
      factory.build(OptionDefinition(
        name = parameter.getName,
        base = parseTypedParameter(schema, parameter, factory, canBeOption = false)
      ), Option(parameter.underlying.getName))
    } else if (parameter.isArray) {
      factory.build(ArrayDefinition(
        name = parameter.getName,
        description = Option(parameter.underlying.getDescription),
        items = getPropertyDef(schema, parameter.getName, parameter.getItems, canBeOption = false),
        uniqueItems = Option(parameter.underlying.isUniqueItems).exists(_ == true),
        minItems = Option(parameter.underlying.getMinItems).map(Integer2int),
        maxItems = Option(parameter.underlying.getMaxItems).map(Integer2int),
        collectionFormat = parameter.collectionFormat
      ), Option(parameter.underlying.getName))
    } else if (parameter.isEnum) {
      factory.build(EnumDefinitionInline(
        items = parameter.getEnum,
        name = parameter.getName,
        title = None,
        description = Option(parameter.underlying.getDescription),
        readOnly = Option(parameter.underlying.isReadOnly).exists(_ == true),
        allowEmptyValue = Option(parameter.underlying.getAllowEmptyValue).exists(_ == true),
        default = None
      ), Option(parameter.underlying.getName))
    } else {
      getPropertyFactoryDef(schema, parameter.getName, parameter.toProperty, factory, canBeOption)
    }
  }

  // TODO Nested object query parameters like 'a.b.c'
  def findObjectQueryParameters(parameters: Iterable[Parameter]): Iterable[Parameter] = {

    val groupedParams = parameters.foldLeft(Map.empty[String, Map[String, Definition]]) {
      case (acc, param: QueryParameter) =>
        val parts = param.name.split('.')
        if (parts.length > 1) {
          val objectName = parts.head
          val properties = acc.getOrElse(objectName, Map.empty)
          val propertyName = parts.tail.mkString
          val property = param.ref.modifyName(_ => propertyName)
          acc + (objectName -> (properties + (propertyName -> property)))
        } else {
          acc
        }
      case (acc, _) =>
        acc
    }

    val otherParams = parameters.filterNot {
      case param: QueryParameter =>
        param.name.split('.').length > 1
      case _ =>
        false
    }

    groupedParams.map { case (objectName, properties) =>
      val objectDef = ObjectDefinitionInline(
        properties = properties,
        name = objectName,
        title = None,
        description = Some(s"Class, that composed query parameters: ${properties.keys.map(p => objectName + "." + p).mkString(", ")}"),
        readOnly = true,
        allowEmptyValue = false
      )
      new QueryParameter(objectName, None, objectDef)
    } ++ otherParams

  }

}
