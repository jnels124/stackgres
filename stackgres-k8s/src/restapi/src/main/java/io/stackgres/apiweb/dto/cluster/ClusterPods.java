/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.dto.cluster;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.crd.CustomContainer;
import io.stackgres.common.crd.CustomVolume;
import io.stackgres.common.crd.CustomVolumeMount;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
//TODO remove once the UI has fixes the sending metadata in this object
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterPods {

  private ClusterPodsPersistentVolume persistentVolume;

  private Boolean disableConnectionPooling;

  private Boolean disableMetricsExporter;

  private Boolean disablePostgresUtil;

  private String managementPolicy;

  private ClusterResources resources;

  private ClusterPodsScheduling scheduling;

  private List<CustomVolume> customVolumes;

  private List<CustomContainer> customContainers;

  private List<CustomContainer> customInitContainers;

  private Map<String, List<CustomVolumeMount>> customVolumeMounts;

  private Map<String, List<CustomVolumeMount>> customInitVolumeMounts;

  public ClusterPodsPersistentVolume getPersistentVolume() {
    return persistentVolume;
  }

  public void setPersistentVolume(ClusterPodsPersistentVolume persistentVolume) {
    this.persistentVolume = persistentVolume;
  }

  public Boolean getDisableConnectionPooling() {
    return disableConnectionPooling;
  }

  public void setDisableConnectionPooling(Boolean disableConnectionPooling) {
    this.disableConnectionPooling = disableConnectionPooling;
  }

  public Boolean getDisableMetricsExporter() {
    return disableMetricsExporter;
  }

  public void setDisableMetricsExporter(Boolean disableMetricsExporter) {
    this.disableMetricsExporter = disableMetricsExporter;
  }

  public Boolean getDisablePostgresUtil() {
    return disablePostgresUtil;
  }

  public void setDisablePostgresUtil(Boolean disablePostgresUtil) {
    this.disablePostgresUtil = disablePostgresUtil;
  }

  public String getManagementPolicy() {
    return managementPolicy;
  }

  public void setManagementPolicy(String managementPolicy) {
    this.managementPolicy = managementPolicy;
  }

  public ClusterResources getResources() {
    return resources;
  }

  public void setResources(ClusterResources resources) {
    this.resources = resources;
  }

  public ClusterPodsScheduling getScheduling() {
    return scheduling;
  }

  public void setScheduling(ClusterPodsScheduling scheduling) {
    this.scheduling = scheduling;
  }

  public List<CustomVolume> getCustomVolumes() {
    return customVolumes;
  }

  public void setCustomVolumes(List<CustomVolume> customVolumes) {
    this.customVolumes = customVolumes;
  }

  public List<CustomContainer> getCustomContainers() {
    return customContainers;
  }

  public void setCustomContainers(List<CustomContainer> customContainers) {
    this.customContainers = customContainers;
  }

  public List<CustomContainer> getCustomInitContainers() {
    return customInitContainers;
  }

  public void setCustomInitContainers(List<CustomContainer> customInitContainers) {
    this.customInitContainers = customInitContainers;
  }

  public Map<String, List<CustomVolumeMount>> getCustomVolumeMounts() {
    return customVolumeMounts;
  }

  public void setCustomVolumeMounts(Map<String, List<CustomVolumeMount>> customVolumeMounts) {
    this.customVolumeMounts = customVolumeMounts;
  }

  public Map<String, List<CustomVolumeMount>> getCustomInitVolumeMounts() {
    return customInitVolumeMounts;
  }

  public void setCustomInitVolumeMounts(Map<String, List<CustomVolumeMount>> customInitVolumeMounts) {
    this.customInitVolumeMounts = customInitVolumeMounts;
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
