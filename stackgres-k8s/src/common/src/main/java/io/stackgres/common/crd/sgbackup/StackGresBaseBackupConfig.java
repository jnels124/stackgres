/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgbackup;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.crd.sgcluster.StackGresClusterBackupEncryption;
import io.sundr.builder.annotations.Buildable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
@Buildable(editableEnabled = false, validationEnabled = false, generateBuilderPackage = false,
    lazyCollectionInitEnabled = false, lazyMapInitEnabled = false,
    builderPackage = "io.fabric8.kubernetes.api.builder")
public class StackGresBaseBackupConfig {

  @Positive(message = "retention must be greater than zero")
  private Integer retention;

  private String cronSchedule;

  private String compression;

  @Valid
  private StackGresBaseBackupPerformance performance;

  @Valid
  private StackGresClusterBackupEncryption encryption;

  public Integer getRetention() {
    return retention;
  }

  public void setRetention(Integer retention) {
    this.retention = retention;
  }

  public String getCronSchedule() {
    return cronSchedule;
  }

  public void setCronSchedule(String cronSchedule) {
    this.cronSchedule = cronSchedule;
  }

  public String getCompression() {
    return compression;
  }

  public void setCompression(String compression) {
    this.compression = compression;
  }

  public StackGresBaseBackupPerformance getPerformance() {
    return performance;
  }

  public void setPerformance(StackGresBaseBackupPerformance performance) {
    this.performance = performance;
  }

  public StackGresClusterBackupEncryption getEncryption() {
    return encryption;
  }

  public void setEncryption(StackGresClusterBackupEncryption encryption) {
    this.encryption = encryption;
  }

  @Override
  public int hashCode() {
    return Objects.hash(compression, cronSchedule, performance, retention, encryption);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StackGresBaseBackupConfig)) {
      return false;
    }
    StackGresBaseBackupConfig other = (StackGresBaseBackupConfig) obj;
    return Objects.equals(compression, other.compression)
        && Objects.equals(cronSchedule, other.cronSchedule)
        && Objects.equals(performance, other.performance)
        && Objects.equals(retention, other.retention)
        && Objects.equals(encryption, other.encryption);
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
