package me.archdev.core.profiles

import java.time.LocalTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Deflate
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{KillSwitches, ThrottleMode}
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.{Framing, Keep, Sink, Source}
import akka.util.ByteString
import me.archdev.BaseServiceTest
import me.archdev.restapi.core.{UserId, UserProfile, UserProfileUpdate}
import me.archdev.restapi.core.profiles.{InMemoryUserProfileStorage, JdbcUserProfileStorage, UserProfileService}
import me.archdev.restapi.utils.Config
import me.archdev.restapi.utils.db.DatabaseConnector

import scala.concurrent.{Await, Future}
import scala.util.Random
import scala.concurrent.duration._

class UserProfileServiceTest extends BaseServiceTest {

  "UserProfileClient" when {
    "read stream all stored profiles" in {

      import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._ // That does the trick!
      //import system.dispatcher

      val future = Http()
        .singleRequest(Get("http://localhost:9000/v1/dbstream"))
        .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])

      future.foreach(_.runForeach(println))
      Await.result(future, Duration.Inf)


    }

    def consume(clients:Int) = {
      val futures = Future.sequence((1 to clients).map(i => {
        val eventSource = EventSource(Uri("http://localhost:9000/v1/dbstream"), Http().singleRequest(_), None)
        //        eventSource.throttle(1, i.milliseconds, 1, ThrottleMode.Shaping).takeWhile(_ => true).log(s"logging $i").runWith(Sink.foreach(println))
        eventSource.map(m => {
          //println(s"client=[$i]")
          m.copy(data = s"client=$i ${m.data}")
        }).takeWhile(_ => true).runWith(Sink.foreach(println))
      }))
      Await.result(futures, Duration.Inf)
    }

    "another" in {
      consume(4)
    }






  }

  "UserProfileService" when {


    "testdata" should {
      "return all stored profiles" in new Context {
        val ai = new AtomicInteger(1000)
        val future = userProfileStorage.deleteAllProfiles.flatMap { _ =>
          val sourceUnderTest = Source
            .tick(1.seconds, 1.seconds, NotUsed)
            .map(_ => ai.incrementAndGet())
            .map(time => {
              println(s"inserting $time")
              userProfileStorage.saveProfile(testProfile1.copy(id = time, firstName = s"Jack $time", lastName = s"Master $time"))
            })
            .take(1000)
          sourceUnderTest.runWith(Sink.seq)
        }
        Await.result(future, Duration.Inf)

      }
    }

    "getProfiles" should {

      "return all stored profiles" in new Context {
        awaitForResult(for {
          _ <- userProfileStorage.saveProfile(testProfile1)
          _ <- userProfileStorage.saveProfile(testProfile2)
          profiles <- userProfileService.getProfiles()
        } yield profiles shouldBe Seq(testProfile1, testProfile2))
      }

    }

    "getProfile" should {

      "return profile by id" in new Context {
        awaitForResult(for {
          _ <- userProfileStorage.saveProfile(testProfile1)
          _ <- userProfileStorage.saveProfile(testProfile2)
          maybeProfile <- userProfileService.getProfile(testProfileId1)
        } yield maybeProfile shouldBe Some(testProfile1))
      }

      "return None if profile not exists" in new Context {
        awaitForResult(for {
          _ <- userProfileStorage.saveProfile(testProfile1)
          _ <- userProfileStorage.saveProfile(testProfile2)
          maybeProfile <- userProfileService.getProfile(-23232323)
        } yield maybeProfile shouldBe None)
      }

    }

    "createProfile" should {

      "store profile" in new Context {
        awaitForResult(for {
          _ <- userProfileService.createProfile(testProfile1)
          maybeProfile <- userProfileStorage.getProfile(testProfileId1)
        } yield maybeProfile shouldBe Some(testProfile1))
      }

    }

    "updateProfile" should {

      "merge profile with partial update" in new Context {
        awaitForResult(for {
          _ <- userProfileService.createProfile(testProfile1)
          _ <- userProfileService.updateProfile(testProfileId1, UserProfileUpdate(Some("test"), Some("test")))
          maybeProfile <- userProfileStorage.getProfile(testProfileId1)
        } yield maybeProfile shouldBe Some(testProfile1.copy(firstName = "test", lastName = "test")))
      }

      "return None if profile is not exists" in new Context {
        awaitForResult(for {
          maybeProfile <- userProfileService.updateProfile(testProfileId1, UserProfileUpdate(Some("test"), Some("test")))
        } yield maybeProfile shouldBe None)
      }

    }

  }

  trait Context {
    val config = Config.load()

    val databaseConnector = new DatabaseConnector(
      config.database.jdbcUrl,
      config.database.username,
      config.database.password
    )

    val userProfileStorage = new JdbcUserProfileStorage(databaseConnector)

    //val userProfileStorage = new InMemoryUserProfileStorage()
    val userProfileService = new UserProfileService(userProfileStorage)

    val testProfileId1: UserId = Math.abs(Random.nextInt())
    val testProfileId2: UserId = Math.abs(Random.nextInt())
    val testProfile1: UserProfile = testProfile(testProfileId1)
    val testProfile2: UserProfile = testProfile(testProfileId2)

    def testProfile(id: UserId) = UserProfile(id, Random.nextString(10), Random.nextString(10))
  }

}
