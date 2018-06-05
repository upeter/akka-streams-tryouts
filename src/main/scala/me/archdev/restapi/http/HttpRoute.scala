package me.archdev.restapi.http

import java.time.LocalTime

import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import me.archdev.restapi.core.profiles.{JdbcUserProfileStorage, UserProfileService}
import me.archdev.restapi.http.routes.{AuthRoute, ProfileRoute}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import me.archdev.restapi.core.auth.AuthService
import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.sse.ServerSentEvent

import scala.concurrent.duration._
import java.time.LocalTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage._
import me.archdev.restapi.core.{UserId, UserProfile}
import me.archdev.restapi.utils.Config
import me.archdev.restapi.utils.db.DatabaseConnector
import slick.basic.DatabasePublisher
import slick.jdbc.{ResultSetConcurrency, ResultSetType}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


class HttpRoute(
                 userProfileService: UserProfileService,
                 authService: AuthService,
                 secretKey: String
               )(implicit system: ActorSystem) {

  implicit val executionContext = system.dispatcher
  private val usersRouter = new ProfileRoute(secretKey, userProfileService)
  private val authRouter = new AuthRoute(authService)

  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._


  class NumbersSource extends GraphStage[SourceShape[Int]] {
    val out: Outlet[Int] = Outlet("NumbersSource")
    override val shape: SourceShape[Int] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new TimerGraphStageLogic(shape) {
        // All state MUST be inside the GraphStageLogic,
        // never inside the enclosing GraphStage.
        // This state is safe to access and modify from all the
        // callbacks that are provided by GraphStageLogic and the
        // registered handlers.
        private var counter = 1
        private var blocked = false


        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (counter == 100)
              blocked = true
            if (!blocked) {
              push(out, counter)
            } else {
              scheduleOnce("TestSingleTimer", 1500.millis)
            }
            counter += 1
          }
        })

        override protected def onTimer(timerKey: Any): Unit = {
          blocked = false
          push(out, counter)
        }
      }

  }

  class UnfoldAsync[S, E](s: S, f: S ⇒ Future[Option[(S, E)]])(implicit ec: ExecutionContext) extends GraphStage[SourceShape[E]] {

    val out: Outlet[E] = Outlet("UnfoldAsync")

    override val shape: SourceShape[E] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      new GraphStageLogic(shape) {
        private var state = s

        private[this] val asyncHandler = getAsyncCallback[Try[Option[(S, E)]]] {
          case Failure(ex) => fail(out, ex)
          case Success(None) => complete(out)
          case Success(Some((newS, elem))) => {
            push(out, elem)
            state = newS
          }
        }

        setHandler(out, new OutHandler {
          override def onPull(): Unit = Try(f(state)) match {
            case Failure(err) => fail(out, err)
            case Success(fut) => fut.onComplete(asyncHandler.invoke)(ec)
          }
        })
      }
    }
  }

