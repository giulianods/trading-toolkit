package io.greensheep.trading.service.app

import zio.*
import zio.json.{DeriveJsonEncoder, JsonEncoder}

import java.time.Instant

final case class JobSnapshot(
    name: String,
    running: Boolean,
    lastSuccess: Option[String],
    lastError: Option[String]
)

object JobSnapshot {
  implicit val encoder: JsonEncoder[JobSnapshot] = DeriveJsonEncoder.gen[JobSnapshot]
}

final case class AppStatus(
    status: String,
    mongo: String,
    web: String,
    cron: String,
    jobs: Seq[JobSnapshot]
)

object AppStatus {
  implicit val encoder: JsonEncoder[AppStatus] = DeriveJsonEncoder.gen[AppStatus]
}

trait AppHealth {
  def tryStart(name: String): UIO[Boolean]
  def finishSuccess(name: String): UIO[Unit]
  def finishFailure(name: String, error: String): UIO[Unit]
  def snapshot: UIO[Seq[JobSnapshot]]
  def markCronStarted: UIO[Unit]
  def cronStarted: UIO[Boolean]
}

object AppHealth {
  val layer: ULayer[AppHealth] = ZLayer.fromZIO {
    for {
      state <- Ref.make(Map.empty[String, JobSnapshot])
      cron <- Ref.make(false)
    } yield new AppHealth {
        def tryStart(name: String): UIO[Boolean] =
          state.modify { current =>
            current.get(name).exists(_.running) match {
              case true =>
                false -> current
              case false =>
                val next = current.updated(
                  name,
                  current.getOrElse(name, JobSnapshot(name, running = false, None, None)).copy(running = true)
                )
                true -> next
            }
          }

        def finishSuccess(name: String): UIO[Unit] =
          state.update { current =>
            current.updated(
              name,
              JobSnapshot(name, running = false, lastSuccess = Some(Instant.now().toString), lastError = None)
            )
          }

        def finishFailure(name: String, error: String): UIO[Unit] =
          state.update { current =>
            val previous = current.getOrElse(name, JobSnapshot(name, running = false, None, None))
            current.updated(name, previous.copy(running = false, lastError = Some(error)))
          }

        def snapshot: UIO[Seq[JobSnapshot]] =
          state.get.map(_.values.toSeq.sortBy(_.name))

        def markCronStarted: UIO[Unit] =
          cron.set(true)

        def cronStarted: UIO[Boolean] =
          cron.get
      }
  }

  def tryStart(name: String): URIO[AppHealth, Boolean] =
    ZIO.serviceWithZIO(_.tryStart(name))

  def finishSuccess(name: String): URIO[AppHealth, Unit] =
    ZIO.serviceWithZIO(_.finishSuccess(name))

  def finishFailure(name: String, error: String): URIO[AppHealth, Unit] =
    ZIO.serviceWithZIO(_.finishFailure(name, error))

  def snapshot: URIO[AppHealth, Seq[JobSnapshot]] =
    ZIO.serviceWithZIO(_.snapshot)

  def markCronStarted: URIO[AppHealth, Unit] =
    ZIO.serviceWithZIO(_.markCronStarted)

  def cronStarted: URIO[AppHealth, Boolean] =
    ZIO.serviceWithZIO(_.cronStarted)
}
