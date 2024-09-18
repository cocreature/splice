// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.store.DomainTimeStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

final class DomainTimeIngestionTrigger(
    domainAlias: DomainAlias,
    domainTimeStore: DomainTimeStore,
    participantAdminConnection: ParticipantAdminConnection,
    context: TriggerContext,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends PeriodicTaskTrigger(context, quiet = true) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    participantAdminConnection.getDomainId(domainAlias).transformWith {
      case Failure(s: StatusRuntimeException) if s.getStatus.getCode == Status.Code.NOT_FOUND =>
        // This can happen during initialization, just skip it to reduce log noise.
        Future.successful(TaskNoop)
      case Failure(e) => Future.failed(e)
      case Success(domainId) =>
        for {
          time <- participantAdminConnection
            .getDomainTimeLowerBound(domainId, context.config.pollingInterval)
            .map(_.timestamp)
          _ <- domainTimeStore.ingestDomainTime(time)
        } yield TaskSuccess(show"Updated domain time to $time")
    }
}
