/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.app;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersion;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.stackgres.common.CrdLoader;
import io.stackgres.common.StackGresContext;
import io.stackgres.common.StackGresVersion;
import io.stackgres.common.YamlMapperProvider;
import io.stackgres.common.resource.ResourceFinder;
import io.stackgres.common.resource.ResourceWriter;
import org.jetbrains.annotations.NotNull;
import org.jooq.lambda.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class CrdInstaller {

  private static final Logger LOGGER = LoggerFactory.getLogger(CrdInstaller.class);

  private final ResourceFinder<CustomResourceDefinition> crdResourceFinder;
  private final ResourceWriter<CustomResourceDefinition> crdResourceWriter;
  private final CrdLoader crdLoader;
  private final KubernetesClient client;

  @Inject
  public CrdInstaller(
      ResourceFinder<CustomResourceDefinition> crdResourceFinder,
      ResourceWriter<CustomResourceDefinition> crdResourceWriter,
      YamlMapperProvider yamlMapperProvider,
      KubernetesClient client) {
    this.crdResourceFinder = crdResourceFinder;
    this.crdResourceWriter = crdResourceWriter;
    this.client = client;
    this.crdLoader = new CrdLoader(yamlMapperProvider.get());
  }

  public void checkUpgrade() {
    var resourcesToUpgrade = crdLoader.scanCrds().stream()
        .map(crd -> crdResourceFinder.findByName(crd.getMetadata().getName()))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .flatMap(crd -> client
          .genericKubernetesResources(CustomResourceDefinitionContext.fromCrd(crd))
          .inAnyNamespace()
          .list()
          .getItems()
          .stream()
          .map(cluster -> Tuple.tuple(cluster, Optional.of(cluster)
                .map(StackGresVersion::getStackGresVersionFromResourceAsNumber)
                .map(Long.valueOf(StackGresVersion.OLDEST.getVersionAsNumber())::compareTo)
                .filter(comparison -> comparison > 0)
                .map(result -> "version at " + cluster.getMetadata().getAnnotations()
                    .get(StackGresContext.VERSION_KEY))
                .orElse("")))
          .filter(t -> !t.v2.isEmpty()))
        .toList();
    if (!resourcesToUpgrade.isEmpty()) {
      throw new RuntimeException("Can not upgrade due to some resources still at version"
          + " older than \"" + StackGresVersion.OLDEST.getVersion() + "\"."
          + " Please, downgrade to a previous version of the operator and run a SGDbOps of"
          + " type securityUpgrade on all the SGClusters of the following list"
          + " (if any is present):\n"
          + resourcesToUpgrade.stream()
          .map(t -> t.v1.getKind() + " "
              + t.v1.getMetadata().getNamespace() + "."
              + t.v1.getMetadata().getName() + ": " + t.v2)
          .collect(Collectors.joining("\n")));
    }
  }

  public void installCustomResourceDefinitions() {
    LOGGER.info("Installing CRDs");
    crdLoader.scanCrds()
        .forEach(this::installCrd);
  }

  protected void installCrd(@NotNull CustomResourceDefinition currentCrd) {
    String name = currentCrd.getMetadata().getName();
    LOGGER.info("Installing CRD " + name);
    Optional<CustomResourceDefinition> installedCrdOpt = crdResourceFinder
        .findByName(name);

    if (installedCrdOpt.isPresent()) {
      LOGGER.debug("CRD {} is present, patching it", name);
      CustomResourceDefinition installedCrd = installedCrdOpt.get();
      if (!isCurrentCrdInstalled(currentCrd, installedCrd)) {
        upgradeCrd(currentCrd, installedCrd);
      }
      updateAlreadyInstalledVersions(currentCrd, installedCrd);
      crdResourceWriter.update(installedCrd);
    } else {
      LOGGER.info("CRD {} is not present, installing it", name);
      crdResourceWriter.create(currentCrd);
    }
  }

  private void updateAlreadyInstalledVersions(CustomResourceDefinition currentCrd,
      CustomResourceDefinition installedCrd) {
    installedCrd.getSpec().getVersions().forEach(installedVersion -> {
      currentCrd.getSpec()
          .getVersions()
          .stream()
          .filter(v -> v.getName().equals(installedVersion.getName()))
          .forEach(currentVersion -> updateAlreadyDeployedVersion(
              installedVersion, currentVersion));
    });
  }

  private void updateAlreadyDeployedVersion(CustomResourceDefinitionVersion installedVersion,
      CustomResourceDefinitionVersion currentVersion) {
    installedVersion.setSchema(currentVersion.getSchema());
    installedVersion.setSubresources(currentVersion.getSubresources());
    installedVersion.setAdditionalPrinterColumns(currentVersion.getAdditionalPrinterColumns());
  }

  private void upgradeCrd(
      CustomResourceDefinition currentCrd,
      CustomResourceDefinition installedCrd) {
    disableStorageVersions(installedCrd);
    addNewSchemaVersions(currentCrd, installedCrd);
    crdResourceWriter.update(installedCrd);
  }

  private void disableStorageVersions(CustomResourceDefinition installedCrd) {
    installedCrd.getSpec().getVersions()
        .forEach(versionDefinition -> versionDefinition.setStorage(false));
  }

  private void addNewSchemaVersions(
      CustomResourceDefinition currentCrd,
      CustomResourceDefinition installedCrd) {
    List<String> installedVersions = installedCrd.getSpec().getVersions()
        .stream()
        .map(CustomResourceDefinitionVersion::getName)
        .toList();

    List<String> versionsToInstall = currentCrd.getSpec().getVersions()
        .stream()
        .map(CustomResourceDefinitionVersion::getName)
        .filter(Predicate.not(installedVersions::contains))
        .toList();

    currentCrd.getSpec().getVersions().stream()
        .filter(version -> versionsToInstall.contains(version.getName()))
        .forEach(installedCrd.getSpec().getVersions()::add);
  }

  private boolean isCurrentCrdInstalled(
      CustomResourceDefinition currentCrd,
      CustomResourceDefinition installedCrd) {
    final String currentVersion = currentCrd.getSpec().getVersions()
        .stream()
        .filter(CustomResourceDefinitionVersion::getStorage).findFirst()
        .orElseThrow(() -> new RuntimeException("At least one CRD version must be stored"))
        .getName();
    return installedCrd.getSpec().getVersions().stream()
        .map(CustomResourceDefinitionVersion::getName)
        .anyMatch(installedVersion -> installedVersion.equals(currentVersion));
  }

  public void checkCustomResourceDefinitions() {
    crdLoader.scanCrds()
        .forEach(this::checkCrd);
  }

  protected void checkCrd(@NotNull CustomResourceDefinition currentCrd) {
    String name = currentCrd.getMetadata().getName();
    Optional<CustomResourceDefinition> installedCrdOpt = crdResourceFinder
        .findByName(name);

    if (installedCrdOpt.isEmpty()) {
      throw new RuntimeException("CRD " + name + " was not found.");
    }
  }

}
