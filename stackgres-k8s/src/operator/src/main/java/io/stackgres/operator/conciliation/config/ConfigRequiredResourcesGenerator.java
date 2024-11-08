/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.VersionInfo;
import io.stackgres.common.crd.external.prometheus.Prometheus;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterConfigurations;
import io.stackgres.common.crd.sgcluster.StackGresClusterObservability;
import io.stackgres.common.crd.sgcluster.StackGresClusterSpec;
import io.stackgres.common.crd.sgconfig.StackGresConfig;
import io.stackgres.common.crd.sgconfig.StackGresConfigCollector;
import io.stackgres.common.crd.sgconfig.StackGresConfigCollectorPrometheusOperator;
import io.stackgres.common.crd.sgconfig.StackGresConfigGrafana;
import io.stackgres.common.crd.sgconfig.StackGresConfigSpec;
import io.stackgres.common.labels.LabelFactoryForCluster;
import io.stackgres.common.resource.CustomResourceScanner;
import io.stackgres.common.resource.ResourceFinder;
import io.stackgres.common.resource.ResourceScanner;
import io.stackgres.operator.common.ObservedClusterContext;
import io.stackgres.operator.common.PrometheusContext;
import io.stackgres.operator.conciliation.RequiredResourceGenerator;
import io.stackgres.operator.conciliation.ResourceGenerationDiscoverer;
import io.stackgres.operator.conciliation.factory.config.OperatorSecret;
import io.stackgres.operator.conciliation.factory.config.collector.CollectorSecret;
import io.stackgres.operator.conciliation.factory.config.webconsole.WebConsoleAdminSecret;
import io.stackgres.operator.conciliation.factory.config.webconsole.WebConsoleDeployment;
import io.stackgres.operator.conciliation.factory.config.webconsole.WebConsoleGrafanaIntegrationJob;
import io.stackgres.operator.conciliation.factory.config.webconsole.WebConsoleSecret;
import io.stackgres.operatorframework.resource.ResourceUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ConfigRequiredResourcesGenerator
    implements RequiredResourceGenerator<StackGresConfig> {

  protected static final Logger LOGGER = LoggerFactory
      .getLogger(ConfigRequiredResourcesGenerator.class);

  private final Supplier<VersionInfo> kubernetesVersionSupplier;

  private final ResourceGenerationDiscoverer<StackGresConfigContext> discoverer;

  private final ResourceFinder<ServiceAccount> serviceAccountFinder;

  private final ResourceFinder<Secret> secretFinder;

  private final ResourceFinder<Job> jobFinder;

  private final ConfigGrafanaIntegrationChecker grafanaIntegrationChecker;

  private final CustomResourceScanner<StackGresCluster> clusterScanner;

  private final LabelFactoryForCluster labelFactoryForCluster;

  private final ResourceScanner<Pod> podScanner;

  private final CustomResourceScanner<Prometheus> prometheusScanner;

  @Inject
  public ConfigRequiredResourcesGenerator(
      Supplier<VersionInfo> kubernetesVersionSupplier,
      ResourceGenerationDiscoverer<StackGresConfigContext> discoverer,
      ResourceFinder<ServiceAccount> serviceAccountFinder,
      ResourceFinder<Secret> secretFinder,
      ResourceFinder<Job> jobFinder,
      ConfigGrafanaIntegrationChecker grafanaIntegrationChecker,
      CustomResourceScanner<StackGresCluster> clusterScanner,
      LabelFactoryForCluster labelFactoryForCluster,
      ResourceScanner<Pod> podScanner,
      CustomResourceScanner<Prometheus> prometheusScanner) {
    this.kubernetesVersionSupplier = kubernetesVersionSupplier;
    this.discoverer = discoverer;
    this.serviceAccountFinder = serviceAccountFinder;
    this.secretFinder = secretFinder;
    this.jobFinder = jobFinder;
    this.grafanaIntegrationChecker = grafanaIntegrationChecker;
    this.clusterScanner = clusterScanner;
    this.labelFactoryForCluster = labelFactoryForCluster;
    this.podScanner = podScanner;
    this.prometheusScanner = prometheusScanner;
  }

  @Override
  public List<HasMetadata> getRequiredResources(StackGresConfig config) {
    VersionInfo kubernetesVersion = kubernetesVersionSupplier.get();

    String namespace = config.getMetadata().getNamespace();
    Optional<Secret> operatorSecret = secretFinder.findByNameAndNamespace(
        OperatorSecret.name(config), namespace);
    Optional<Secret> webConsoleSecret = secretFinder.findByNameAndNamespace(
        WebConsoleSecret.name(config), namespace);
    Optional<Secret> webConsoleAdminSecret = secretFinder.findByNameAndNamespace(
        WebConsoleAdminSecret.sourceName(config), namespace);
    Optional<ServiceAccount> webConsoleServiceAccount = serviceAccountFinder.findByNameAndNamespace(
        WebConsoleDeployment.name(config), namespace);
    boolean isGrafanaEmbedded = grafanaIntegrationChecker.isGrafanaEmbedded(config);
    boolean isGrafanaIntegrated = grafanaIntegrationChecker.isGrafanaIntegrated(config);
    boolean isGrafanaIntegrationJobFailed =
        jobFinder.findByNameAndNamespace(WebConsoleGrafanaIntegrationJob.name(config), namespace)
        .map(Job::getStatus)
        .map(JobStatus::getFailed)
        .map(failed -> failed > 0)
        .orElse(false);
    Optional<Map<String, String>> grafanaCredentials = Optional.of(config.getSpec())
        .map(StackGresConfigSpec::getGrafana)
        .filter(grafana -> grafana.getSecretNamespace() != null
            && grafana.getSecretName() != null
            && grafana.getSecretUserKey() != null
            && grafana.getSecretPasswordKey() != null)
        .map(grafana -> secretFinder.findByNameAndNamespace(
            grafana.getSecretName(), grafana.getSecretNamespace())
            .orElseThrow(() -> new IllegalArgumentException(
                "Can not find secret "
                    + grafana.getSecretNamespace() + "." + grafana.getSecretName()
                    + " for grafana credentials")))
        .map(Secret::getData)
        .map(ResourceUtil::decodeSecret);
    Optional<String> grafanaUser = grafanaCredentials
        .map(credentials -> credentials.get(
            config.getSpec().getGrafana().getSecretUserKey()))
        .or(() -> Optional.of(config.getSpec())
            .map(StackGresConfigSpec::getGrafana)
            .map(StackGresConfigGrafana::getUser));
    Optional<String> grafanaPassword = grafanaCredentials
        .map(credentials -> credentials.get(
            config.getSpec().getGrafana().getSecretPasswordKey()))
        .or(() -> Optional.of(config.getSpec())
            .map(StackGresConfigSpec::getGrafana)
            .map(StackGresConfigGrafana::getPassword));

    Optional<Secret> collectorConsoleSecret = secretFinder.findByNameAndNamespace(
        CollectorSecret.name(config), namespace);
    final List<ObservedClusterContext> observerdClusters = clusterScanner.getResources()
        .stream()
        .filter(cluster -> Optional.of(cluster)
            .map(StackGresCluster::getSpec)
            .map(StackGresClusterSpec::getConfigurations)
            .map(StackGresClusterConfigurations::getObservability)
            .map(StackGresClusterObservability::getPrometheusAutobind)
            .orElse(false))
        .map(cluster -> ObservedClusterContext.toObservedClusterContext(
            cluster,
            podScanner.getResourcesInNamespaceWithLabels(
                cluster.getMetadata().getNamespace(),
                labelFactoryForCluster.clusterLabels(cluster))))
        .toList();

    StackGresConfigContext context = ImmutableStackGresConfigContext.builder()
        .kubernetesVersion(kubernetesVersion)
        .source(config)
        .operatorSecret(operatorSecret)
        .webConsoleSecret(webConsoleSecret)
        .webConsoleAdminSecret(webConsoleAdminSecret)
        .webConsoleServiceAccount(webConsoleServiceAccount)
        .isGrafanaEmbedded(isGrafanaEmbedded)
        .isGrafanaIntegrated(isGrafanaIntegrated)
        .isGrafanaIntegrationJobFailed(isGrafanaIntegrationJobFailed)
        .grafanaUser(grafanaUser)
        .grafanaPassword(grafanaPassword)
        .collectorSecret(collectorConsoleSecret)
        .observedClusters(observerdClusters)
        .prometheus(getPrometheus(config))
        .build();

    return discoverer.generateResources(context);
  }

  public List<PrometheusContext> getPrometheus(StackGresConfig config) {
    boolean isAutobindAllowed = Optional.of(config)
        .map(StackGresConfig::getSpec)
        .map(StackGresConfigSpec::getCollector)
        .map(StackGresConfigCollector::getPrometheusOperator)
        .map(StackGresConfigCollectorPrometheusOperator::getAllowDiscovery)
        .orElse(false);
    var monitors = Optional.of(config)
        .map(StackGresConfig::getSpec)
        .map(StackGresConfigSpec::getCollector)
        .map(StackGresConfigCollector::getPrometheusOperator)
        .map(StackGresConfigCollectorPrometheusOperator::getMonitors)
        .orElse(List.of());
        
    if (monitors.size() > 0) {
      LOGGER.trace("Prometheus monitors detected, looking for Prometheus resources");
      return prometheusScanner.findResources()
          .stream()
          .flatMap(List::stream)
          .filter(prometheus -> monitors.stream()
              .anyMatch(monitor -> monitor.getMetadata().getNamespace().equals(prometheus.getMetadata().getNamespace())
                  && monitor.getMetadata().getName().equals(prometheus.getMetadata().getName())))
          .map(prometheus -> PrometheusContext.toPrometheusContext(
              prometheus,
              monitors.stream()
              .filter(monitor -> monitor.getMetadata().getNamespace().equals(prometheus.getMetadata().getNamespace())
                  && monitor.getMetadata().getName().equals(prometheus.getMetadata().getName()))
              .findFirst()
              .orElseThrow()))
          .toList();
    } else if (isAutobindAllowed) {
      LOGGER.trace("Prometheus auto bind enabled, looking for Prometheus resources");

      return prometheusScanner.findResources()
          .stream()
          .flatMap(List::stream)
          .map(PrometheusContext::toPrometheusContext)
          .toList();
    } else {
      return List.of();
    }
  }

}
