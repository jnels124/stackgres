/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.controller;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.operator.common.StackGresClusterContext;
import io.stackgres.operatorframework.resource.Condition;
import io.stackgres.operatorframework.resource.ConditionUpdater;
import org.jooq.lambda.tuple.Tuple2;

public abstract class AbstractClusterStatusManager<T extends StackGresClusterContext,
    C extends Condition> extends ConditionUpdater<T, C> {

  private final LabelFactory<?> labelFactory;

  public AbstractClusterStatusManager(LabelFactory<?> labelFactory) {
    this.labelFactory = labelFactory;
  }

  /**
   * Update pending restart status condition.
   */
  public void updatePendingRestart(T context, KubernetesClient client) {
    if (isPendingRestart(context)) {
      updateCondition(getPodRequiresRestart(), context, client);
    } else {
      updateCondition(getFalsePendingRestart(), context, client);
    }
  }

  protected abstract C getFalsePendingRestart();

  protected abstract C getPodRequiresRestart();

  /**
   * Check pending restart status condition.
   */
  public boolean isPendingRestart(T context) {
    return isStatefulSetPendingRestart(context)
        || isPatroniPendingRestart(context);
  }

  private boolean isStatefulSetPendingRestart(T context) {
    final Map<String, String> clusterLabels = labelFactory.clusterLabels(context.getCluster());
    final Map<String, String> podClusterLabels =
        labelFactory.patroniClusterLabels(context.getCluster());

    String statefulSetUpdateRevision = context.getExistingResources()
        .stream()
        .map(Tuple2::v1)
        .filter(StatefulSet.class::isInstance)
        .map(StatefulSet.class::cast)
        .filter(sts -> sts.getMetadata() != null
            && sts.getMetadata().getLabels() != null
            && clusterLabels.entrySet().stream()
            .allMatch(clusterLabel -> sts.getMetadata().getLabels().entrySet().stream()
                .anyMatch(stsLabel -> Objects.equals(stsLabel, clusterLabel))))
        .map(sts -> Optional.ofNullable(sts.getStatus())
            .map(StatefulSetStatus::getUpdateRevision))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst()
        .orElse(null);

    return context.getExistingResources()
        .stream()
        .map(Tuple2::v1)
        .filter(Pod.class::isInstance)
        .map(Pod.class::cast)
        .filter(pod -> pod.getMetadata() != null
            && pod.getMetadata().getLabels() != null
            && podClusterLabels.entrySet().stream()
            .allMatch(podClusterLabel -> pod.getMetadata().getLabels().entrySet().stream()
                .anyMatch(podLabel -> Objects.equals(podLabel, podClusterLabel))))
        .anyMatch(pod -> !Objects.equals(
            statefulSetUpdateRevision,
            pod.getMetadata().getLabels().get("controller-revision-hash")));
  }

  private boolean isPatroniPendingRestart(T context) {
    final StackGresCluster cluster = context.getCluster();
    final Map<String, String> podClusterLabels = labelFactory.patroniClusterLabels(cluster);

    return context.getExistingResources()
        .stream()
        .map(Tuple2::v1)
        .filter(Pod.class::isInstance)
        .map(Pod.class::cast)
        .filter(pod -> pod.getMetadata() != null
            && pod.getMetadata().getLabels() != null
            && podClusterLabels.entrySet().stream()
            .allMatch(podClusterLabel -> pod.getMetadata().getLabels().entrySet().stream()
                .anyMatch(podLabel -> Objects.equals(podLabel, podClusterLabel))))
        .map(Pod::getMetadata).filter(Objects::nonNull)
        .map(ObjectMeta::getAnnotations).filter(Objects::nonNull)
        .map(Map::entrySet)
        .anyMatch(p -> p.stream()
            .map(Map.Entry::getValue).filter(Objects::nonNull)
            .anyMatch(r -> r.contains("\"pending_restart\":true")));
  }

}
