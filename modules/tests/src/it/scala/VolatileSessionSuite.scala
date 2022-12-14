package dolphin.tests

import dolphin.VolatileSession
import dolphin.concurrent.ExpectedRevision
import dolphin.setting.ReadStreamSettings
import dolphin.setting.EventStoreSettings

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.Stream
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.noop.NoOpLogger
import weaver.IOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object VolatileSessionSuite extends IOSuite with Checkers {
  override def checkConfig: CheckConfig              = CheckConfig.default.copy(minimumSuccessful = 1)
  implicit val logger: SelfAwareStructuredLogger[IO] = NoOpLogger[IO]

  override type Res = VolatileSession[IO]

  override def sharedResource: Resource[IO, Res] = VolatileSession.resource(EventStoreSettings.Default)

  test("Should be able to create a session and write a dummy event to event store database") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      for {
        writeResult          <- session.appendToStream(
                                  streamAggregateId,
                                  s"test-event-".getBytes,
                                  Array.emptyByteArray,
                                  "test-data"
                                )
        nextExpectedRevision <- writeResult.getNextExpectedRevision
        commitUnsigned       <- writeResult.getCommitUnsigned
        prepareUnsigned      <- writeResult.getPrepareUnsigned
      } yield expect(nextExpectedRevision == ExpectedRevision.Exact(0)) and expect(commitUnsigned > 0L) and expect(
        prepareUnsigned > 0L
      )
    }
  }

  test("Should be able to create a session and read a dummy event from event store database") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      (for {
        _               <- Stream.eval(
                             session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
                           )
        readResult      <- Stream.eval(session.readStream(streamAggregateId, ReadStreamSettings.Default))
        readResultEvent <- readResult.getEventData.map(new String(_))
      } yield expect(readResultEvent == "test-event-")).compile.lastOrError
    }
  }

  test("Should throw an exception when trying to read a non-existing stream") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      session.readStream(streamAggregateId, ReadStreamSettings.Default).attempt.map {
        case Left(exception) =>
          expect(exception.getClass.getCanonicalName == "com.eventstore.dbclient.StreamNotFoundException")
        case Right(_)        => failure("Should have thrown an exception")
      }
    }
  }

  test("Should throw an exception when trying to read to a stream that was tombstoned") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      for {
        _         <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _         <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _         <- session.tombstoneStream(streamAggregateId)
        readError <- session.readStream(streamAggregateId, ReadStreamSettings.Default).attempt
      } yield readError match {
        case Left(exception) => expect(exception.getClass.getCanonicalName == "io.grpc.StatusRuntimeException")
        case Right(_)        => failure("Should have thrown an exception")
      }
    }
  }

  test("Should throw an exception when trying to write to a stream that was tombstoned") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      for {
        _          <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _          <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _          <- session.tombstoneStream(streamAggregateId)
        writeError <-
          session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data").attempt
      } yield writeError match {
        case Left(exception) => expect(exception.getClass.getCanonicalName == "io.grpc.StatusRuntimeException")
        case Right(_)        => failure("Should have thrown an exception")
      }
    }
  }

  test("Should be able to continue writing to deleted stream if scavenger process haven't been run") { session =>
    forall(generator.nonEmptyStringGen) { streamAggregateId =>
      for {
        _                    <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _                    <- session.appendToStream(streamAggregateId, s"test-event-".getBytes, Array.emptyByteArray, "test-data")
        _                    <- session.deleteStream(streamAggregateId)
        writeResult          <- session.appendToStream(
                                  streamAggregateId,
                                  s"test-event-".getBytes,
                                  Array.emptyByteArray,
                                  "test-data"
                                )
        nextExpectedRevision <- writeResult.getNextExpectedRevision
      } yield expect(nextExpectedRevision == ExpectedRevision.Exact(2))
    }
  }

}
