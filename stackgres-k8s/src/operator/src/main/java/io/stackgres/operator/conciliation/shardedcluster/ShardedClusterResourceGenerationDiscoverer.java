/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.shardedcluster;

import io.stackgres.operator.conciliation.AbstractResourceDiscoverer;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.factory.DecoratorDiscoverer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class ShardedClusterResourceGenerationDiscoverer
    extends AbstractResourceDiscoverer<StackGresShardedClusterContext> {

  @Inject
  public ShardedClusterResourceGenerationDiscoverer(
      @Any Instance<ResourceGenerator<StackGresShardedClusterContext>> instance,
      DecoratorDiscoverer<StackGresShardedClusterContext> decoratorDiscoverer) {
    super(instance, decoratorDiscoverer);
  }

}
