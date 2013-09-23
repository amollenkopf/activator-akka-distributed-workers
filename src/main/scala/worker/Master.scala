package worker

import scala.concurrent.duration._
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.cluster.Cluster
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator.Put
import akka.persistence.Persistent
import akka.persistence.Processor

object Master {

  val ResultsTopic = "results"

  def props(workTimeout: FiniteDuration): Props = Props(classOf[Master], workTimeout)

  case class Ack(workId: String)

  private sealed trait WorkerStatus
  private case object Idle extends WorkerStatus
  private case class Busy(workId: String, deadline: Deadline) extends WorkerStatus
  private case class WorkerState(ref: ActorRef, status: WorkerStatus)

  private case object CleanupTick

}

class Master(workTimeout: FiniteDuration) extends Actor with Processor with ActorLogging {
  import Master._
  import WorkState._

  val mediator = DistributedPubSubExtension(context.system).mediator

  // processorId must include cluster role to support multiple masters
  override def processorId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) ⇒ role + "-master"
    case None       ⇒ "master"
  }

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
    SupervisorStrategy.defaultDecider
  }

  mediator ! Put(self)

  // workers state is not event sourced
  private var workers = Map[String, WorkerState]()

  // workState is event sourced
  private var workState = WorkState.empty

  import context.dispatcher
  val cleanupTask = context.system.scheduler.schedule(workTimeout / 2, workTimeout / 2,
    self, CleanupTick)

  override def postStop(): Unit = cleanupTask.cancel()

  def receive = {
    case Persistent(event: WorkDomainEvent, sequenceNr) if recoveryRunning ⇒
      // only update current state by applying the event, no side effects
      workState = workState.updated(event)
      log.info("Replayed {} {}", sequenceNr, event.getClass.getSimpleName)

    case MasterWorkerProtocol.RegisterWorker(workerId) ⇒
      if (workers.contains(workerId)) {
        workers += (workerId -> workers(workerId).copy(ref = sender))
      } else {
        log.info("Worker registered: {}", workerId)
        workers += (workerId -> WorkerState(sender, status = Idle))
        if (workState.hasWork)
          sender ! MasterWorkerProtocol.WorkIsReady
      }

    case MasterWorkerProtocol.WorkerRequestsWork(workerId) ⇒
      if (workState.hasWork) {
        workers.get(workerId) match {
          case Some(s @ WorkerState(_, Idle)) ⇒
            val work = workState.nextWork
            handleDomainEvent(WorkStarted(work.workId)) { event ⇒
              workState = workState.updated(event)
              log.info("Giving worker {} some work {}", workerId, work.workId)
              workers += (workerId -> s.copy(status = Busy(work.workId, Deadline.now + workTimeout)))
              sender ! work
            }
          case _ ⇒
        }
      }

    case MasterWorkerProtocol.WorkIsDone(workerId, workId, result) ⇒
      // idempotent
      if (workState.isDone(workId)) {
        // previous Ack was lost, confirm again that this is done
        sender ! MasterWorkerProtocol.Ack(workId)
      } else if (!workState.isInProgress(workId)) {
        log.info("Work {} not in progress, reported as done by worker {}", workId, workerId)
      } else {
        log.info("Work {} is done by worker {}", workId, workerId)
        changeWorkerToIdle(workerId, workId)
        handleDomainEvent(WorkCompleted(workId, result)) { event ⇒
          workState = workState.updated(event)
          mediator ! DistributedPubSubMediator.Publish(ResultsTopic, WorkResult(workId, result))
          // Ack back to original sender
          sender ! MasterWorkerProtocol.Ack(workId)
        }
      }

    case MasterWorkerProtocol.WorkFailed(workerId, workId) ⇒
      if (workState.isInProgress(workId)) {
        log.info("Work {} failed by worker {}", workId, workerId)
        changeWorkerToIdle(workerId, workId)
        handleDomainEvent(WorkerFailed(workId)) { event ⇒
          workState = workState.updated(event)
          notifyWorkers()
        }
      }

    case work: Work ⇒
      // idempotent
      if (workState.isAccepted(work.workId)) {
        sender ! Master.Ack(work.workId)
      } else {
        log.info("Accepted work: {}", work.workId)
        handleDomainEvent(WorkAccepted(work)) { event ⇒
          // Ack back to original sender
          sender ! Master.Ack(work.workId)
          workState = workState.updated(event)
          notifyWorkers()
        }
      }

    case CleanupTick ⇒
      for ((workerId, s @ WorkerState(_, Busy(workId, timeout))) ← workers) {
        if (timeout.isOverdue) {
          log.info("Work timed out: {}", workId)
          workers -= workerId
          handleDomainEvent(WorkerTimedOut(workId)) { event ⇒
            workState = workState.updated(event)
            notifyWorkers()
          }
        }
      }

  }

  /**
   * Handle the domain `event` by persisting it and thereafter proceed with the
   * `whenStored` thunk with the `event` passed as parameter.
   * Any messages received while the event is stored are stashed and processed
   * afterwards.
   */
  def handleDomainEvent[A](event: A)(whenStored: A ⇒ Unit): Unit = {
    val storingBehavior: Receive = {
      case Persistent(`event`, sequenceNr) ⇒
        log.info("Stored {} {}", sequenceNr, event.getClass.getSimpleName)
        // important to unbecome before whenStored to support become in the whenStored thunk
        context.unbecome()
        unstashAll()
        whenStored(event)
      case _ ⇒ stash()
    }
    // TODO will exception be thrown by Processor if storage fails (timeout)?
    context.become(storingBehavior, discardOld = false)
    self forward Persistent(event)
  }

  def notifyWorkers(): Unit =
    if (workState.hasWork) {
      // could pick a few random instead of all
      workers.foreach {
        case (_, WorkerState(ref, Idle)) ⇒ ref ! MasterWorkerProtocol.WorkIsReady
        case _                           ⇒ // busy
      }
    }

  def changeWorkerToIdle(workerId: String, workId: String): Unit =
    workers.get(workerId) match {
      case Some(s @ WorkerState(_, Busy(`workId`, _))) ⇒
        workers += (workerId -> s.copy(status = Idle))
      case _ ⇒
      // ok, might happen after standby recovery, worker state is not persisted
    }

  // TODO cleanup old workers
  // TODO cleanup old workIds, doneWorkIds

}