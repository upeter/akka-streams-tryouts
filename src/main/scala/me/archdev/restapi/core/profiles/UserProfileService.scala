package me.archdev.restapi.core.profiles

import java.time.LocalDateTime

import me.archdev.restapi.core.{UserId, UserProfile, UserProfileUpdate}
import me.archdev.restapi.utils.MonadTransformers._

import scala.concurrent.{ExecutionContext, Future}

class UserProfileService(
  userProfileStorage: UserProfileStorage
)(implicit executionContext: ExecutionContext) {

  def getProfiles(): Future[Seq[UserProfile]] =
    userProfileStorage.getProfiles()

  def getProfile(id: UserId): Future[Option[UserProfile]] =
    userProfileStorage.getProfile(id)

  def createProfile(profile: UserProfile): Future[UserProfile] =
    userProfileStorage.saveProfile(profile)

  def updateProfile(id: UserId, profileUpdate: UserProfileUpdate): Future[Option[UserProfile]] =
    userProfileStorage
      .getProfile(id)
      .mapT(profileUpdate.merge)
      .flatMapTOuter(userProfileStorage.saveProfile)

  def getProfileGreaterThan(offset:UserId, chunkSize:Int = 10):Future[Seq[UserProfile]] =
    userProfileStorage.getProfileGreaterThan(offset, chunkSize).map(p => {
      println(s"fetched from offset=[$offset] items=[${p.size}] at=[${LocalDateTime.now()}]")
      p
    })


}
