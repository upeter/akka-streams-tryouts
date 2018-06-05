package me.archdev.restapi

package object core {

  type UserId = Int
  type AuthToken = String

  final case class AuthTokenContent(userId: UserId)

  final case class AuthData(id: UserId, username: String, email: String, password: String) {
    require(username.nonEmpty, "username.empty")
    require(email.nonEmpty, "email.empty")
    require(password.nonEmpty, "password.empty")
  }

  final case class UserProfile(id: UserId, firstName: String, lastName: String) {
    require(firstName.nonEmpty, "firstName.empty")
    require(lastName.nonEmpty, "lastName.empty")
  }

  final case class UserProfileUpdate(firstName: Option[String] = None, lastName: Option[String] = None) {
    def merge(profile: UserProfile): UserProfile = {
      UserProfile(profile.id, firstName.getOrElse(profile.firstName), lastName.getOrElse(profile.lastName))
    }
  }

}
