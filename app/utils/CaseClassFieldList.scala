package utils

object CaseClassFieldList extends Logging {
  import scala.reflect.runtime.universe._

  def allFields[T: TypeTag]: List[String] = {
    def rec(tpe: Type): List[List[Name]] = {
      val collected = tpe.members.collect {
        case m: MethodSymbol if m.isCaseAccessor => m
      }.toList
      if (collected.nonEmpty)
        collected.flatMap { m =>
          m.returnType.typeArgs.headOption match {
            case None => List(List(m.name))
            case Some(t) => rec(t).map(m.name :: _)
          }
        }
      else
        List(Nil)
    }
    rec(typeOf[T]).map(_.mkString("."))
  }
}
