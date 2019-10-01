/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 */

package io.stackgres.operator.validation.cluster;

import java.util.Optional;

import io.stackgres.common.customresource.sgprofile.StackGresProfile;
import io.stackgres.operator.services.KubernetesResourceFinder;
import io.stackgres.operator.utils.JsonUtil;
import io.stackgres.operator.validation.AdmissionReview;
import io.stackgres.operator.validation.Operation;
import io.stackgres.operator.validation.ValidationFailed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@RunWith(MockitoJUnitRunner.class)
class ProfileReferenceValidatorValidatorTest {

  private ProfileReferenceValidator validator;

  @Mock
  private KubernetesResourceFinder<StackGresProfile> profileFinder;

  private StackGresProfile xsProfile;

  @BeforeEach
  void setUp() throws Exception {
    validator = new ProfileReferenceValidator(profileFinder);

    xsProfile = JsonUtil.readFromJson("stackgres_profiles/size-xs.json",
        StackGresProfile.class);

  }

  @Test
  void givenValidStackgresReferenceOnCreation_shouldNotFail() throws ValidationFailed {

    final AdmissionReview review = JsonUtil
        .readFromJson("allowed_requests/valid_creation.json", AdmissionReview.class);

    String resourceProfile = review.getRequest().getObject().getSpec().getResourceProfile();
    when(profileFinder.findByName(resourceProfile))
        .thenReturn(Optional.of(xsProfile));

    validator.validate(review);

    verify(profileFinder).findByName(eq(resourceProfile));

  }

  @Test
  void giveInvalidStackgresReferenceOnCreation_shouldFail() {

    final AdmissionReview review = JsonUtil
        .readFromJson("allowed_requests/valid_creation.json", AdmissionReview.class);

    String resourceProfile = review.getRequest().getObject().getSpec().getResourceProfile();

    when(profileFinder.findByName(resourceProfile))
        .thenReturn(Optional.empty());

    ValidationFailed ex = assertThrows(ValidationFailed.class, () -> {
      validator.validate(review);
    });

    String resultMessage = ex.getMessage();

    assertEquals("Invalid profile " + resourceProfile, resultMessage);

  }

  @Test
  void giveAnAttemptToUpdateToAnUnknownProfile_shouldFail() {

    final AdmissionReview review = JsonUtil
        .readFromJson("allowed_requests/profile_config_update.json", AdmissionReview.class);

    String resourceProfile = review.getRequest().getObject().getSpec().getResourceProfile();

    when(profileFinder.findByName(resourceProfile))
        .thenReturn(Optional.empty());

    ValidationFailed ex = assertThrows(ValidationFailed.class, () -> {
      validator.validate(review);
    });

    String resultMessage = ex.getMessage();

    assertEquals("Cannot update to profile " + resourceProfile
        + " because it doesn't exists", resultMessage);

    verify(profileFinder).findByName(eq(resourceProfile));

  }

  @Test
  void giveAnAttemptToUpdateToAnKnownProfile_shouldNotFail() throws ValidationFailed {

    final AdmissionReview review = JsonUtil
        .readFromJson("allowed_requests/profile_config_update.json", AdmissionReview.class);

    String resourceProfile = review.getRequest().getObject().getSpec().getResourceProfile();

    StackGresProfile sProfile = JsonUtil.readFromJson("stackgres_profiles/size-s.json",
        StackGresProfile.class);

    when(profileFinder.findByName(resourceProfile))
        .thenReturn(Optional.of(sProfile));

    validator.validate(review);

    verify(profileFinder).findByName(eq(resourceProfile));

  }

  @Test
  void giveAnAttemptToDelete_shouldNotFail() throws ValidationFailed {

    final AdmissionReview review = JsonUtil
        .readFromJson("allowed_requests/profile_config_update.json", AdmissionReview.class);
    review.getRequest().setOperation(Operation.DELETE);

    String resourceProfile = review.getRequest().getObject().getSpec().getResourceProfile();

    validator.validate(review);

    verify(profileFinder, never()).findByName(eq(resourceProfile));

  }



}
