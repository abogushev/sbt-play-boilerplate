package play.boilerplate.generators.support

import play.boilerplate.generators.{GeneratorContext, GeneratorUtils}
import play.boilerplate.parser.model._

trait ObjectSupport { this: DefinitionsSupport =>

  import GeneratorUtils._
  import treehugger.forest._
  import treehuggerDSL._

  def composeFullClassName(name: String, context: DefinitionContext)(implicit ctx: GeneratorContext): String = {
    val className = name.capitalize
    val pathClassName = (ctx.currentPath.map(_.capitalize) :+ className).mkString("")
    if (ctx.inModel && context.isInline) {
      composeName(ctx.settings.modelPackageName, pathClassName)
    } else if ((ctx.inService || ctx.inClient) && context.isInline) {
      composeName(ctx.settings.servicePackageName, ctx.settings.serviceClassName, pathClassName)
    } else {
      val packageName = if (ctx.isModel) {
        ctx.settings.modelPackageName
      } else {
        ctx.settings.basePackageName
      }
      composeName(packageName, className)
    }
  }

  def getObjectSupport(obj: ObjectDefinition, context: DefinitionContext)
                      (implicit ctx: GeneratorContext): TypeSupport = {
    val support = generateObject(composeFullClassName(obj.name, context), obj.properties, context)
    support.copy(
      defs = support.defs.map { defs =>
        if (context.withoutDefinition) {
          defs.copy(definition = EmptyTree)
        } else {
          defs
        }
      }
    )
  }

  def getComplexObjectSupport(complex: ComplexObjectDefinition, context: DefinitionContext)
                             (implicit ctx: GeneratorContext): TypeSupport = {
    ???
  }

  def generateObject(fullClassName: String, properties: Map[String, Definition], context: DefinitionContext)
                    (implicit ctx: GeneratorContext): TypeSupport = {
    val objectClassName = fullClassName.split('.').last
    val objectClass = definitions.getClass(objectClassName)
    TypeSupport(
      tpe = objectClass,
      fullQualified = definitions.getClass(fullClassName),
      defs = if (context.withoutDefinition) {
        Nil
      } else {
        val (interface, interfaceDef) = if (ctx.needInterface) {
          val symbol = definitions.getClass("I" + objectClassName)
          (Some(symbol), Some(generateInterface(symbol, properties)(ctx.addCurrentPath(objectClass.nameString))))
        } else {
          (None, None)
        }
        generateObjectDefs(objectClass, properties, interface.toList)(ctx.addCurrentPath(objectClass.nameString)) ++
          interfaceDef
      }
    )
  }

  def generateInterface(interfaceSymbol: Symbol, properties: Map[String, Definition])
                       (implicit ctx: GeneratorContext): TypeSupportDefs = {

    val definition = TRAITDEF(interfaceSymbol).withFlags(Flags.SEALED) := BLOCK(
      for ((name, prop) <- properties) yield {
        DEF(name, getTypeSupport(prop).tpe).empty
      }
    )

    TypeSupportDefs(
      symbol = interfaceSymbol,
      definition = definition,
      jsonReads = EmptyTree,
      jsonWrites = EmptyTree,
      queryBindable = EmptyTree,
      pathBindable = EmptyTree
    )

  }

  sealed trait Constraint
  final case class ListConstraint(constraints: Seq[Constraint], tpe: Type) extends Constraint
  final case class MapConstraint(constraints: Seq[Constraint], tpe: Type) extends Constraint
  final case class Maximum(value: Any) extends Constraint
  final case class Minimum(value: Any) extends Constraint
  final case class MaxLength(length: Int) extends Constraint
  final case class MinLength(length: Int) extends Constraint
  final case class Pattern(pattern: String) extends Constraint
  case object Email extends Constraint

  def collectPropertyConstraints(property: Definition)(implicit ctx: GeneratorContext): Seq[Constraint] = {
    property match {
      case OptionDefinition(_, base) =>
        collectPropertyConstraints(base)
      case ArrayDefinition(_, items, _, minItems, maxItems) =>
        val list = ListConstraint(collectPropertyConstraints(items), getTypeSupport(items).tpe)
        list +: (minItems.map(MaxLength).toList ++ maxItems.map(MinLength).toList)
      case RefDefinition(_, ref) =>
        collectPropertyConstraints(ref)
      case MapDefinition(_, additionalProperties) =>
        MapConstraint(collectPropertyConstraints(additionalProperties), getTypeSupport(additionalProperties).tpe) :: Nil
      case _: EmailDefinition =>
        Email :: Nil
      case p: WithMinMax[_] =>
        p.maximum.map(Maximum).toList ++ p.minimum.map(Minimum).toList
      case p: WithMinMaxLength =>
        p.maxLength.map(MaxLength).toList ++ p.minLength.map(MinLength).toList
      case p: WithPattern =>
        p.pattern.map(Pattern).toList
      case _ =>
        Nil
    }
  }

