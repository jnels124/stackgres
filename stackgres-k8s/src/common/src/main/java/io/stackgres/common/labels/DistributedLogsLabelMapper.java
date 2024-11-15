/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.labels;

import java.util.Optional;

import io.stackgres.common.StackGresContext;
import io.stackgres.common.crd.sgdistributedlogs.StackGresDistributedLogs;
import io.stackgres.common.crd.sgdistributedlogs.StackGresDistributedLogsStatus;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DistributedLogsLabelMapper implements LabelMapperForDistributedLogs {

  @Override
  public String appName() {
    return StackGresContext.DISTRIBUTED_LOGS_APP_NAME;
  }

  @Override
  public String resourceNameKey(StackGresDistributedLogs resource) {
    return getKeyPrefix(resource) + StackGresContext.DISTRIBUTED_LOGS_CLUSTER_NAME_KEY;
  }

  @Override
  public String resourceNamespaceKey(StackGresDistributedLogs resource) {
    return getKeyPrefix(resource) + StackGresContext.DISTRIBUTED_LOGS_CLUSTER_NAMESPACE_KEY;
  }

  @Override
  public String resourceUidKey(StackGresDistributedLogs resource) {
    return getKeyPrefix(resource) + StackGresContext.DISTRIBUTED_LOGS_CLUSTER_UID_KEY;
  }

  @Override
  public String getKeyPrefix(StackGresDistributedLogs resource) {
    return Optional.of(resource)
        .map(StackGresDistributedLogs::getStatus)
        .map(StackGresDistributedLogsStatus::getLabelPrefix)
        .orElse(StackGresContext.STACKGRES_KEY_PREFIX);
  }

}
