package utils

class StopWatch {
  val startTime = System.currentTimeMillis
  def elapsed = System.currentTimeMillis - startTime
}
