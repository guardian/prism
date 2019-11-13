package conf

import com.gu.management.{ManifestPage, CountMetric, TimingMetric, GaugeMetric, Switchboard, HealthcheckManagementPage, StatusPage, PropertiesPage}
import com.gu.management.play.{RequestMetrics, Management => GuManagement}
import com.gu.management.logback.LogbackLevelPage


class Metrics(prismAgents: controllers.PrismAgents) {

  object PlayRequestMetrics extends RequestMetrics.Standard

  object sourceMetrics {
    def sources = prismAgents.globalCollectorAgent.sources.data
    object TotalGauge extends GaugeMetric("prism", "sources", "Sources", "Number of sources in Prism", () => sources.size)
    object SuccessGauge extends GaugeMetric("prism", "success_sources", "Successful Sources", "Number of sources in Prism that last ran successfully", () => sources.count(_.error.isEmpty), Some(TotalGauge))
    object ErrorGauge extends GaugeMetric("prism", "error_sources", "Erroring Sources", "Number of sources in Prism that are failing to run", () => sources.count(_.error.isDefined), Some(TotalGauge))

    object CrawlTimer extends TimingMetric("prism", "crawl", "Crawls", "Attempted crawls of sources")
    object CrawlSuccessCounter extends CountMetric("prism", "crawl_success", "Successful crawls", "Number of crawls that succeeded")
    object CrawlFailureCounter extends CountMetric("prism", "crawl_error", "Failed crawls", "Number of crawls that failed")
    val all = Seq(TotalGauge, SuccessGauge, ErrorGauge, CrawlTimer, CrawlSuccessCounter, CrawlFailureCounter)
  }
}




/*object DataMetrics extends Logging {
  val resourceNames = Prism.allAgents.flatMap(_.resourceName).distinct
  def countResources(resource:String) = {
    val filteredAgents = Prism.allAgents.filter{ _.resourceName == Some(resource) }
    filteredAgents.map(_.size).fold(0)(_+_)
  }
  val resourceGauges = resourceNames.map { resource =>
    new GaugeMetric("prism", s"${resource}_entities", s"$resource entities", s"Number of $resource entities", () => countResources(resource))
  }
}*/

/*object Management extends GuManagement {
  val applicationName = "prism"

  lazy val pages = List(
    new ManifestPage(),
    new Switchboard(applicationName, Seq()),
    new HealthcheckManagementPage,
    StatusPage(applicationName, PlayRequestMetrics.asMetrics ++ SourceMetrics.all ++ DataMetrics.resourceGauges),
    new PropertiesPage(Configuration.toString),
    new LogbackLevelPage(applicationName)
  )
}*/