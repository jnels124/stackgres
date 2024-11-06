/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.shardedcluster;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.stackgres.common.crd.Condition;
import io.stackgres.common.crd.sgshardedcluster.StackGresShardedCluster;
import io.stackgres.common.event.EventEmitter;
import io.stackgres.common.fixture.Fixtures;
import io.stackgres.common.resource.CustomResourceScheduler;
import io.stackgres.operator.conciliation.AbstractConciliator;
import io.stackgres.operator.conciliation.DeployedResourcesCache;
import io.stackgres.operator.conciliation.HandlerDelegator;
import io.stackgres.operator.conciliation.ReconciliationResult;
import io.stackgres.operator.conciliation.StatusManager;
import io.stackgres.operator.conciliation.factory.cluster.KubernetessMockResourceGenerationUtil;
import io.stackgres.testutil.JsonUtil;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShardedClusterReconciliatorTest {

  private final StackGresShardedCluster cluster = Fixtures.shardedCluster().loadDefault().get();
  @Mock
  AbstractConciliator<StackGresShardedCluster> conciliator;
  @Mock
  DeployedResourcesCache deployedResourcesCache;
  @Mock
  HandlerDelegator<StackGresShardedCluster> handlerDelegator;
  @Mock
  StatusManager<StackGresShardedCluster, Condition> statusManager;
  @Mock
  EventEmitter<StackGresShardedCluster> eventController;
  @Mock
  CustomResourceScheduler<StackGresShardedCluster> clusterScheduler;

  private ShardedClusterReconciliator reconciliator;

  @BeforeEach
  void setUp() {
    ShardedClusterReconciliator.Parameters parameters =
        new ShardedClusterReconciliator.Parameters();
    parameters.conciliator = conciliator;
    parameters.deployedResourcesCache = deployedResourcesCache;
    parameters.handlerDelegator = handlerDelegator;
    parameters.eventController = eventController;
    parameters.statusManager = statusManager;
    parameters.clusterScheduler = clusterScheduler;
    parameters.objectMapper = JsonUtil.jsonMapper();
    reconciliator = spy(new ShardedClusterReconciliator(parameters));
  }

  @Test
  void allCreations_shouldBePerformed() {
    final List<HasMetadata> creations = KubernetessMockResourceGenerationUtil
        .buildResources("test", "test");

    creations.forEach(resource -> when(handlerDelegator.create(cluster, resource))
        .thenReturn(resource));

    when(conciliator.evalReconciliationState(cluster))
        .thenReturn(new ReconciliationResult(
            creations,
            List.of(),
            List.of()));

    reconciliator.reconciliationCycle(cluster, 0, false);

    verify(conciliator).evalReconciliationState(cluster);
    creations.forEach(resource -> verify(handlerDelegator).create(cluster, resource));
  }

  @Test
  void allPatches_shouldBePerformed() {
    final List<Tuple2<HasMetadata, HasMetadata>> patches = KubernetessMockResourceGenerationUtil
        .buildResources("test", "test")
        .stream().map(r -> Tuple.tuple(r, r))
        .collect(Collectors.toUnmodifiableList());

    patches.forEach(resource -> when(handlerDelegator.patch(cluster, resource.v1, resource.v2))
        .thenReturn(resource.v1));

    when(conciliator.evalReconciliationState(cluster))
        .thenReturn(new ReconciliationResult(
            List.of(),
            patches,
            List.of()));

    reconciliator.reconciliationCycle(cluster, 0, false);

    verify(conciliator).evalReconciliationState(cluster);
    patches.forEach(resource -> verify(handlerDelegator).patch(cluster, resource.v1, resource.v2));
  }

  @Test
  void allDeletions_shouldBePerformed() {
    final List<HasMetadata> deletions = KubernetessMockResourceGenerationUtil
        .buildResources("test", "test");

    deletions.forEach(resource -> doNothing().when(handlerDelegator).delete(cluster, resource));

    when(conciliator.evalReconciliationState(cluster))
        .thenReturn(new ReconciliationResult(
            List.of(),
            List.of(),
            deletions));

    reconciliator.reconciliationCycle(cluster, 0, false);

    verify(conciliator).evalReconciliationState(cluster);
    deletions.forEach(resource -> verify(handlerDelegator).delete(cluster, resource));
  }

}
