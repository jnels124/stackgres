/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.dto.dbops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class DbOpsSpec {

  @JsonProperty("sgCluster")
  private String sgCluster;

  @JsonProperty("op")
  private String op;

  @JsonProperty("runAt")
  private String runAt;

  @JsonProperty("timeout")
  private String timeout;

  @JsonProperty("maxRetries")
  private Integer maxRetries;

  @JsonProperty("benchmark")
  private DbOpsBenchmark benchmark;

  @JsonProperty("vacuum")
  private DbOpsVacuum vacuum;

  @JsonProperty("repack")
  private DbOpsRepack repack;

  @JsonProperty("majorVersionUpgrade")
  private DbOpsMajorVersionUpgrade majorVersionUpgrade;

  public String getSgCluster() {
    return sgCluster;
  }

  public void setSgCluster(String sgCluster) {
    this.sgCluster = sgCluster;
  }

  public String getOp() {
    return op;
  }

  public void setOp(String op) {
    this.op = op;
  }

  public String getRunAt() {
    return runAt;
  }

  public void setRunAt(String runAt) {
    this.runAt = runAt;
  }

  public String getTimeout() {
    return timeout;
  }

  public void setTimeout(String timeout) {
    this.timeout = timeout;
  }

  public Integer getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(Integer maxRetries) {
    this.maxRetries = maxRetries;
  }

  public DbOpsBenchmark getBenchmark() {
    return benchmark;
  }

  public void setBenchmark(DbOpsBenchmark benchmark) {
    this.benchmark = benchmark;
  }

  public DbOpsVacuum getVacuum() {
    return vacuum;
  }

  public void setVacuum(DbOpsVacuum vacuum) {
    this.vacuum = vacuum;
  }

  public DbOpsRepack getRepack() {
    return repack;
  }

  public void setRepack(DbOpsRepack repack) {
    this.repack = repack;
  }

  public DbOpsMajorVersionUpgrade getMajorVersionUpgrade() {
    return majorVersionUpgrade;
  }

  public void setMajorVersionUpgrade(DbOpsMajorVersionUpgrade majorVersionUpgrade) {
    this.majorVersionUpgrade = majorVersionUpgrade;
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
