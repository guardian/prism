package utils

import org.apache.pekko.actor.{ActorSystem, Cancellable}

import scala.concurrent.duration._
import scala.language.postfixOps
import org.joda.time.{DateTime, Interval, LocalDate, LocalTime}
import org.apache.pekko.agent.Agent

import scala.concurrent.ExecutionContext

object ScheduledAgent extends LifecycleWithoutApp {
  var scheduleSystem: Option[ActorSystem] = None

  def apply[T](initialDelay: FiniteDuration, frequency: FiniteDuration)(
      block: => T
  )(implicit ec: ExecutionContext): ScheduledAgent[T] = {
    ScheduledAgent(initialDelay, frequency, block)(_ => block)
  }

  def apply[T](
      initialDelay: FiniteDuration,
      frequency: FiniteDuration,
      initialValue: T
  )(block: T => T)(implicit ec: ExecutionContext): ScheduledAgent[T] = {
    ScheduledAgent(
      initialValue,
      PeriodicScheduledAgentUpdate(block, initialDelay, frequency)
    )
  }

  def apply[T](initialValue: T, updates: ScheduledAgentUpdate[T]*)(implicit
      ec: ExecutionContext
  ): ScheduledAgent[T] = {
    new ScheduledAgent(scheduleSystem.get, initialValue, updates: _*)
  }

  def init(): Unit = {
    scheduleSystem = Some(ActorSystem("scheduled-agent"))
  }

  def shutdown(): Unit = {
    scheduleSystem.foreach(_.terminate())
  }
}

trait ScheduledAgentUpdate[T] {
  def block: T => T
}

case class Update[T](block: T => T) extends ScheduledAgentUpdate[T]
object Update {
  def apply[T](block: => Unit): Update[T] = {
    Update { t =>
      block
      t
    }
  }
}

case class PeriodicScheduledAgentUpdate[T](
    block: T => T,
    initialDelay: FiniteDuration,
    frequency: FiniteDuration
) extends ScheduledAgentUpdate[T]

object PeriodicScheduledAgentUpdate {
  def apply[T](initialDelay: FiniteDuration, frequency: FiniteDuration)(
      block: T => T
  ): PeriodicScheduledAgentUpdate[T] =
    PeriodicScheduledAgentUpdate(block, initialDelay, frequency)
}

case class DailyScheduledAgentUpdate[T](block: T => T, timeOfDay: LocalTime)
    extends ScheduledAgentUpdate[T] {
  def timeToNextExecution: FiniteDuration = {
    val executionToday = new LocalDate().toDateTime(timeOfDay)

    val interval =
      if (executionToday.isAfterNow)
        // today if before the time of day
        new Interval(new DateTime(), executionToday)
      else {
        // tomorrow if after the time of day
        val executionTomorrow =
          new LocalDate().plusDays(1).toDateTime(timeOfDay)
        new Interval(new DateTime(), executionTomorrow)
      }
    interval.toDurationMillis milliseconds
  }
}
object DailyScheduledAgentUpdate {
  def apply[T](timeOfDay: LocalTime)(
      block: T => T
  ): DailyScheduledAgentUpdate[T] = DailyScheduledAgentUpdate(block, timeOfDay)
}

class ScheduledAgent[T](
    system: ActorSystem,
    initialValue: T,
    updates: ScheduledAgentUpdate[T]*
)(implicit ec: ExecutionContext)
    extends Logging {

  val agent: Agent[T] = Agent[T](initialValue)(ec)

  val cancellablesAgent: Agent[Map[ScheduledAgentUpdate[T], Cancellable]] =
    Agent[Map[ScheduledAgentUpdate[T], Cancellable]] {
      updates.map { update =>
        val cancellable = update match {
          case periodic: PeriodicScheduledAgentUpdate[_] =>
            system.scheduler.scheduleAtFixedRate(
              periodic.initialDelay,
              periodic.frequency
            ) { () =>
              queueUpdate(periodic)
            }
          case daily: DailyScheduledAgentUpdate[_] =>
            scheduleNext(daily.asInstanceOf[DailyScheduledAgentUpdate[T]])
        }
        update -> cancellable
      }.toMap
    }(ec)

  def scheduleNext(update: DailyScheduledAgentUpdate[T]): Cancellable = {
    val delay = update.timeToNextExecution
    log.debug("Scheduling %s to run next in %s".format(update, delay))
    system.scheduler.scheduleOnce(delay) {
      queueUpdate(update)
      val newCancellable = scheduleNext(update)
      cancellablesAgent.send(_ + (update -> newCancellable))
    }
  }

  def queueUpdate(update: ScheduledAgentUpdate[T]): Unit = {
    agent sendOff { lastValue =>
      try {
        update.block(lastValue)
      } catch {
        case t: Throwable =>
          log.warn("Failed to update on schedule", t)
          lastValue
      }
    }
  }

  def get(): T = agent()
  def apply(): T = get()

  def shutdown(): Unit = {
    cancellablesAgent.send { cancellables =>
      cancellables.values.foreach(_.cancel())
      Map.empty
    }
  }

}
