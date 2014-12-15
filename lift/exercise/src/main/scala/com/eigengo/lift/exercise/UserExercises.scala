package com.eigengo.lift.exercise

import akka.actor._
import akka.contrib.pattern.ShardRegion
import akka.persistence.{PersistentActor, SnapshotOffer}
import cakesolutions.AutoPassivation
import com.eigengo.lift.common.UserId
import com.eigengo.lift.exercise.AccelerometerData._
import com.eigengo.lift.exercise.ExerciseClassifier.{Classify, FullyClassifiedExercise, NoExercise, UnclassifiedExercise}
import com.eigengo.lift.exercise.UserExercises._
import com.eigengo.lift.exercise.UserExercisesView._
import com.eigengo.lift.notification.NotificationProtocol.{MobileDestination, PushMessage, WatchDestination}
import com.typesafe.config.ConfigFactory
import scodec.bits.BitVector
import scala.collection.JavaConversions._
import scala.language.postfixOps
import scalaz.\/

/**
 * User + list of exercises companion
 */
object UserExercises {

  /** The shard name */
  val shardName = "user-exercises"
  /** The sessionProps to create the actor on a node */
  def props(notification: ActorRef, exerciseClassifiers: ActorRef) = Props(classOf[UserExercises], notification, exerciseClassifiers)

  def shardProps(notification: ActorRef, exerciseClassifiers: ActorRef): Option[Props] = {
    val roles = ConfigFactory.load().getStringList("akka.cluster.roles")
    roles.find("lift-exercise" ==).map(_ => props(notification, exerciseClassifiers))
  }

  /**
   * Receive exercise data for the given ``userId`` and the ``bits`` that may represent the exercises performed
   * @param userId the user identity
   * @param sessionId the sessionProps identity
   * @param bits the submitted bits
   */
  case class UserExerciseDataProcess(userId: UserId, sessionId: SessionId, bits: BitVector)

  /**
   * Process exercise data for the given sessionProps
   * @param sessionId the sessionProps identifier
   * @param bits the exercise data bits
   */
  private case class ExerciseDataProcess(sessionId: SessionId, bits: BitVector)

  /**
   * Starts the user exercise sessionProps
   * @param userId the user identity
   * @param sessionProps the sessionProps details
   */
  case class UserExerciseSessionStart(userId: UserId, sessionProps: SessionProps)

  /**
   * Ends the user exercise sessionProps
   * @param userId the user identity
   * @param sessionId the generated sessionProps identity
   */
  case class UserExerciseSessionEnd(userId: UserId, sessionId: SessionId)

  /**
   * The sessionProps has started
   * @param sessionProps the sessionProps identity
   */
  private case class ExerciseSessionStart(sessionProps: SessionProps)

  /**
   * The sessionProps has ended
   * @param sessionId the sessionProps identity
   */
  private case class ExerciseSessionEnd(sessionId: SessionId)

  /**
   * Accelerometer data for the given sessionProps
   * @param sessionId the sessionProps identity
   * @param data the data
   */
  private case class ExerciseSessionData(sessionId: SessionId, data: AccelerometerData)

  /**
   * Too much rest message that is sent to us if the user is being lazy
   */
  private case object TooMuchRest

  /**
   * Extracts the identity of the shard from the messages sent to the coordinator. We have per-user shard,
   * so our identity is ``userId.toString``
   */
  val idExtractor: ShardRegion.IdExtractor = {
    case UserExerciseSessionStart(userId, session)        ⇒ (userId.toString, ExerciseSessionStart(session))
    case UserExerciseSessionEnd(userId, sessionId)        ⇒ (userId.toString, ExerciseSessionEnd(sessionId))
    case UserExerciseDataProcess(userId, sessionId, data) ⇒ (userId.toString, ExerciseDataProcess(sessionId, data))
  }

  /**
   * Resolves the shard name from the incoming message.
   */
  val shardResolver: ShardRegion.ShardResolver = {
    case UserExerciseSessionStart(userId, _)   ⇒ s"${userId.hashCode() % 10}"
    case UserExerciseSessionEnd(userId, _)     ⇒ s"${userId.hashCode() % 10}"
    case UserExerciseDataProcess(userId, _, _) ⇒ s"${userId.hashCode() % 10}"
  }

}

/**
 * Models each user's exercises as its state, which is updated upon receiving and classifying the
 * ``AccelerometerData``. It also provides the query for the current state.
 */
