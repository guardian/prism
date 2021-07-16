package agent

import scala.language.postfixOps

trait CollectorAgentTrait[T<:IndexedItem] {
  def get(): Iterable[ApiDatum[T]]

  def getTuples: Iterable[(ApiLabel, T)] = get().flatMap(datum => datum.data.map(datum.label ->))

  def init():Unit

  def shutdown():Unit
}