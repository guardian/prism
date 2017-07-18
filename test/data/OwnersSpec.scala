package data

import model._
import org.specs2.mutable._

class OwnersSpec extends Specification {

  val ssa1 = SSA(stack = "s1")
  val ssa2 = SSA(stack = "s1", app = Some("a1"))
  val ssa3 = SSA(stack = "s1", stage = Some("PROD"), app = Some("a1"))
  val ssa4 = SSA(stack = "s2", stage = Some("PROD"))
  val ssa5 = SSA(stack = "s3")


  object TestOwners extends Owners {
    override def default = Owner("aron")

    override def all = Set(
      Owner("bob", Set(ssa1, ssa2)),
      Owner("david", Set(ssa3)),
      Owner("eric", Set(ssa4)),
      Owner("frank", Set(ssa5))
    )
  }

  "forStack" should {
    "return owner with matching stack, stage, app" in {
      TestOwners.forStack("s1", Some("PROD"), Some("a1")).id shouldEqual "david"
    }
    "return owner with matching stack and stage" in {
      TestOwners.forStack("s2", Some("PROD"), None).id shouldEqual "eric"
    }
    "return owner with matching stack" in {
      TestOwners.forStack("s1", None, None).id shouldEqual "bob"
    }
    "return owner with matching stack when app doesn't exist" in {
      TestOwners.forStack("s3", None, Some("doesNotExist")).id shouldEqual "frank"
    }
    "return default owner when stack, stage and app don't exist" in {
      TestOwners.forStack("doesNotExist", Some("doesNotExist"), Some("doesNotExist")).id shouldEqual "aron"
    }
  }

  "A stack" should {
    "have only one owner" in {
      val allStacks = Owners.all.toList.flatMap(_.ssas).map(_.stack)
      allStacks.size shouldEqual allStacks.toSet.size
    }
  }
}
