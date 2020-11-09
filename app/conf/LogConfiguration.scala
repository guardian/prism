package conf

import ch.qos.logback.classic.spi.ILoggingEvent
import com.amazonaws.regions.Regions
import com.gu.logback.appender.kinesis.KinesisAppender
import org.slf4j.{LoggerFactory, Logger => SLFLogger}
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.amazonaws.auth.{AWSCredentialsProvider, InstanceProfileCredentialsProvider, STSAssumeRoleSessionCredentialsProvider}
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import net.logstash.logback.layout.LogstashLayout
import play.api.libs.json.Json
import utils.AWSCredentialProviders

object LogConfiguration {

  private def makeCustomFields(config: Identity): String = {
    Json.toJson(Map(
      "app" -> config.app,
      "stack" -> config.stack,
      "stage" -> config.stage)).toString()
  }

  def shipping(stream: String, config: Identity) = {
    val bufferSize: Int = 1000
    val region: Regions = Regions.EU_WEST_1
    val rootLogger: LogbackLogger = LoggerFactory.getLogger(SLFLogger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val context = rootLogger.getLoggerContext
    val customFields = makeCustomFields(config)
    val layout = new LogstashLayout
    layout.setContext(context)
    layout.setCustomFields(customFields)
    layout.start()

    val appender = new KinesisAppender[ILoggingEvent]
    appender.setBufferSize(bufferSize)
    appender.setRegion(region.getName)
    appender.setStreamName(stream)
    appender.setContext(context)
    appender.setLayout(layout)
    appender.setCredentialsProvider(AWSCredentialProviders.deployToolsCredentialsProviderChain)
    appender.start()
    rootLogger.addAppender(appender)
    rootLogger.info("initialised log shipping")
  }
}
