package com.eigengo.lift.profile

import akka.actor.{ActorLogging, Props}
import akka.contrib.pattern.ShardRegion
import akka.persistence.{PersistentActor, SnapshotOffer}
import cakesolutions.{AutoPassivation, Configuration}
import com.eigengo.lift.common.UserId
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConversions._
import scala.language.postfixOps
import scalaz.\/

object UserProfile {
  import com.eigengo.lift.profile.UserProfileProtocol._
  val shardName = "user-profile"
  val props = Props[UserProfile]

  def shardProps: Option[Props] = {
    val roles = ConfigFactory.load().getStringList("akka.cluster.roles")
    roles.find("lift-profile" ==).map(_ => props)
  }

  val idExtractor: ShardRegion.IdExtractor = {
    case UserRegistered(userId, account)       ⇒ (userId.toString, account)
    case UserGetAccount(userId)                ⇒ (userId.toString, GetAccount)
    case UserGetPublicProfile(userId)          ⇒ (userId.toString, GetPublicProfile)
    case UserPublicProfileSet(userId, profile) ⇒ (userId.toString, profile)
    case UserGetDevices(userId)                ⇒ (userId.toString, GetDevices)
    case UserDeviceSet(userId, device)         ⇒ (userId.toString, DeviceSet(device))
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case UserRegistered(userId, _)       ⇒ s"${userId.hashCode() % 10}"
    case UserGetAccount(userId)          ⇒ s"${userId.hashCode() % 10}"
    case UserGetDevices(userId)          ⇒ s"${userId.hashCode() % 10}"
    case UserDeviceSet(userId, _)        ⇒ s"${userId.hashCode() % 10}"
    case UserGetPublicProfile(userId)    ⇒ s"${userId.hashCode() % 10}"
    case UserPublicProfileSet(userId, _) ⇒ s"${userId.hashCode() % 10}"
  }

  /**
   * Sets the user's device
   * @param device the device
   */
  case class DeviceSet(device: UserDevice)

  /**
   * Registers a user
   * @param userId the user to be added
   * @param account the user account
   */
  case class UserRegistered(userId: UserId, account: Account)

  /**
   * Device has been set
   * @param userId the user for the device
   * @param device the device that has just been set
   */
  case class UserDeviceSet(userId: UserId, device: UserDevice)

  /**
   * Get account
   */
  private case object GetAccount

  /**
   * Get public profile
   */
  private case object GetPublicProfile

  /**
   * Get all devices
   */
  private case object GetDevices
}

/**
 * User profile domain
 */
class UserProfile extends PersistentActor with ActorLogging with AutoPassivation with Configuration {
  import com.eigengo.lift.profile.UserProfile._
  import com.eigengo.lift.profile.UserProfileProtocol._
  import scala.concurrent.duration._

  private var profile: Profile = _

  override def persistenceId: String = s"user-profile-${self.path.name}"

  // the shard lives for the specified timeout seconds before passivating
  context.setReceiveTimeout(10.seconds)

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, offeredSnapshot: Profile) ⇒
      log.info("SnapshotOffer: not registered -> registered.")
      profile = offeredSnapshot
      context.become(registered)
  }

  override def receiveCommand: Receive = notRegistered

  private def notRegistered: Receive = withPassivation {
    case cmd: Account ⇒
      persist(cmd) { acc ⇒
        log.info("Account: not registered -> registered.")
        profile = Profile(acc, UserDevices.empty, None)
        saveSnapshot(profile)
        context.become(registered)
      }
  }

  private def registered: Receive = withPassivation {
    case ds: DeviceSet ⇒ persist(ds) { evt ⇒
        log.info("DeviceSet: registered -> registered.")
        profile = profile.withDevice(evt.device)
        saveSnapshot(profile)
      }
    case pp: PublicProfile ⇒ persist(pp) { evt ⇒
        log.info("PublicProfile: registered -> registered.")
        profile = profile.withPublicProfile(evt)
        saveSnapshot(profile)
      }

    case GetPublicProfile ⇒
      log.info("GetPublicProfile: registered -> registered.")
      sender() ! profile.publicProfile
    case GetAccount ⇒
      log.info("GetAccount: registered -> registered.")
      sender() ! profile.account
    case GetDevices ⇒
      log.info("GetDevices: registered -> registered.")
      sender() ! profile.devices
  }

}
