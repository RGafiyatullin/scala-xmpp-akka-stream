package com.github.rgafiyatullin.xmpp_akka_stream.util

import scala.concurrent.ExecutionContext

private[xmpp_akka_stream] object SameThreadExecutionContext extends ExecutionContext {
  override def execute(runnable: Runnable): Unit =
    runnable.run()

  override def reportFailure(cause: Throwable): Unit =
    throw new IllegalStateException("exception in sameThreadExecutionContext", cause)
}