  def getReadsConstraint(constraint: Constraint, noOptType: Type): Tree = {
    constraint match {
      case ListConstraint(constraints, tpe) if constraints.nonEmpty =>
        REF("Reads") DOT "list" APPLYTYPE tpe APPLY constraints.map(getReadsConstraint(_, tpe))
      case MapConstraint(constraints, tpe) if constraints.nonEmpty =>
        REF("Reads") DOT "map" APPLYTYPE tpe APPLY constraints.map(getReadsConstraint(_, tpe))
      case Maximum(value) =>
        REF("Reads") DOT "max" APPLYTYPE noOptType APPLY LIT(value)
      case Minimum(value) =>
        REF("Reads") DOT "min" APPLYTYPE noOptType APPLY LIT(value)
      case MaxLength(length) =>
        REF("Reads") DOT "maxLength" APPLYTYPE noOptType APPLY LIT(length)
      case MinLength(length) =>
        REF("Reads") DOT "minLength" APPLYTYPE noOptType APPLY LIT(length)
      case Pattern(pattern) =>
        REF("Reads") DOT "pattern"   APPLYTYPE noOptType APPLY (LIT(pattern) DOT "r")
      case Email =>
        REF("Reads") DOT "email"
      case _ =>
        EmptyTree
    }
  }

  case class ObjectProperty(name: String, support: TypeSupport, noOptType: Type, isOpt: Boolean, constraints: Seq[Constraint]) {
    def param: ValDef = PARAM(name, support.tpe).empty
    def json : ObjectPropertyJson = ObjectPropertyJson(name, reads, writes)
    def reads : Enumerator = {
      val readsConstraints = filterNonEmptyTree(constraints.map(getReadsConstraint(_, noOptType)))
      val readsDef = PAREN(REF("JsPath") INFIX ("\\", LIT(name))) DOT (if (isOpt) "readNullable" else "read") APPLYTYPE noOptType
      VALFROM(name) := {
        if (readsConstraints.isEmpty) {
          readsDef
        } else {
          readsDef APPLY INFIX_CHAIN("keepAnd", readsConstraints)
        }
      }
    }
    def writes: Tree = {
      LIT(name) INFIX ("->", REF("o") DOT name)
    }
  }

  final case class ObjectJson(reads: Tree, writes: Tree)
  final case class ObjectPropertyJson(name: String, reads: Enumerator, writes: Tree)

  def generateObjectDefs(objectClass: Symbol, properties: Map[String, Definition], parents: Seq[Symbol])
                        (implicit ctx: GeneratorContext): Seq[TypeSupportDefs] = {

    val params = for ((name, prop) <- properties.toSeq) yield {
      val (noOptType, isOpt) = prop match {
        case OptionDefinition(_, base) => (getTypeSupport(base).tpe, true)
        case _ => (getTypeSupport(prop).tpe, false)
      }
      ObjectProperty(name, getTypeSupport(prop), noOptType, isOpt, collectPropertyConstraints(prop))
    }

    val objectDef = if (params.isEmpty) {
      CASEOBJECTDEF(objectClass).withParents(parents).tree
    } else {
      CASECLASSDEF(objectClass).withParams(params.map(_.param)).withParents(parents).tree
    }

    val ObjectJson(reads, writes) = generateObjectJson(objectClass, params)

    val objectDefs = TypeSupportDefs(
      symbol = objectClass,
      definition = objectDef,
      jsonReads  = reads,
      jsonWrites = writes,
      queryBindable = EmptyTree,
      pathBindable  = EmptyTree
    )

    val paramsDefs = params.flatMap(_.support.defs)
      .groupBy(_.symbol.nameString)
      .mapValues(_.head)
      .values.toSeq

    paramsDefs :+ objectDefs

  }

  def generateObjectJson(objectClass: Symbol, properties: Seq[ObjectProperty])
                        (implicit ctx: GeneratorContext): ObjectJson = {

    val caseObject = properties.isEmpty
    val modelName  = objectClass.nameString
    val modelType  = if (caseObject) TYPE_SINGLETON(TYPE_REF(modelName)) else TYPE_REF(modelName)

    ObjectJson(
      reads  = generateObjectReads (modelName, modelType, properties.map(_.json)),
      writes = generateObjectWrites(modelName, modelType, properties.map(_.json))
    )

  }

  def generateObjectReads(modelName: String, modelType: Type, properties: Seq[ObjectPropertyJson])
                         (implicit ctx: GeneratorContext): Tree = {

    val caseObject = properties.isEmpty
    val readsType  = definitions.getClass("Reads") TYPE_OF modelType

    val modelReads = VAL(s"${modelName}Reads", readsType) withFlags (Flags.IMPLICIT, Flags.LAZY) := {
      if (caseObject) {
        REF("Reads") DOT "pure" APPLY REF(modelName)
      } else {
        FOR (properties.map(_.reads): _ *) YIELD REF(modelName) APPLY (properties.map(js => REF(js.name)): _ *)
      }
    }

    modelReads

  }

  def generateObjectWrites(modelName: String, modelType: Type, properties: Seq[ObjectPropertyJson])
                          (implicit ctx: GeneratorContext): Tree = {

    val writesType = definitions.getClass("Writes") TYPE_OF modelType

    val modelWrites = VAL(s"${modelName}Writes", writesType) withFlags(Flags.IMPLICIT, Flags.LAZY) := {
      ANONDEF(writesType) :=
        LAMBDA(PARAM("o").tree) ==> REF("Json") DOT "obj" APPLY (properties.map(_.writes): _ *)
    }

    modelWrites

  }

}
