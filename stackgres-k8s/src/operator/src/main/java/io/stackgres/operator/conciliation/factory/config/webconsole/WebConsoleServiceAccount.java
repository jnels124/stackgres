/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.config.webconsole;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.stackgres.common.crd.sgconfig.StackGresConfig;
import io.stackgres.common.crd.sgconfig.StackGresConfigDeploy;
import io.stackgres.common.crd.sgconfig.StackGresConfigServiceAccount;
import io.stackgres.common.crd.sgconfig.StackGresConfigSpec;
import io.stackgres.common.labels.LabelFactoryForConfig;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.config.StackGresConfigContext;
import org.jetbrains.annotations.NotNull;

@Singleton
@OperatorVersionBinder
public class WebConsoleServiceAccount
    implements ResourceGenerator<StackGresConfigContext> {

  private final LabelFactoryForConfig labelFactory;

  @Inject
  public WebConsoleServiceAccount(LabelFactoryForConfig labelFactory) {
    this.labelFactory = labelFactory;
  }

  /**
   * Create the Secret for Web Console.
   */
  @Override
  public @NotNull Stream<HasMetadata> generateResource(StackGresConfigContext context) {
    if (!Optional.ofNullable(context.getSource().getSpec())
        .map(StackGresConfigSpec::getDeploy)
        .map(StackGresConfigDeploy::getRestapi)
        .orElse(true)) {
      return Stream.of();
    }

    final StackGresConfig config = context.getSource();
    final String namespace = config.getMetadata().getNamespace();
    final Map<String, String> labels = labelFactory.genericLabels(config);

    return Stream.of(new ServiceAccountBuilder()
        .withNewMetadata()
        .withNamespace(namespace)
        .withName(WebConsoleDeployment.name(config))
        .withLabels(labels)
        .withAnnotations(Optional.of(config.getSpec())
            .map(StackGresConfigSpec::getServiceAccount)
            .map(StackGresConfigServiceAccount::getAnnotations)
            .orElse(null))
        .endMetadata()
        .withImagePullSecrets(Optional.of(config.getSpec())
            .map(StackGresConfigSpec::getServiceAccount)
            .map(StackGresConfigServiceAccount::getRepoCredentials)
            .stream()
            .flatMap(List::stream)
            .map(LocalObjectReference::new)
            .toList())
        .build());
  }

}
