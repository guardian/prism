package utils

import org.slf4j.{Logger, LoggerFactory}

trait Logging {
  implicit val log: Logger = LoggerFactory.getLogger(getClass.getName.stripSuffix("$"))
}