class UserExercises(notification: ActorRef, exerciseClasssifiers: ActorRef)
  extends PersistentActor with ActorLogging with AutoPassivation {
  import scala.concurrent.duration._

  private val userId = UserId(self.path.name)
  // minimum confidence
  private val confidenceThreshold = 0.5
  // how long until we stop processing
  context.setReceiveTimeout(360.seconds)
  // our unique persistenceId; the self.path.name is provided by ``UserExercises.idExtractor``,
  // hence, self.path.name is the String representation of the userId UUID.
  override val persistenceId: String = s"user-exercises-${self.path.name}"

  // chop-chop timer cancellable
  private var tooMuchRestCancellable: Option[Cancellable] = None

  import context.dispatcher

  private def validateData(result: (BitVector, List[AccelerometerData])): \/[String, AccelerometerData] = result match {
    case (BitVector.empty, Nil)    ⇒ \/.left("Empty")
    case (BitVector.empty, h :: t) ⇒
      if (t.forall(_.samplingRate == h.samplingRate)) {
        \/.right(t.foldLeft(h)((res, ad) ⇒ ad.copy(values = ad.values ++ res.values)))
      } else {
        \/.left("Unmatched sampling rates")
      }
    case (_, _)                    ⇒ \/.left("Undecoded input")
  }

  private def cancellingTooMuchRest(r: Receive): Receive = {
    case x if r.isDefinedAt(x) ⇒
      tooMuchRestCancellable.foreach(_.cancel())
      r(x)
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, SessionStartedEvt(sessionId, sessionProps)) ⇒
      context.become(exercising(sessionId, sessionProps))
  }

  private def exercising(id: SessionId, sessionProps: SessionProps): Receive = withPassivation(cancellingTooMuchRest {
    case ExerciseSessionStart(newSessionProps) ⇒
      val newId = SessionId.randomId()
      persist(Seq(SessionEndedEvt(id), SessionStartedEvt(newId, newSessionProps))) { x ⇒
        log.warning("ExerciseSessionStart: exercising -> exercising. Implicitly ending running session and starting a new one.")
        val (_::newSession) = x
        saveSnapshot(newSession)
        sender() ! \/.right(newId)
        context.become(exercising(newId, newSessionProps))
      }

    case ExerciseDataProcess(`id`, bits) ⇒
      log.info("ExerciseDataProcess: exercising -> exercising.")
      val result = decodeAll(bits, Nil)
      validateData(result).fold(
        { err ⇒ sender() ! \/.left(err)},
        { evt ⇒ exerciseClasssifiers ! Classify(sessionProps, evt); sender() ! \/.right(()) }
      )

    case FullyClassifiedExercise(metadata, confidence, name, intensity) if confidence > confidenceThreshold ⇒
      log.info("FullyClassifiedExercise: exercising -> exercising.")
      persist(ExerciseEvt(id, metadata, Exercise(name, intensity))) { evt ⇒
        tooMuchRestCancellable = Some(context.system.scheduler.scheduleOnce(sessionProps.restDuration, self, TooMuchRest))
        intensity.foreach { i ⇒
          if (i << sessionProps.intendedIntensity) notification ! PushMessage(userId, "Harder!", None, Some("default"), Seq(MobileDestination, WatchDestination))
          if (i >> sessionProps.intendedIntensity) notification ! PushMessage(userId, "Easier!", None, Some("default"), Seq(MobileDestination, WatchDestination))
        }
      }

    case UnclassifiedExercise(_) ⇒
      // Maybe notify the user?
      tooMuchRestCancellable = Some(context.system.scheduler.scheduleOnce(sessionProps.restDuration, self, TooMuchRest))

    case NoExercise(metadata) ⇒
      log.info("NoExercise: exercising -> exercising.")
      persist(NoExerciseEvt(id, metadata)) { evt ⇒
        tooMuchRestCancellable = Some(context.system.scheduler.scheduleOnce(sessionProps.restDuration, self, TooMuchRest))
      }

    case TooMuchRest ⇒
      log.info("NoExercise: exercising -> exercising.")
      persist(TooMuchRestEvt(id)) { evt ⇒
        notification ! PushMessage(userId, "Chop chop!", None, Some("default"), Seq(MobileDestination, WatchDestination))
      }

    case ExerciseSessionEnd(`id`) ⇒
      log.info("ExerciseSessionEnd: exercising -> not exercising.")
      persist(SessionEndedEvt(id)) { evt ⇒
        saveSnapshot(evt)
        context.become(notExercising)
        sender() ! \/.right(())
      }
  })

  private def notExercising: Receive = withPassivation {
    case ExerciseSessionStart(sessionProps) ⇒
      persist(SessionStartedEvt(SessionId.randomId(), sessionProps)) { evt ⇒
        saveSnapshot(evt)
        sender() ! \/.right(evt.sessionId)
        context.become(exercising(evt.sessionId, sessionProps))
      }
  }

  override def receiveCommand: Receive = notExercising

}
