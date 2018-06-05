package me.archdev.http

import akka.stream._
import akka.stream.scaladsl.Keep
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import me.archdev.BaseServiceTest

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class CombinedStreamTest extends BaseServiceTest {

  var dbTable: Seq[(Long, String)] = Seq.empty

  def query(offset: Long, chunkSize: Int = 2): Future[Seq[(Long, String)]] = Future.successful(dbTable.filter(_._1 > offset).take(chunkSize))

  case class RecordAddedNotification()

  "CombinedStreamTest" when {

    class DbFetchStage(var offset: Long = -1) extends GraphStage[FlowShape[RecordAddedNotification, String]] {
      val in: Inlet[RecordAddedNotification] = Inlet("DbFetchStage.in")
      val out: Outlet[String] = Outlet("DbFetchStage.out")
      override val shape: FlowShape[RecordAddedNotification, String] = FlowShape(in, out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) {
          var dbBuffer = Seq.empty[(Long, String)]

          /** async handler since DB queries are async */
          private[this] val asyncHandler = getAsyncCallback[Try[Seq[(Long, String)]]] {
            case Failure(ex) =>
              println(s"failure-> ${ex.getMessage}")
              fail(out, ex)
            case Success(chunk) if chunk.isEmpty => {
              println("fetched empty chunk -> do nothing")
            }
            case Success(chunk) => {
              println(s"fetched chunk with $chunk itmes -> update buffer")
              dbBuffer = dbBuffer ++ chunk
              offset = dbBuffer.maxBy(_._1)._1
              pushNext()
              if (!hasBeenPulled(in))
                pull(in)
              println(s"new offset is $offset")
            }
          }

          private[this] def pushNext(): Unit = {
            if (isAvailable(out)) {
              val head :: tail = dbBuffer
              push(out, head._2)
              dbBuffer = tail
            }
          }

          private[this] def fetchChunk() = {
            val future = query(offset)
            future.onComplete { result =>
              asyncHandler.invoke(result)
            }
          }

          /** receive record added notification from database */
          setHandler(in, new InHandler {
            override def onPush(): Unit = {
              //only fetch if buffer is empty
              if (dbBuffer.isEmpty) {
                println(s"notification received and buffer empty -> fetchChunk")
                fetchChunk()
              } else {
                println(s"notification received but buffer not empty -> pull next notification")
                pull(in)

              }
            }

            override def onUpstreamFinish(): Unit = {
              completeStage()
            }

          })

          /** receive demand from client */
          setHandler(out, new OutHandler {
            override def onPull(): Unit = {
              if (dbBuffer.isEmpty) {
                fetchChunk()
              } else {
                pushNext()
              }
            }
          })

        }
    }



    "DbFetchStage" should {

      "Combine hot and cold stream" in {
        val (upstream, downstream) =
          TestSource.probe[RecordAddedNotification]
            .via(new DbFetchStage(-1l))
            .toMat(TestSink.probe)(Keep.both)
            .run()

        //cold stream (return existing records from database)
        dbTable = 1l to 2l map (i => i -> i.toString)
        downstream.request(1)
        downstream.expectNext("1")
        downstream.request(1)
        downstream.expectNext("2")

        //hot stream (return newly added records from database)
        downstream.request(1) //nothing there yet...
        downstream.expectNoMessage(500.millis)

        //meanwhile new db elements are inserted
        var newRecords = 3l to 5l map (i => i -> i.toString)
        dbTable = dbTable ++ newRecords
        newRecords.foreach(_ => upstream.sendNext(RecordAddedNotification()))
        downstream.expectNext("3")

        downstream.request(1)
        downstream.expectNext("4")

        downstream.request(1)
        downstream.expectNext("5")

        // end the stream
        upstream.sendComplete()
        downstream.expectComplete()

      }

    }


  }
}
