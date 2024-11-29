/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.patroni;

import static io.stackgres.common.StackGresUtil.getDefaultPullPolicy;
import static io.stackgres.common.StackGresUtil.getPostgresFlavorComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapEnvSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.stackgres.common.ClusterPath;
import io.stackgres.common.EnvoyUtil;
import io.stackgres.common.StackGresComponent;
import io.stackgres.common.StackGresContainer;
import io.stackgres.common.StackGresContext;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.StackGresVolume;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterInitialData;
import io.stackgres.common.crd.sgcluster.StackGresClusterPods;
import io.stackgres.common.crd.sgcluster.StackGresClusterRestore;
import io.stackgres.common.crd.sgcluster.StackGresClusterRestoreFromBackup;
import io.stackgres.common.crd.sgcluster.StackGresClusterSpec;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.factory.ContainerFactory;
import io.stackgres.operator.conciliation.factory.LocalBinMounts;
import io.stackgres.operator.conciliation.factory.PostgresSocketMount;
import io.stackgres.operator.conciliation.factory.RunningContainer;
import io.stackgres.operator.conciliation.factory.cluster.BackupVolumeMounts;
import io.stackgres.operator.conciliation.factory.cluster.ClusterContainerContext;
import io.stackgres.operator.conciliation.factory.cluster.HugePagesMounts;
import io.stackgres.operator.conciliation.factory.cluster.PostgresEnvironmentVariables;
import io.stackgres.operator.conciliation.factory.cluster.PostgresExtensionMounts;
import io.stackgres.operator.conciliation.factory.cluster.ReplicateVolumeMounts;
import io.stackgres.operator.conciliation.factory.cluster.ReplicationInitializationVolumeMounts;
import io.stackgres.operator.conciliation.factory.cluster.RestoreVolumeMounts;
import io.stackgres.operatorframework.resource.ResourceUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@OperatorVersionBinder
@RunningContainer(StackGresContainer.PATRONI)
public class Patroni implements ContainerFactory<ClusterContainerContext> {

  private static final String POD_MONITOR = "-patroni";

  private final PatroniEnvironmentVariables patroniEnvironmentVariables;
  private final PostgresEnvironmentVariables postgresEnvironmentVariables;
  private final PostgresSocketMount postgresSocket;
  private final PostgresExtensionMounts postgresExtensions;
  private final LocalBinMounts localBinMounts;
  private final RestoreVolumeMounts restoreMounts;
  private final BackupVolumeMounts backupMounts;
  private final ReplicationInitializationVolumeMounts replicationInitMounts;
  private final ReplicateVolumeMounts replicateMounts;
  private final PatroniVolumeMounts patroniMounts;
  private final HugePagesMounts hugePagesMounts;
  private final PatroniConfigMap patroniConfigMap;

  public static String podMonitorName(StackGresClusterContext clusterContext) {
    String namespace = clusterContext.getSource().getMetadata().getNamespace();
    String name = clusterContext.getSource().getMetadata().getName();
    return ResourceUtil.resourceName(namespace + "-" + name + POD_MONITOR);
  }

  @Inject
  public Patroni(
      PatroniEnvironmentVariables patroniEnvironmentVariables,
      PostgresEnvironmentVariables postgresEnvironmentVariables,
      PostgresSocketMount postgresSocket,
      PostgresExtensionMounts postgresExtensions,
      LocalBinMounts localBinMounts,
      RestoreVolumeMounts restoreMounts,
      BackupVolumeMounts backupMounts,
      ReplicationInitializationVolumeMounts replicationInitMounts,
      ReplicateVolumeMounts replicateMounts,
      PatroniVolumeMounts patroniMounts,
      HugePagesMounts hugePagesMounts,
      @OperatorVersionBinder
      PatroniConfigMap patroniConfigMap) {
    super();
    this.patroniEnvironmentVariables = patroniEnvironmentVariables;
    this.postgresEnvironmentVariables = postgresEnvironmentVariables;
    this.postgresSocket = postgresSocket;
    this.postgresExtensions = postgresExtensions;
    this.localBinMounts = localBinMounts;
    this.restoreMounts = restoreMounts;
    this.backupMounts = backupMounts;
    this.replicationInitMounts = replicationInitMounts;
    this.replicateMounts = replicateMounts;
    this.patroniMounts = patroniMounts;
    this.hugePagesMounts = hugePagesMounts;
    this.patroniConfigMap = patroniConfigMap;
  }

