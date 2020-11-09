package conf

import org.specs2.mutable._

class PrismConfigurationSpec extends Specification {
  "getCrawlRate" should {
    val crawlRates = PrismConfiguration.getCrawlRates(Seq("eu-west-1", "us-east-1"))
    "return a fastCrawl for eu-west-1 and an instance resource" in {
      crawlRates("eu-west-1")("instance") should equalTo(PrismConfiguration.fastCrawlRate)
    }
    "return a mediumCrawl for a test region and an instance resource" in {
      crawlRates("test")("instance") should equalTo(PrismConfiguration.slowCrawlRate)
    }
    "return a mediumCrawl for eu-west-1 and a test resource" in {
      crawlRates("eu-west-1")("test") should equalTo(PrismConfiguration.defaultCrawlRate)
    }
    "return a mediumCrawl for test region and test resource" in {
      crawlRates("test")("test") should equalTo(PrismConfiguration.slowCrawlRate)
    }
    "return a slowCrawlRate for all resources in low priority regions" in {
      crawlRates("af-south-1")("test") should equalTo(PrismConfiguration.slowCrawlRate)
    }
  }
}
