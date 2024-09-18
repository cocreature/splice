// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext, TriggerEnabledSynchronization}
import com.daml.network.config.Thresholds
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.validator.domain.DomainConnector
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import cats.syntax.foldable.*
import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencerConnectionsTrigger(
    baseContext: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    domainConnector: DomainConnector,
    patience: NonNegativeFiniteDuration,
    supportsSoftDomainMigrationPoc: Boolean,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  // Disabling domain time and params sync since we might need to fix domain connections to allow for catchup.
  override protected lazy val context =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      decentralizedSynchronizer <- amuletRulesDomain()
      maybeDomainTime <-
        if (supportsSoftDomainMigrationPoc)
          // TODO(#13301) This should query domain time for each domain and chose sequencers based on that to work accurately with catchup
          Future.successful(Some(context.clock.now))
        else
          participantAdminConnection
            .getDomainTimeLowerBound(
              decentralizedSynchronizer,
              maxDomainTimeLag = context.config.pollingInterval,
            )
            .map(domainTime => Some(domainTime.timestamp))
            .recover {
              // Time tracker for domain not found. the domainTime is not yet available.
              case ex: StatusRuntimeException
                  if ex.getStatus.getCode == Code.INVALID_ARGUMENT &&
                    ex.getStatus.getDescription.contains("Time tracker for domain") =>
                None
            }
      _ <- maybeDomainTime match {
        case Some(domainTime) =>
          for {
            sequencerConnections <- domainConnector.getSequencerConnectionsFromScan(domainTime)
            _ <- sequencerConnections.toList.traverse_ { case (alias, connections) =>
              val sequencerConnectionConfig = NonEmpty.from(connections) match {
                case None =>
                  // We warn on repeated failures of a polling trigger so
                  // it's safe to just treat it as a transient exception and retry without logging warnings.
                  throw Status.NOT_FOUND
                    .withDescription(
                      "Dso Sequencer list from Scan is empty, not modifying sequencers connections. This can happen during initialization when domain time is lagging behind."
                    )
                    .asRuntimeException()
                case Some(nonEmptyConnections) =>
                  SequencerConnections.tryMany(
                    nonEmptyConnections.forgetNE,
                    Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
                    submissionRequestAmplification = SubmissionRequestAmplification(
                      Thresholds.sequencerSubmissionRequestAmplification(nonEmptyConnections.size),
                      patience,
                    ),
                  )
              }
              participantAdminConnection.modifyOrRegisterDomainConnectionConfigAndReconnect(
                DomainConnectionConfig(
                  alias,
                  sequencerConnectionConfig,
                ),
                modifySequencerConnections(sequencerConnectionConfig),
                RetryFor.Automation,
              )
            }
          } yield ()
        case None =>
          logger.debug("time tracker from the domain is not yet available, skipping")
          Future.unit
      }

    } yield false
  }

  private[this] def amuletRulesDomain()(implicit tc: TraceContext) =
    scanConnection.getAmuletRulesDomain()(tc)

  private def modifySequencerConnections(
      sequencerConnections: SequencerConnections
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf => {
      if (differentEndpointSet(sequencerConnections, conf.sequencerConnections.connections)) {
        logger.info(
          s"modifying sequencers connections to $sequencerConnections"
        )
        Some(conf.copy(sequencerConnections = sequencerConnections))
      } else {
        logger.trace(
          "sequencers connections are already set. not modifying sequencers."
        )
        None
      }
    }

  private def differentEndpointSet(
      sequencerConnections: SequencerConnections,
      connections: NonEmpty[Seq[SequencerConnection]],
  )(implicit tc: TraceContext) =
    sequencerConnections.connections.forgetNE
      .flatMap(c => sequencerConnectionEndpoint(c).toList)
      .toSet != connections.forgetNE.flatMap(c => sequencerConnectionEndpoint(c).toList).toSet

  private def sequencerConnectionEndpoint(
      connection: SequencerConnection
  )(implicit tc: TraceContext): Option[Endpoint] =
    connection match {
      case GrpcSequencerConnection(endpoints, _, _, _) if endpoints.size == 1 =>
        Some(endpoints.head1)
      case GrpcSequencerConnection(endpoints, _, _, _) =>
        logger.warn(s"expected exactly 1 endpoint in a sequencer connection but got: $endpoints")
        None
    }
}