  @Override
  public Map<String, String> getComponentVersions(ClusterContainerContext context) {
    return Map.of(
        StackGresContext.POSTGRES_VERSION_KEY,
        StackGresComponent.POSTGRESQL.get(context.getClusterContext().getCluster())
        .getVersion(
            context.getClusterContext().getCluster().getSpec().getPostgres().getVersion()),
        StackGresContext.PATRONI_VERSION_KEY,
        StackGresComponent.PATRONI.get(context.getClusterContext().getCluster())
        .getLatestVersion());
  }

  @Override
  public Container getContainer(ClusterContainerContext context) {
    final StackGresClusterContext clusterContext = context.getClusterContext();
    final StackGresCluster cluster = clusterContext.getSource();
    final String patroniImageName = StackGresUtil.getPatroniImageName(cluster);

    ImmutableList.Builder<VolumeMount> volumeMounts = ImmutableList.<VolumeMount>builder()
        .addAll(postgresSocket.getVolumeMounts(context))
        .add(new VolumeMountBuilder()
            .withName(StackGresVolume.DSHM.getName())
            .withMountPath(ClusterPath.SHARED_MEMORY_PATH.path())
            .build())
        .add(new VolumeMountBuilder()
            .withName(StackGresVolume.LOG.getName())
            .withMountPath(ClusterPath.PG_LOG_PATH.path())
            .build())
        .addAll(localBinMounts.getVolumeMounts(context))
        .addAll(patroniMounts.getVolumeMounts(context))
        .addAll(backupMounts.getVolumeMounts(context))
        .addAll(replicationInitMounts.getVolumeMounts(context))
        .addAll(replicateMounts.getVolumeMounts(context))
        .addAll(postgresExtensions.getVolumeMounts(context))
        .addAll(hugePagesMounts.getVolumeMounts(context))
        .add(new VolumeMountBuilder()
            .withName(StackGresVolume.POSTGRES_SSL_COPY.getName())
            .withMountPath(ClusterPath.SSL_PATH.path())
            .withReadOnly(true)
            .build());

    Optional.ofNullable(cluster.getSpec().getInitialData())
        .map(StackGresClusterInitialData::getRestore)
        .map(StackGresClusterRestore::getFromBackup)
        .map(StackGresClusterRestoreFromBackup::getName).ifPresent(ignore ->
            volumeMounts.addAll(restoreMounts.getVolumeMounts(context))
        );

    boolean isEnvoyDisabled = Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPods)
        .map(StackGresClusterPods::getDisableEnvoy)
        .orElse(false);
    final int patroniPort = isEnvoyDisabled ? EnvoyUtil.PATRONI_PORT : EnvoyUtil.PATRONI_ENTRY_PORT;

