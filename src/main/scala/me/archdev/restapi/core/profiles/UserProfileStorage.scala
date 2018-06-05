package me.archdev.restapi.core.profiles

import me.archdev.restapi.core.{UserId, UserProfile}
import me.archdev.restapi.utils.db.DatabaseConnector

import scala.concurrent.{ExecutionContext, Future}

sealed trait UserProfileStorage {

  def getProfiles(): Future[Seq[UserProfile]]

  def getProfile(id: UserId): Future[Option[UserProfile]]

  def saveProfile(profile: UserProfile): Future[UserProfile]

  def getProfileGreaterThan(offset:UserId, chunkSize:Int = 10):Future[Seq[UserProfile]]

}

class JdbcUserProfileStorage(
  val databaseConnector: DatabaseConnector
)(implicit executionContext: ExecutionContext) extends UserProfileTable with UserProfileStorage {

  import databaseConnector._
  import databaseConnector.profile.api._

  def getProfiles(): Future[Seq[UserProfile]] = db.run(profiles.result)

  def getProfile(id: UserId): Future[Option[UserProfile]] = db.run(profiles.filter(_.id === id).result.headOption)

  def saveProfile(profile: UserProfile): Future[UserProfile] =
    db.run(profiles.insertOrUpdate(profile)).map(_ => profile)

  def deleteAllProfiles():Future[_] = db.run(profiles.delete)

  override def getProfileGreaterThan(offset: UserId, chunkSize: Int): Future[Seq[UserProfile]] = db.run(profiles.filter(_.id > offset).sortBy(_.id).take(chunkSize).result)
}

class InMemoryUserProfileStorage extends UserProfileStorage {

  private var state: Seq[UserProfile] = Nil

  override def getProfiles(): Future[Seq[UserProfile]] =
    Future.successful(state)

  override def getProfile(id: UserId): Future[Option[UserProfile]] =
    Future.successful(state.find(_.id == id))

  override def saveProfile(profile: UserProfile): Future[UserProfile] =
    Future.successful {
      state = state.filterNot(_.id == profile.id)
      state = state :+ profile
      profile
    }

  override def getProfileGreaterThan(offset: UserId, chunkSize: Int): Future[Seq[UserProfile]] = ???
}