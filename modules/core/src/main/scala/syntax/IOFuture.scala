// Copyright (c) 2022 by LapsusHQ
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dolphin.syntax

import scala.concurrent.{ExecutionContext, Future}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

sealed trait IOFuture[F[_]] {
  def convert[A](fa: F[A])(implicit runtime: IORuntime): Future[A]
}

object IOFuture {
  def apply[F[_]: IOFuture]: IOFuture[F] = implicitly[IOFuture[F]]

  implicit val ioFuture: IOFuture[IO] =
    new IOFuture[IO] {
      override def convert[A](fa: IO[A])(implicit runtime: IORuntime): Future[A] = fa.unsafeToFuture()
    }

  implicit class IOFutureOps[F[_]: IOFuture, A](fa: F[A]) {
    def toFuture(implicit runtime: IORuntime): Future[A] = IOFuture[F].convert(fa)
  }

}

trait IOFutureSyntax {

  implicit class IOFutureOps[F[_]: IOFuture, A](fa: F[A]) {
    def toFuture(implicit runtime: IORuntime): Future[A] = IOFuture[F].convert(fa)

    def toUnit(implicit ec: ExecutionContext, runtime: IORuntime): Unit = IOFuture[F].convert(fa).onComplete(_ => ())
  }

  implicit class FutureOps[A](fa: Future[A]) {
    def toIO: IO[A] = IO.fromFuture(IO(fa))

  }

}

object future extends IOFutureSyntax
