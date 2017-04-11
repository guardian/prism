package data

import model._
import org.specs2.matcher.MatchResult
import org.specs2.mutable._

class OwnersSpec extends Specification {

  "A guardian stack" should {
    "have only one owner" in {
      val stacksWithMultipleOwners = Owners.stacks.groupBy(_._2).filter(_._2.size > 1).toSeq
      val akaMessage = s"Number of stacks with more than one owner (${stacksWithMultipleOwners.map(_._1).mkString(", ")})"
      stacksWithMultipleOwners.size aka akaMessage should beEqualTo(0)
    }
  }

  val defaultSsa = SSA()
  val ssa1 = SSA(stack = Some("s1"))
  val ssa2 = SSA(stack = Some("s1"), app = Some("a1"))
  val ssa3 = SSA(stack = Some("s1"), stage = Some("PROD"), app = Some("a1"))
  val ssa4 = SSA(stack = Some("s2"), stage = Some("PROD"))
  val ssa5 = SSA(stack = Some("s3"))


  object TestOwners extends Owners {
    override def stacks = Set(
      "aron" -> defaultSsa,
      "bob" -> ssa1,
      "bob" -> ssa2,
      "david" -> ssa3,
      "eric" -> ssa4,
      "frank" -> ssa5
    )
  }

  def verify(owner: Option[Owner], expectedId: String): MatchResult[Option[String]] = {
    owner.map(_.id) should equalTo(Some(expectedId))
  }

  "forStack" should {
    "return owner with matching stack, stage, app" in {
      verify(TestOwners.forStack(Some("s1"), Some("PROD"), Some("a1")), "david")
    }
    "return owner with matching stack and stage" in {
      verify(TestOwners.forStack(Some("s2"), Some("PROD"), None), "eric")
    }
    "return owner with matching stack" in {
      verify(TestOwners.forStack(Some("s1"), None, None), "bob")
    }
    "return owner with matching stack when app doesn't exist" in {
      verify(TestOwners.forStack(Some("s3"), None, Some("doesNotExist")), "frank")
    }
    "return default owner when stack, stage and app don't exist" in {
      verify(TestOwners.forStack(Some("doesNotExist"), Some("doesNotExist"), Some("doesNotExist")), "aron")
    }

    "return none when quering only using app" in {
      TestOwners.forStack(None, None, appName =  Some("a1")) must beNone
    }

    "return none when quering only using stage" in {
      TestOwners.forStack(None, stageName = Some("PROD"), None) must beNone
    }

    "return none when quering only using stage and app" in {
      TestOwners.forStack(None, Some("PROD"), Some("a1")) must beNone
    }
  }

  "all" should {
    "return all owners with the stacks they own" in {
      val expected = Set(
        Owner("aron", Set(defaultSsa)),
        Owner("bob", Set(ssa1, ssa2)),
        Owner("david", Set(ssa3)),
        Owner("eric", Set(ssa4)),
        Owner("frank", Set(ssa5))
      )
      TestOwners.all should beEqualTo(expected)
    }
  }

}
