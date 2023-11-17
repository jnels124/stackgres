/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.storages;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;
import io.sundr.builder.annotations.Buildable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
@Buildable(editableEnabled = false, validationEnabled = false, generateBuilderPackage = false,
    lazyCollectionInitEnabled = false, lazyMapInitEnabled = false,
    builderPackage = "io.fabric8.kubernetes.api.builder")
public class GoogleCloudCredentials {

  private boolean fetchCredentialsFromMetadataService;

  @Valid
  private GoogleCloudSecretKeySelector secretKeySelectors;

  @JsonIgnore
  @AssertTrue(message = "The secretKeySelectors is required if fetchCredentialsFromMetadataService"
      + " is false")
  public boolean isSecretKeySelectorsSetIfFetchCredentialsFromMetadataServiceiSFalse() {
    return secretKeySelectors != null || fetchCredentialsFromMetadataService;
  }

  public boolean isFetchCredentialsFromMetadataService() {
    return fetchCredentialsFromMetadataService;
  }

  public void setFetchCredentialsFromMetadataService(boolean fetchCredentialsFromMetadataService) {
    this.fetchCredentialsFromMetadataService = fetchCredentialsFromMetadataService;
  }

  public GoogleCloudSecretKeySelector getSecretKeySelectors() {
    return secretKeySelectors;
  }

  public void setSecretKeySelectors(GoogleCloudSecretKeySelector secretKeySelectors) {
    this.secretKeySelectors = secretKeySelectors;
  }

  @Override
  public int hashCode() {
    return Objects.hash(fetchCredentialsFromMetadataService, secretKeySelectors);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof GoogleCloudCredentials)) {
      return false;
    }
    GoogleCloudCredentials other = (GoogleCloudCredentials) obj;
    return fetchCredentialsFromMetadataService == other.fetchCredentialsFromMetadataService
        && Objects.equals(secretKeySelectors, other.secretKeySelectors);
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }
}
