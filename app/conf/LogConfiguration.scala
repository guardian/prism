package conf

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.gu.logback.appender.kinesis.KinesisAppender
import net.logstash.logback.layout.LogstashLayout
import org.slf4j.{LoggerFactory, Logger => SLFLogger}
import play.api.libs.json.Json
import software.amazon.awssdk.regions.Region
import utils.AWSCredentialProviders

object LogConfiguration {

  private def makeCustomFields(config: Identity, loggingContext: Map[String, String]): String = {
    Json.toJson(Map(
        "app" -> config.app,
        "stack" -> config.stack,
        "stage" -> config.stage,
      )
      ++ loggingContext
    ).toString()
  }

  def shipping(stream: String, config: Identity, loggingContext: Map[String, String]) = {
    val bufferSize: Int = 1000
    val region: Region = Region.EU_WEST_1
    val rootLogger: LogbackLogger = LoggerFactory.getLogger(SLFLogger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val context = rootLogger.getLoggerContext
    val customFields = makeCustomFields(config, loggingContext)
    val layout = new LogstashLayout
    layout.setContext(context)
    layout.setCustomFields(customFields)
    layout.start()

    val appender = new KinesisAppender[ILoggingEvent]
    appender.setBufferSize(bufferSize)
    appender.setRegion(region.id)
    appender.setStreamName(stream)
    appender.setContext(context)
    appender.setLayout(layout)
    appender.setCredentialsProvider(AWSCredentialProviders.deployToolsCredentialsProviderChain)
    appender.start()
    rootLogger.addAppender(appender)
    rootLogger.info("initialised log shipping")
  }
}
