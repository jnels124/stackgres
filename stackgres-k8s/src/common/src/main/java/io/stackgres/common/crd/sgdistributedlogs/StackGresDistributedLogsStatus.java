/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgdistributedlogs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.crd.Condition;
import io.stackgres.common.crd.sgcluster.StackGresClusterPodStatus;
import io.sundr.builder.annotations.Buildable;
import jakarta.validation.Valid;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
@Buildable(editableEnabled = false, validationEnabled = false, generateBuilderPackage = false,
    lazyCollectionInitEnabled = false, lazyMapInitEnabled = false,
    builderPackage = "io.fabric8.kubernetes.api.builder")
public class StackGresDistributedLogsStatus {

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @Valid
  private List<Condition> conditions = new ArrayList<>();

  @Valid
  private List<StackGresClusterPodStatus> podStatuses;

  @Valid
  private List<StackGresDistributedLogsStatusDatabase> databases = new ArrayList<>();

  @Valid
  private List<StackGresDistributedLogsStatusCluster> connectedClusters = new ArrayList<>();

  private String postgresVersion;

  private String timescaledbVersion;

  private String fluentdConfigHash;

  private String arch;

  private String os;

  private String labelPrefix;

  private Boolean oldConfigMapRemoved;

  public List<Condition> getConditions() {
    return conditions;
  }

  public void setConditions(List<Condition> conditions) {
    this.conditions = conditions;
  }

  public List<StackGresClusterPodStatus> getPodStatuses() {
    return podStatuses;
  }

  public void setPodStatuses(List<StackGresClusterPodStatus> podStatuses) {
    this.podStatuses = podStatuses;
  }

  public List<StackGresDistributedLogsStatusDatabase> getDatabases() {
    return databases;
  }

  public void setDatabases(List<StackGresDistributedLogsStatusDatabase> databases) {
    this.databases = databases;
  }

  public List<StackGresDistributedLogsStatusCluster> getConnectedClusters() {
    return connectedClusters;
  }

  public void setConnectedClusters(List<StackGresDistributedLogsStatusCluster> connectedClusters) {
    this.connectedClusters = connectedClusters;
  }

  public String getPostgresVersion() {
    return postgresVersion;
  }

  public void setPostgresVersion(String postgresVersion) {
    this.postgresVersion = postgresVersion;
  }

  public String getTimescaledbVersion() {
    return timescaledbVersion;
  }

  public void setTimescaledbVersion(String timescaledbVersion) {
    this.timescaledbVersion = timescaledbVersion;
  }

  public String getFluentdConfigHash() {
    return fluentdConfigHash;
  }

  public void setFluentdConfigHash(String fluentdConfigHash) {
    this.fluentdConfigHash = fluentdConfigHash;
  }

  public String getArch() {
    return arch;
  }

  public void setArch(String arch) {
    this.arch = arch;
  }

  public String getOs() {
    return os;
  }

  public void setOs(String os) {
    this.os = os;
  }

  public String getLabelPrefix() {
    return labelPrefix;
  }

  public void setLabelPrefix(String labelPrefix) {
    this.labelPrefix = labelPrefix;
  }

  public Boolean getOldConfigMapRemoved() {
    return oldConfigMapRemoved;
  }

  public void setOldConfigMapRemoved(Boolean oldConfigMapRemoved) {
    this.oldConfigMapRemoved = oldConfigMapRemoved;
  }

  @Override
  public int hashCode() {
    return Objects.hash(arch, conditions, connectedClusters, databases, fluentdConfigHash,
        labelPrefix, oldConfigMapRemoved, os, podStatuses, postgresVersion, timescaledbVersion);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StackGresDistributedLogsStatus)) {
      return false;
    }
    StackGresDistributedLogsStatus other = (StackGresDistributedLogsStatus) obj;
    return Objects.equals(arch, other.arch) && Objects.equals(conditions, other.conditions)
        && Objects.equals(connectedClusters, other.connectedClusters)
        && Objects.equals(databases, other.databases)
        && Objects.equals(fluentdConfigHash, other.fluentdConfigHash)
        && Objects.equals(labelPrefix, other.labelPrefix)
        && Objects.equals(oldConfigMapRemoved, other.oldConfigMapRemoved)
        && Objects.equals(os, other.os) && Objects.equals(podStatuses, other.podStatuses)
        && Objects.equals(postgresVersion, other.postgresVersion)
        && Objects.equals(timescaledbVersion, other.timescaledbVersion);
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