//
//  Source.repeat(NotUsed).flatMapConcat { _ =>
//    val items = service.getItems
//    if (items.isEmpty)
//      Source.tick(30.seconds, 1.second, Seq.empty[Item]).take(1).drop(1) // <-- more than likely easier ways to do this, but it is late here.
//    else
//      Source.single(items)
//  }
//  def customers: DatabasePublisher[Customer] =
//    db.stream(
//      customerQuery
//        .result
//        .withStatementParameters(
//          rsType = ResultSetType.ForwardOnly,
//          rsConcurrency = ResultSetConcurrency.ReadOnly,
//          fetchSize = 10000)
//        .transactionally)



  def infiniteFetch(offset: UserId = -1): Future[Seq[UserProfile]] = {
    userProfileService.getProfileGreaterThan(offset).flatMap(profiles =>
      if (profiles.isEmpty) {
        val promise = Promise[Seq[UserProfile]]
        system.scheduler.scheduleOnce(FiniteDuration(2, TimeUnit.SECONDS)) {
          userProfileService.getProfileGreaterThan(offset).map { profiles =>
            if (!profiles.isEmpty)
              promise.trySuccess(profiles)
            else infiniteFetch(offset)
          }
        }
        promise.future
      } else {
        Future.successful(profiles)

      })
  }

  class ProfilesSource(var offset: Int = 0) extends GraphStage[SourceShape[UserProfile]] {
//    class ProfilesSource(var offset: Int = 0) extends GraphStage[FlowShape[Unit, UserProfile]] {


    val out: Outlet[UserProfile] = Outlet("ProfilesSourceOut")
    val in: Inlet[Unit] = Inlet("ProfilesSourceIn")
    override val shape: SourceShape[UserProfile] = SourceShape(out)
    //override val shape: FlowShape[Unit, UserProfile] = FlowShape(in ,out)


    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =

      new TimerGraphStageLogic(shape) {
        var buffer: Seq[UserProfile] = Seq.empty
        private[this] val asyncHandler = getAsyncCallback[Try[Seq[UserProfile]]] {
          case Failure(ex) =>
            println(s"failure-> ${ex.getMessage}")
            fail(out, ex)
          case Success(profileChunk) if profileChunk.isEmpty => {
            println("EMPTY chunk-> poll")
            scheduleOnce("ProfilesSource", 2000.millis)
          }
          //case Success(None) => complete(out)
          case Success(profileChunk) => {
            println(s"chunk with $profileChunk itmes received -> update buffer")
            buffer = buffer ++ profileChunk
            val head :: tail = buffer
            push(out, head)
            buffer = tail
            offset = profileChunk.maxBy(_.id).id.toInt
            println(s"new offset is $offset")
          }
        }


        private[this] def fetchUserProfiles() = {
          val future = userProfileService.getProfileGreaterThan(offset)
          future.onComplete { result =>
            asyncHandler.invoke(result)
          }

        }


//        setHandler(in, new InHandler {
//          override def onPush(): Unit = scheduleOnce("DelayedEcho", pause)
//        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (buffer.isEmpty) {
              fetchUserProfiles()
            } else {
              push(out, buffer.head)
              buffer = buffer.tail
            }
          }



          override def onDownstreamFinish(): Unit = {
            // fill in the promise that is used as the materialized value
            println("finished!!!!!!!!!!!")
            super.onDownstreamFinish()
          }

        })



        override protected def onTimer(timerKey: Any): Unit = {
          if(isAvailable(out)) {
            println("is available")
          fetchUserProfiles()
          } else println("not available")
        }
      }


  }

  def toImmutable[A](elements: Iterable[A]) =
    new scala.collection.immutable.Iterable[A] {
      override def iterator: Iterator[A] = elements.toIterator
    }

  //  Source.fromFuture()

  //val sourceGraph: Graph[SourceShape[Int], NotUsed] = new NumbersSource

  // Create a Source from the Graph to access the DSL
  //val mySource: Source[Int, NotUsed] = Source.fromGraph(sourceGraph)
  val route: Route =
  cors() {
    pathPrefix("v1") {



      path("dbstream3") {
        parameter('offset.as[UserId].?) { offset =>
          complete {
            Source.unfoldAsync(offset.getOrElse(-1)) { lastOffset =>
              userProfileService.getProfileGreaterThan(lastOffset).map(profiles =>
                //Some((profiles.maxBy(_.id).id, profiles)))
                Some(profiles.foldLeft((lastOffset, Seq.empty[UserProfile])) {
                  case ((maxOffset, results), profile) => maxOffset.max(profile.id) ->  (results :+ profile)
                }))
            }
              // Throttle on empty sequences, don't throttle (or throttle less) on non-empty results
              .log("pre-throttle")
              .throttle(1, 1000.milli, 1, s => if (s.isEmpty) 1 else 0, ThrottleMode.Shaping)
              .mapConcat(_.toList)
              .map(data => ServerSentEvent(data.toString, ISO_LOCAL_TIME.format(LocalTime.now())))
              .keepAlive(1.second, () => ServerSentEvent.heartbeat)
          }
        }
      } ~
      path("dbstream2") {
        parameter('offset.as[UserId].?) { offset =>
          complete {
            Source.unfoldAsync(offset.getOrElse(-1)) { fromOffset =>
              infiniteFetch(fromOffset).flatMap(profiles =>
                Future.successful(Some((profiles.maxBy(_.id).id, profiles)))
              )
            }
              .mapConcat(toImmutable)
              .map(data => ServerSentEvent(data.toString, ISO_LOCAL_TIME.format(LocalTime.now())))
          }
        }
      } ~
        path("dbstream") {
          parameter('offset.as[Int].?) { offset =>
            complete {
              Source.fromGraph(new ProfilesSource(offset.getOrElse(0)))
                //.keepAlive(10.second, () => ": \n\n")
                .map(data => ServerSentEvent(data = data.toString, `type` = "profile", id = ISO_LOCAL_TIME.format(LocalTime.now())))
                .keepAlive(10.second, () => ServerSentEvent.heartbeat)


            }
          }
        } ~
        path("stream") {
          complete {
            Source.fromGraph(new NumbersSource)
              .map(i => s"$i ${LocalTime.now()}")
              .map(data => ServerSentEvent(data, ISO_LOCAL_TIME.format(LocalTime.now())))
          }
        } ~
        path("events") {
          get {
            complete {
              Source
                .tick(2.seconds, 2.seconds, NotUsed)
                .map(_ => LocalTime.now())
                .map(time => ServerSentEvent(ISO_LOCAL_TIME.format(time)))
              //.keepAlive(1.second, () => ServerSentEvent.heartbeat)
            }
          }
        } ~
        usersRouter.route ~
        authRouter.route
    } ~
      pathPrefix("healthcheck") {
        get {
          complete("OK")
        }
      }
  }

}

trait UserProfileServiceSupport {
  implicit val executionContext: ExecutionContext
  val config = Config.load()

  val databaseConnector = new DatabaseConnector(
    config.database.jdbcUrl,
    config.database.username,
    config.database.password
  )

  val userProfileStorage = new JdbcUserProfileStorage(databaseConnector)
  val userProfileService = new UserProfileService(userProfileStorage)
}

