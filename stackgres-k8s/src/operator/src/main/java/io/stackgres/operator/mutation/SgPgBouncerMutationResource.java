/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.mutation;

import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.quarkus.runtime.StartupEvent;
import io.stackgres.operator.common.PgBouncerReview;
import io.stackgres.operatorframework.AdmissionReviewResponse;
import io.stackgres.operatorframework.JsonPatchMutationPipeline;
import io.stackgres.operatorframework.MutationResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path(MutationUtil.CONNPOOLCONFIG_MUTATION_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SgPgBouncerMutationResource implements MutationResource<PgBouncerReview> {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(SgPgBouncerMutationResource.class);

  private JsonPatchMutationPipeline<PgBouncerReview> pipeline;

  @Inject
  public SgPgBouncerMutationResource(JsonPatchMutationPipeline<PgBouncerReview> pipeline) {
    this.pipeline = pipeline;
  }

  void onStart(@Observes StartupEvent ev) {
    LOGGER.info("PgBouncer configuration mutation resource started");
  }

  @POST
  @Override
  public AdmissionReviewResponse mutate(PgBouncerReview admissionReview) {
    return mutate(admissionReview, pipeline);
  }
}
