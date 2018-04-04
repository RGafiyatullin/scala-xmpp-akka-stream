package com.github.rgafiyatullin.xmpp_akka_stream

import scala.util.{Either, Right}

// sorry, 2.11 has no map and flatMap defined on Either[A,B]
private[xmpp_akka_stream] object EitherWithMap {
  implicit class EitherWithMapWrapper[A, B](either: Either[A, B]) {
    def doMap[B1](f: B => B1): Either[A, B1] = either match {
      case Right(b) => Right(f(b))
      case _        => either.asInstanceOf[Either[A, B1]]
    }
    def doFlatMap[A1 >: A, B1](f: B => Either[A1, B1]): Either[A1, B1] = either match {
      case Right(b) => f(b)
      case _        => either.asInstanceOf[Either[A1, B1]]
    }
  }
}
