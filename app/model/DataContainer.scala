package model

import org.joda.time.DateTime

trait DataContainer {
  def name: String
  def lastUpdated: DateTime
  def isStale: Boolean
}
