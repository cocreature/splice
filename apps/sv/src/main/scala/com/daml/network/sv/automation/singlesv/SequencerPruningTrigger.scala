// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SequencerAdminConnection,
}
import com.daml.network.sv.store.SvDsoStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerPruningStatus
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.jdk.DurationConverters.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to periodically call the sequencer pruning command
  */
class SequencerPruningTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    sequencerAdminConnection: SequencerAdminConnection,
    mediatorAdminConnection: MediatorAdminConnection,
    clock: Clock,
    retentionPeriod: NonNegativeFiniteDuration,
    participantAdminConnection: ParticipantAdminConnection,
    migrationId: Long,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  val pruningMetrics = new SequencerPruningMetrics(
    context.metricsFactory
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      rulesAndState <- store.getDsoRulesWithSvNodeState(store.key.svParty)
      // TODO(#4906): check whether are passing the right domain-id to make this work with soft-domain migration
      dsoRulesActiveSequencerConfig = rulesAndState.lookupSequencerConfigFor(
        rulesAndState.dsoRules.domain,
        clock.now.toInstant,
        migrationId,
      )
      _ <- dsoRulesActiveSequencerConfig.fold {
        logger.debug(
          show"Member info or sequencer info not (yet) published to DsoRules for our own party ${store.key.svParty}, skipping"
        )
        Future.unit
      } { _ =>
        {
          logger.debug("Attempt pruning our sequencer...")
          prune().map { prunedResult =>
            logger.debug(s"Completed pruning our sequencer with result: $prunedResult")
          }
        }
      }
    } yield false

  // This method is replicating the force_prune command defined in `com.digitalasset.canton.console.commands.SequencerAdministrationGroupCommon`
  // Which will prunes the member preventing pruning
  private def prune()(implicit traceContext: TraceContext) = for {
    status <- sequencerAdminConnection.getSequencerPruningStatus()
    pruningTimestamp = status.now.minus(retentionPeriod.underlying.toJava)
    membersToDisable = clientsPreventingPruning(status, pruningTimestamp)
    _ <-
      // disabling member preventing pruning
      if (membersToDisable.nonEmpty) {
        filterToOurMembers(membersToDisable).flatMap { ourLaggingMembers =>
          if (ourLaggingMembers.isEmpty) {
            logger.info(
              show"disabling ${membersToDisable.size} member clients preventing pruning to $pruningTimestamp: $membersToDisable"
            )
            pruningMetrics.disabledMembers.updateValue(membersToDisable.size)
            Future.traverse(membersToDisable)(m => sequencerAdminConnection.disableMember(m.member))
          } else {
            throw Status.INTERNAL
              .withDescription(
                show"Failed to prune sequencer to $pruningTimestamp because our own nodes have not acknowledged that timestamp: ${ourLaggingMembers}"
              )
              .asRuntimeException()
          }
        }
      } else Future.unit
    statusAfterDisabling <- sequencerAdminConnection.getSequencerPruningStatus()
    safeTimestamp = statusAfterDisabling.safePruningTimestamp
    res <-
      if (safeTimestamp < pruningTimestamp) {
        val message = (
          s"We disabled all clients preventing pruning at $pruningTimestamp however the safe timestamp is set to $safeTimestamp"
        )
        Future.failed(Status.INTERNAL.withDescription(message).asRuntimeException())
      } else
        pruningMetrics.latency
          .timeFuture(
            sequencerAdminConnection
              .prune(pruningTimestamp)
          )
          .transform(
            identity,
            err => {
              val lastAcknowledged =
                statusAfterDisabling.members
                  .map(m => m.member.toProtoPrimitive -> m.safePruningTimestamp)
              val message = s"failed to prune with sequencer pruning status: $lastAcknowledged"
              if (context.retryProvider.isClosing)
                logger.info(message)
              else
                logger.warn(message)
              err
            },
          )

  } yield res

  private def filterToOurMembers(
      laggingMembers: Seq[SequencerPruningTrigger.LaggingMember]
  )(implicit traceContext: TraceContext): Future[Seq[SequencerPruningTrigger.LaggingMember]] = for {
    participantId <- participantAdminConnection.getParticipantId()
    mediatorId <- mediatorAdminConnection.getMediatorId
    sequencerId <- sequencerAdminConnection.getSequencerId
  } yield laggingMembers.filter(m =>
    Seq[Member](participantId.member, mediatorId.member, sequencerId.member).contains(m.member)
  )

  private def clientsPreventingPruning(
      status: SequencerPruningStatus,
      timestamp: CantonTimestamp,
  ): Seq[SequencerPruningTrigger.LaggingMember] = {
    val memberToSafePruningTimestamp: Map[Member, CantonTimestamp] =
      status.members.view.map(m => m.member -> m.safePruningTimestamp).toMap
    status
      .clientsPreventingPruning(timestamp)
      .members
      .toList
      .map(m => SequencerPruningTrigger.LaggingMember(m, memberToSafePruningTimestamp(m)))
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    SyncCloseable("Pruning Metrics", pruningMetrics.close()) +: super.closeAsync()
}

private object SequencerPruningTrigger {
  final case class LaggingMember(
      member: Member,
      safePruningTimestamp: CantonTimestamp,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("member", _.member),
        param("safePruningTimestamp", _.safePruningTimestamp),
      )
  }
}
