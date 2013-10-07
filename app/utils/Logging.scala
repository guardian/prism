package utils

import play.api.Logger

trait Logging {
  implicit val log = Logger(getClass.getName.stripSuffix("$"))
}