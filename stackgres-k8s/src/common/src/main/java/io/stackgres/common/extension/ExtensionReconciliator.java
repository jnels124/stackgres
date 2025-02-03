/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.stackgres.common.CdiUtil;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterPodStatus;
import io.stackgres.common.crd.sgcluster.StackGresClusterStatus;
import io.stackgres.common.extension.ExtensionManager.ExtensionInstaller;
import io.stackgres.common.extension.ExtensionManager.ExtensionUninstaller;
import io.stackgres.operatorframework.reconciliation.ReconciliationResult;
import io.stackgres.operatorframework.reconciliation.SafeReconciliator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ExtensionReconciliator<T extends ExtensionReconciliatorContext>
    extends SafeReconciliator<T, Boolean> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionReconciliator.class);

  private final String podName;
  private final ExtensionManager extensionManager;
  private final Supplier<Boolean> skipSharedLibrariesOverwrites;
  private final ExtensionEventEmitter extensionEventEmitter;

  protected ExtensionReconciliator(
      String podName,
      ExtensionManager extensionManager,
      Supplier<Boolean> skipSharedLibrariesOverwrites,
      ExtensionEventEmitter extensionEventEmitter) {
    this.podName = podName;
    this.extensionManager = extensionManager;
    this.skipSharedLibrariesOverwrites = skipSharedLibrariesOverwrites;
    this.extensionEventEmitter = extensionEventEmitter;
  }

  public ExtensionReconciliator() {
    CdiUtil.checkPublicNoArgsConstructorIsCalledToCreateProxy(getClass());
    this.podName = null;
    this.extensionManager = null;
    this.skipSharedLibrariesOverwrites = null;
    this.extensionEventEmitter = null;
  }

  @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
      justification = "False positives")
  public ReconciliationResult<Boolean> safeReconcile(KubernetesClient client, T context)
      throws Exception {
    final boolean skipSharedLibrariesOverwrites = this.skipSharedLibrariesOverwrites.get();
    final ImmutableList.Builder<Exception> exceptions = ImmutableList.builder();
    final StackGresCluster cluster = context.getCluster();
    final ImmutableList<StackGresClusterInstalledExtension> toInstallExtensions =
        context.getExtensions();
    if (cluster.getStatus() == null) {
      cluster.setStatus(new StackGresClusterStatus());
    }
    if (cluster.getStatus().getPodStatuses() == null) {
      cluster.getStatus().setPodStatuses(new ArrayList<>());
    }
    if (cluster.getStatus().getPodStatuses().stream()
        .noneMatch(podStatus -> podStatus.getName().equals(podName))) {
      StackGresClusterPodStatus podStatus = new StackGresClusterPodStatus();
      podStatus.setName(podName);
      podStatus.setInstalledPostgresExtensions(new ArrayList<>());
      cluster.getStatus().getPodStatuses().add(podStatus);
    }
    final StackGresClusterPodStatus podStatus = cluster.getStatus().getPodStatuses().stream()
        .filter(status -> status.getName().equals(podName))
        .findAny().get();
    if (podStatus.getInstalledPostgresExtensions() == null) {
      podStatus.setInstalledPostgresExtensions(new ArrayList<>());
    }
    final List<StackGresClusterInstalledExtension> installedExtensions =
        podStatus.getInstalledPostgresExtensions();
    LOGGER.info("Reconcile postgres extensions...");
    boolean clusterUpdated = false;
    final List<StackGresClusterInstalledExtension> extensionToUninstall = installedExtensions
        .stream()
        .filter(installedExtension -> toInstallExtensions.stream()
            .noneMatch(installedExtension::same))
        .collect(Collectors.toList());

    for (StackGresClusterInstalledExtension installedExtension : extensionToUninstall) {
      try {
        ExtensionUninstaller extensionUninstaller = extensionManager.getExtensionUninstaller(
            context, installedExtension);
        if (!skipSharedLibrariesOverwrites) {
          if (extensionUninstaller.isExtensionInstalled()) {
            LOGGER.info("Removing extension {}",
                ExtensionUtil.getDescription(cluster, installedExtension, true));
            extensionEventEmitter.emitExtensionRemoved(installedExtension);
            extensionUninstaller.uninstallExtension();
          }
          installedExtensions.remove(installedExtension);
          clusterUpdated = true;
        } else {
          LOGGER.info("Skip uninstallation of extension {}",
              ExtensionUtil.getDescription(cluster, installedExtension, true));
          if (!Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
            podStatus.setPendingRestart(true);
            clusterUpdated = true;
          }
        }
      } catch (Exception ex) {
        exceptions.add(ex);
        onUninstallException(client, cluster,
            ExtensionUtil.getDescription(cluster, installedExtension, true),
            podName, ex);
      }
    }
    for (StackGresClusterInstalledExtension toInstallExtension : toInstallExtensions) {
      final StackGresClusterInstalledExtension extension;
      try {
        Optional<StackGresClusterInstalledExtension> installedExtension =
            podStatus.getInstalledPostgresExtensions().stream().filter(
                toInstallExtension::equals).findFirst();
        if (installedExtension.isPresent()) {
          extension = installedExtension.get();
        } else {
          StackGresClusterExtension clusterExtension = new StackGresClusterExtension();
          clusterExtension.setName(toInstallExtension.getName());
          clusterExtension.setPublisher(toInstallExtension.getPublisher());
          clusterExtension.setRepository(toInstallExtension.getRepository());
          clusterExtension.setVersion(toInstallExtension.getVersion());
          StackGresExtensionMetadata candidateExtension =
              extensionManager.getMetadataManager()
              .getExtensionCandidateSameMajorBuild(cluster, clusterExtension, true);
          extension = ExtensionUtil.getInstalledExtension(
              cluster, clusterExtension, candidateExtension, true);
          LOGGER.info("Detected extension {}",
              ExtensionUtil.getDescription(cluster, extension, true));
        }
      } catch (Exception ex) {
        LOGGER.warn("Can not detected extension {}",
            ExtensionUtil.getDescription(cluster, toInstallExtension, true));
        exceptions.add(ex);
        onInstallException(client, cluster, ExtensionUtil.getDescription(
            cluster, toInstallExtension, true),
            podName, ex);
        continue;
      }
      try {
        final ExtensionInstaller extensionInstaller = Optional.ofNullable(
            extensionManager.getExtensionInstaller(context, extension))
            .orElseThrow(() -> new IllegalStateException(
                "Can not find extension "
                    + ExtensionUtil.getDescription(cluster, extension, true)));
        if (!extensionInstaller.isExtensionInstalled()
            && (!skipSharedLibrariesOverwrites
                || !extensionInstaller.isExtensionPendingOverwrite())) {
          LOGGER.info("Download extension {}",
              ExtensionUtil.getDescription(cluster, extension, true));
          extensionEventEmitter.emitExtensionDownloading(extension);
          extensionInstaller.downloadAndExtract();
          LOGGER.info("Verify extension {}",
              ExtensionUtil.getDescription(cluster, extension, true));
          extensionInstaller.verify();
          if (skipSharedLibrariesOverwrites
              && extensionInstaller.doesInstallOverwriteAnySharedFile()) {
            LOGGER.info("Skip installation of extension {}",
                ExtensionUtil.getDescription(cluster, extension, true));
            if (!extensionInstaller.isExtensionPendingOverwrite()) {
              extensionInstaller.setExtensionAsPending();
            }
            if (!Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
              extensionEventEmitter.emitExtensionDeployedRestart(extension);
              podStatus.setPendingRestart(true);
              clusterUpdated = true;
            }
          } else {
            LOGGER.info("Install extension {}",
                ExtensionUtil.getDescription(cluster, extension, true));
            extensionInstaller.installExtension();
            extensionEventEmitter.emitExtensionDeployed(extension);
          }
        }
        if (extensionInstaller.isExtensionInstalled()) {
          if (!extensionInstaller.areLinksCreated()) {
            LOGGER.info("Create links for extension {}",
                ExtensionUtil.getDescription(cluster, extension, true));
            extensionInstaller.createExtensionLinks();
          }
          if (installedExtensions
              .stream()
              .noneMatch(anInstalledExtension -> anInstalledExtension.equals(extension))) {
            installedExtensions.stream()
                .filter(anInstalledExtension -> anInstalledExtension.same(extension))
                .map(previousInstalledExtension -> {
                  LOGGER.info("Extension upgraded from {} to {}",
                      ExtensionUtil.getDescription(cluster, previousInstalledExtension, true),
                      ExtensionUtil.getDescription(cluster, extension, true));
                  extensionEventEmitter.emitExtensionChanged(
                      previousInstalledExtension, extension);
                  return previousInstalledExtension;
                })
                .findAny()
                .ifPresent(installedExtensions::remove);
            installedExtensions.add(extension);
            clusterUpdated = true;
          }
        }
      } catch (Exception ex) {
        exceptions.add(ex);
        onInstallException(client, cluster, ExtensionUtil.getDescription(cluster, extension, true),
            podName, ex);
      }
    }
    if (!skipSharedLibrariesOverwrites
        && Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
      podStatus.setPendingRestart(false);
      clusterUpdated = true;
    }
    LOGGER.info("Reconciliation of postgres extensions completed");
    return new ReconciliationResult<>(clusterUpdated, exceptions.build());
  }

  protected abstract void onUninstallException(KubernetesClient client, StackGresCluster cluster,
      String extension, String podName, Exception ex);

  protected abstract void onInstallException(KubernetesClient client, StackGresCluster cluster,
      String extension, String podName, Exception ex);

}
