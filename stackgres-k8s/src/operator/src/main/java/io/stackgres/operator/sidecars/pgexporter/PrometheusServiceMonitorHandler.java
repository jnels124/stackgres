/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.sidecars.pgexporter;

import java.util.Optional;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;

import com.google.common.collect.ImmutableList;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.stackgres.operator.common.Kind;
import io.stackgres.operator.common.StackGresClusterConfig;
import io.stackgres.operator.resource.ResourceHandler;
import io.stackgres.operator.resource.ResourceUtil;
import io.stackgres.operator.sidecars.pgexporter.customresources.Endpoint;
import io.stackgres.operator.sidecars.pgexporter.customresources.NamespaceSelector;
import io.stackgres.operator.sidecars.pgexporter.customresources.ServiceMonitor;
import io.stackgres.operator.sidecars.pgexporter.customresources.ServiceMonitorDefinition;
import io.stackgres.operator.sidecars.pgexporter.customresources.ServiceMonitorDoneable;
import io.stackgres.operator.sidecars.pgexporter.customresources.ServiceMonitorList;
import io.stackgres.operator.sidecars.pgexporter.customresources.ServiceMonitorSpec;
import io.stackgres.operatorframework.resource.PairVisitor;
import io.stackgres.operatorframework.resource.ResourcePairVisitor;

@Kind("ServiceMonitor")
@ApplicationScoped
public class PrometheusServiceMonitorHandler implements ResourceHandler {

  @Override
  public boolean equals(HasMetadata existingResource, HasMetadata requiredResource) {
    return ResourcePairVisitor.equals(new ServiceMonitorVisitor<>(),
        existingResource, requiredResource);
  }

  @Override
  public HasMetadata update(HasMetadata existingResource, HasMetadata requiredResource) {
    return ResourcePairVisitor.update(new ServiceMonitorVisitor<>(),
        existingResource, requiredResource);
  }

  @Override
  public void registerKind() {
    KubernetesDeserializer.registerCustomKind(ServiceMonitorDefinition.APIVERSION,
        ServiceMonitorDefinition.KIND, ServiceMonitor.class);
  }

  @Override
  public Stream<HasMetadata> getOrphanResources(KubernetesClient client,
      ImmutableList<StackGresClusterConfig> existingConfigs) {
    return getServiceMonitorClient(client)
        .map(crClient -> crClient
            .inAnyNamespace()
            .withLabels(ResourceUtil.defaultLabels())
            .withLabelNotIn(ResourceUtil.CLUSTER_NAME_KEY, existingConfigs.stream()
                .map(config -> config.getCluster().getMetadata().getName())
                .toArray(String[]::new))
            .list()
            .getItems()
            .stream()
            .map(cr -> (HasMetadata) cr))
        .orElse(Stream.empty());
  }

  @Override
  public Stream<HasMetadata> getResources(KubernetesClient client,
      StackGresClusterConfig config) {
    return getServiceMonitorClient(client)
        .map(crClient -> crClient
            .inAnyNamespace()
            .withLabels(ResourceUtil.defaultLabels(config.getCluster().getMetadata().getName()))
            .withLabel(PostgresExporter.CLUSTER_NAMESPACE_KEY,
                config.getCluster().getMetadata().getNamespace())
            .list()
            .getItems()
            .stream()
            .map(cr -> (HasMetadata) cr))
        .orElse(Stream.empty());
  }

  @Override
  public Optional<HasMetadata> find(KubernetesClient client, HasMetadata resource) {
    return getServiceMonitorClient(client)
        .flatMap(crClient -> Optional.ofNullable(crClient
            .inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName())
            .get()))
        .map(cr -> (HasMetadata) cr);
  }

  @Override
  public HasMetadata create(KubernetesClient client, HasMetadata resource) {
    return getServiceMonitorClient(client)
        .map(crClient -> crClient
            .create((ServiceMonitor) resource))
        .orElse(null);
  }

  @Override
  public HasMetadata patch(KubernetesClient client, HasMetadata resource) {
    return getServiceMonitorClient(client)
        .map(crClient -> crClient
            .inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName())
            .patch((ServiceMonitor) resource))
        .orElse(null);
  }

  @Override
  public boolean delete(KubernetesClient client, HasMetadata resource) {
    return getServiceMonitorClient(client)
        .map(crClient -> crClient
            .inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName())
            .delete())
        .orElse(null);
  }

  private Optional<MixedOperation<ServiceMonitor, ServiceMonitorList, ServiceMonitorDoneable,
      Resource<ServiceMonitor, ServiceMonitorDoneable>>> getServiceMonitorClient(
          KubernetesClient client) {
    return ResourceUtil.getCustomResource(client, ServiceMonitorDefinition.NAME)
        .map(crd -> client.customResources(crd,
            ServiceMonitor.class,
            ServiceMonitorList.class,
            ServiceMonitorDoneable.class));
  }

  private class ServiceMonitorVisitor<T> extends ResourcePairVisitor<T> {

    @Override
    public PairVisitor<HasMetadata, T> visit(
        PairVisitor<HasMetadata, T> pairVisitor) {
      return pairVisitor.visit()
          .visit(HasMetadata::getApiVersion, HasMetadata::setApiVersion)
          .visit(HasMetadata::getKind)
          .visitWith(HasMetadata::getMetadata, HasMetadata::setMetadata,
              this::visitMetadata)
          .lastVisit(this::visitServiceMonitor);
    }

    public PairVisitor<ServiceMonitor, T> visitServiceMonitor(
        PairVisitor<ServiceMonitor, T> pairVisitor) {
      return pairVisitor.visit()
          .visitWith(ServiceMonitor::getSpec, ServiceMonitor::setSpec,
              this::visitServiceMonitorSpec);
    }

    public PairVisitor<ServiceMonitorSpec, T> visitServiceMonitorSpec(
        PairVisitor<ServiceMonitorSpec, T> pairVisitor) {
      return pairVisitor.visit()
          .visitListWith(ServiceMonitorSpec::getEndpoints, ServiceMonitorSpec::setEndpoints,
              this::visitEndpoint)
          .visitWith(ServiceMonitorSpec::getNamespaceSelector,
              ServiceMonitorSpec::setNamespaceSelector,
              this::visitNamespaceSelector)
          .visitWith(ServiceMonitorSpec::getSelector,
              ServiceMonitorSpec::setSelector,
              this::visitLabelSelector);
    }

    public PairVisitor<Endpoint, T> visitEndpoint(
        PairVisitor<Endpoint, T> pairVisitor) {
      return pairVisitor.visit()
          .visit(Endpoint::getPort, Endpoint::setPort);
    }

    public PairVisitor<NamespaceSelector, T> visitNamespaceSelector(
        PairVisitor<NamespaceSelector, T> pairVisitor) {
      return pairVisitor.visit()
          .visit(NamespaceSelector::getAny, NamespaceSelector::setAny)
          .visitList(NamespaceSelector::getMatchNames, NamespaceSelector::setMatchNames);
    }

  }

}
