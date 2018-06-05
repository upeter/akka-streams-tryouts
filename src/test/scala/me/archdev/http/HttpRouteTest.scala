package me.archdev.http

import java.time.LocalTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME

import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Route
import akka.stream.Attributes.LogLevels
import akka.stream._
import akka.stream.scaladsl.{Keep, Merge, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import me.archdev.BaseServiceTest
import me.archdev.restapi.core.UserProfile
import me.archdev.restapi.core.auth.AuthService
import me.archdev.restapi.core.profiles.UserProfileService
import me.archdev.restapi.http.HttpRoute

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class HttpRouteTest extends BaseServiceTest {

  "HttpRoute" when {

    "GET /healthcheck" should {

      "return 200 OK" in new Context {
        Get("/healthcheck") ~> httpRoute ~> check {
          responseAs[String] shouldBe "OK"
          status.intValue() shouldBe 200
        }
      }

    }


    case object SourceA
    case object SourceB

    val ticksSource = Source
      .tick(0 seconds, 400 millis, SourceA)

    var dbTable: Seq[(Long, String)] = Seq.empty

    def query(offset: Long): Future[Seq[(Long, String)]] = Future.successful(dbTable.takeWhile(_._1 > offset))

    def streamingSource(offset: Long) = Source.unfoldAsync(offset) { lastOffset =>
      query(lastOffset).map(result =>
        Some(result.foldLeft((lastOffset, Seq.empty[String])) {
          case ((maxOffset, seq), (newOffset, s)) => (maxOffset.max(newOffset), s +: seq)
        }))
    }


    //      // Throttle on empty sequences, don't throttle (or throttle less) on non-empty results
    //      .log("pre-throttle")
    //      .throttle(1, 1000.milli, 1, s => if (s.isEmpty) 1 else 0, ThrottleMode.Shaping)
    //      .mapConcat(_.toList)
    //      .map(data => ServerSentEvent(data.toString, ISO_LOCAL_TIME.format(LocalTime.now())))
    //      .keepAlive(1.second, () => ServerSentEvent.heartbeat)

    class MaxStage extends GraphStage[FlowShape[Int, Int]] {
      val in: Inlet[Int] = Inlet("MaxStage.in")
      val out: Outlet[Int] = Outlet("MaxStage.out")
      override val shape: FlowShape[Int, Int] = FlowShape(in, out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) {
          var maxValue = Int.MinValue
          var maxPushed = Int.MinValue

          setHandler(in, new InHandler {
            override def onPush(): Unit = {
              maxValue = math.max(maxValue, grab(in))
              if (isAvailable(out) && maxValue > maxPushed)
                pushMaxValue()
              pull(in)
            }

            override def onUpstreamFinish(): Unit = {
              if (maxValue > maxPushed)
                emit(out, maxValue)
              completeStage()
            }

          })

          setHandler(out, new OutHandler {
            override def onPull(): Unit = {
              if (maxValue > maxPushed)
                pushMaxValue()
              else if (!hasBeenPulled(in))
                pull(in)
            }
          })

          def pushMaxValue(): Unit = {
            maxPushed = maxValue
            push(out, maxPushed)
          }
        }
    }



    "Test" should {

      "Flow Graph in test" in {
        val ticksSource = Source.fromIterator(() => Iterator.continually(Random.nextInt(10000)))
          .throttle(1, 200.milli, 1, ThrottleMode.Shaping)
        ticksSource.via(new MaxStage).runWith(Sink.foreach(println))

        Thread.sleep(10000)
      }
      "Flow Graph" in {
        val (upstream, downstream) =
          TestSource.probe[Int]
            .via(new MaxStage)
            .toMat(TestSink.probe)(Keep.both)
            .run()

        // send element 10 from upstream
        upstream.sendNext(10)
        downstream.request(1)

        // and it is received by downstream
        downstream.expectNext(10)
        downstream.request(1)
        upstream.sendNext(9)
        upstream.sendNext(8)

        // no new max yet since 9 and 8 are < 10
        downstream.expectNoMsg(200.millis)
        upstream.sendNext(11)

        // new max emitted by the stage
        downstream.expectNext(11)
        upstream.sendNext(17)

        // end the stream
        upstream.sendComplete()

        // no request from downstream yet
        downstream.expectNoMessage(200.millis)
        downstream.request(1)

        // get the final element
        downstream.expectNext(17)
        downstream.expectComplete()

      }
      "work" in {
        implicit val adapter = Logging(system, "customLogger")
        val probe: TestSubscriber.Probe[String] =
          streamingSource(-1)
            .withAttributes(Attributes(LogLevels(
              onElement = Logging.InfoLevel,
              onFailure = Logging.ErrorLevel,
              onFinish = Logging.InfoLevel
            )))
            .map { elem ⇒ println(elem); elem }
            .log("elements-produced").mapConcat(_.toList).runWith(TestSink.probe[String])

        probe.ensureSubscription()

        // Record shouldn't appear (we assume having seen offset 0 already, and no demand)
        dbTable = Seq((0L, "first"))
        Thread.sleep(500)

        // "second" record should appear, but no demand yet for "third" (and thus no need for polling in general)
        dbTable = Seq((2L, "third"), (1L, "second"), (0L, "first"))
        probe.requestNext() should be("first")
        Thread.sleep(500)

        // Now there is demand for "third" and more! So we expect "third" and from them on periodic queries for more data
        probe.request(2)
        Thread.sleep(500)
      }
      "another" in {
        dbTable = Seq((2L, "third"), (1L, "second"), (0L, "first"))

        val dbSource = streamingSource(0)
          .map { elem ⇒ println(s"fetch $elem"); elem }
          .throttle(1, 200.milli, 1, s => if (s.isEmpty) 1 else 0, ThrottleMode.Shaping)
          .mapConcat(_.toList)


        val merged = Source.combine(ticksSource, dbSource)(Merge(_))

        merged.runWith(Sink.foreach(println))


        Thread.sleep(500)
        dbTable = Seq((3L, "fourth"), (4L, "fifth"))

        Thread.sleep(500)
        dbTable = Seq((5L, "sixth"), (6L, "seventh"))

        Thread.sleep(1000)

      }
    }

  }

  trait Context {
    val secretKey = "secret"
    val userProfileService: UserProfileService = mock[UserProfileService]
    val authService: AuthService = mock[AuthService]

    val httpRoute: Route = new HttpRoute(userProfileService, authService, secretKey).route
  }

}