    return new ContainerBuilder()
        .withName(StackGresContainer.PATRONI.getName())
        .withImage(patroniImageName)
        .withCommand("/bin/sh", "-ex",
            ClusterPath.LOCAL_BIN_START_PATRONI_SH_PATH.path())
        .withImagePullPolicy(getDefaultPullPolicy())
        .withVolumeMounts(volumeMounts.build())
        .withEnv(getEnvVars(context))
        .withEnvFrom(new EnvFromSourceBuilder()
            .withConfigMapRef(new ConfigMapEnvSourceBuilder()
                .withName(PatroniConfigMap.name(clusterContext)).build())
            .build())
        .addToEnv(new EnvVarBuilder()
            .withName("PATRONI_CONFIG_MD5SUM")
            .withValue(Optional.of(patroniConfigMap.buildSource(context.getClusterContext()))
                .map(ConfigMap::getData)
                .map(data -> {
                  var dataWithoutDcs = new HashMap<>(data);
                  dataWithoutDcs.remove(PatroniConfigMap.PATRONI_DCS_CONFIG_ENV_NAME);
                  return StackGresUtil.addMd5Sum(dataWithoutDcs);
                })
                .map(data -> data.get(StackGresUtil.MD5SUM_2_KEY))
                .orElseThrow())
            .build())
        .withLivenessProbe(new ProbeBuilder()
            .withHttpGet(new HTTPGetActionBuilder()
                .withPath("/liveness")
                .withPort(new IntOrString(patroniPort))
                .withScheme("HTTP")
                .build())
            .withInitialDelaySeconds(15)
            .withPeriodSeconds(20)
            .withFailureThreshold(6)
            .build())
        .withReadinessProbe(new ProbeBuilder()
            .withHttpGet(new HTTPGetActionBuilder()
                .withPath("/readiness")
                .withPort(new IntOrString(patroniPort))
                .withScheme("HTTP")
                .build())
            .withInitialDelaySeconds(0)
            .withPeriodSeconds(2)
            .withTimeoutSeconds(1)
            .build())
        .withPorts(getContainerPorts(cluster))
        .build();
  }

  private List<ContainerPort> getContainerPorts(StackGresCluster cluster) {
    boolean isEnvoyDisabled = Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPods)
        .map(StackGresClusterPods::getDisableEnvoy)
        .orElse(false);
    if (!isEnvoyDisabled) {
      return List.of();
    }
    boolean isConnectionPoolingDisabled = Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPods)
        .map(StackGresClusterPods::getDisableConnectionPooling)
        .orElse(false);
    if (getPostgresFlavorComponent(cluster) == StackGresComponent.BABELFISH) {
      if (isConnectionPoolingDisabled) {
        return List.of(
            new ContainerPortBuilder()
                .withProtocol("TCP")
                .withName(EnvoyUtil.POSTGRES_PORT_NAME)
                .withContainerPort(EnvoyUtil.PG_ENTRY_PORT)
                .build(),
            new ContainerPortBuilder()
                .withProtocol("TCP")
                .withName(EnvoyUtil.BABELFISH_PORT_NAME)
                .withContainerPort(EnvoyUtil.BF_PORT)
                .build(),
            new ContainerPortBuilder()
                .withName(EnvoyUtil.PATRONI_RESTAPI_PORT_NAME)
                .withProtocol("TCP")
                .withContainerPort(EnvoyUtil.PATRONI_PORT)
                .build());
      }
      return List.of(
          new ContainerPortBuilder()
              .withProtocol("TCP")
              .withName(EnvoyUtil.POSTGRES_REPLICATION_PORT_NAME)
              .withContainerPort(EnvoyUtil.PG_PORT)
              .build(),
          new ContainerPortBuilder()
              .withProtocol("TCP")
              .withName(EnvoyUtil.BABELFISH_PORT_NAME)
              .withContainerPort(EnvoyUtil.BF_PORT)
              .build(),
          new ContainerPortBuilder()
              .withName(EnvoyUtil.PATRONI_RESTAPI_PORT_NAME)
              .withProtocol("TCP")
              .withContainerPort(EnvoyUtil.PATRONI_PORT)
              .build());
    }
    if (isConnectionPoolingDisabled) {
      return List.of(
          new ContainerPortBuilder()
              .withProtocol("TCP")
              .withName(EnvoyUtil.POSTGRES_PORT_NAME)
              .withContainerPort(EnvoyUtil.PG_PORT)
              .build(),
          new ContainerPortBuilder()
              .withName(EnvoyUtil.PATRONI_RESTAPI_PORT_NAME)
              .withProtocol("TCP")
              .withContainerPort(EnvoyUtil.PATRONI_PORT)
              .build());
    }
    return List.of(
        new ContainerPortBuilder()
            .withProtocol("TCP")
            .withName(EnvoyUtil.POSTGRES_REPLICATION_PORT_NAME)
            .withContainerPort(EnvoyUtil.PG_PORT)
            .build(),
        new ContainerPortBuilder()
            .withName(EnvoyUtil.PATRONI_RESTAPI_PORT_NAME)
            .withProtocol("TCP")
            .withContainerPort(EnvoyUtil.PATRONI_PORT)
            .build());
  }

  private ImmutableList<EnvVar> getEnvVars(ClusterContainerContext context) {
    final StackGresClusterContext clusterContext = context.getClusterContext();
    return ImmutableList.<EnvVar>builder()
        .addAll(patroniEnvironmentVariables.getEnvVars(clusterContext))
        .addAll(postgresEnvironmentVariables.getEnvVars(clusterContext))
        .addAll(localBinMounts.getDerivedEnvVars(context))
        .addAll(postgresExtensions.getDerivedEnvVars(context))
        .addAll(patroniMounts.getDerivedEnvVars(context))
        .addAll(backupMounts.getDerivedEnvVars(context))
        .addAll(replicationInitMounts.getDerivedEnvVars(context))
        .addAll(replicateMounts.getDerivedEnvVars(context))
        .addAll(restoreMounts.getDerivedEnvVars(context))
        .addAll(hugePagesMounts.getDerivedEnvVars(context))
        .build();
  }

}
