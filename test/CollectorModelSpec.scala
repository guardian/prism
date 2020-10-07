import agent.BestBefore
import org.joda.time.DateTime
import org.specs2.mutable._

import scala.concurrent.duration._

class CollectorModelSpec extends Specification {
  "BestBefore" should {
    "correctly calculate the best before date" in {
      val bb = BestBefore(new DateTime(2013,11,8,10,0,0), 30 seconds, error = false)
      bb.bestBefore should equalTo(new DateTime(2013,11,8,10,0,30))
    }
    "say it is stale if past best before" in {
      val bb = BestBefore(new DateTime(2013,11,8,10,0,0), 30 seconds, error = false)
      bb.bestBefore should be equalTo new DateTime(2013,11,8,10,0,30)
      (new DateTime).getMillis should be greaterThan bb.bestBefore.getMillis
      bb.isStale should beTrue
    }
    "say it is not stale if only just created" in {
      val bb = BestBefore(new DateTime, 30 seconds, error = false)
      bb.isStale should beFalse
    }
  }

}