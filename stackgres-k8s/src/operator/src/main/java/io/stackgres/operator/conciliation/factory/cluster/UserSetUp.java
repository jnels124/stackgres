/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster;

import static io.stackgres.common.StackGresUtil.getDefaultPullPolicy;

import java.util.List;

import com.google.common.collect.ImmutableList;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.stackgres.common.ClusterPath;
import io.stackgres.common.KubectlUtil;
import io.stackgres.common.StackGresInitContainer;
import io.stackgres.common.StackGresVolume;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.factory.ContainerFactory;
import io.stackgres.operator.conciliation.factory.InitContainer;
import io.stackgres.operator.conciliation.factory.PostgresDataMounts;
import io.stackgres.operator.conciliation.factory.TemplatesMounts;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@OperatorVersionBinder
@InitContainer(StackGresInitContainer.SETUP_ARBITRARY_USER)
public class UserSetUp implements ContainerFactory<ClusterContainerContext> {

  private final TemplatesMounts scriptTemplateMounts;

  private final PostgresDataMounts postgresDataMounts;

  @Inject
  KubectlUtil kubectl;

  @Inject
  public UserSetUp(
      TemplatesMounts scriptTemplateMounts,
      PostgresDataMounts postgresDataMounts) {
    this.scriptTemplateMounts = scriptTemplateMounts;
    this.postgresDataMounts = postgresDataMounts;
  }

  @Override
  public Container getContainer(ClusterContainerContext context) {
    return new ContainerBuilder()
        .withName(StackGresInitContainer.SETUP_ARBITRARY_USER.getName())
        .withImage(kubectl.getImageName(context.getClusterContext().getCluster()))
        .withImagePullPolicy(getDefaultPullPolicy())
        .withCommand("/bin/sh", "-ex",
            ClusterPath.TEMPLATES_PATH.path()
                + "/" + ClusterPath.LOCAL_BIN_SETUP_ARBITRARY_USER_SH_PATH.filename())
        .withEnv(getClusterEnvVars(context))
        .addToEnv(new EnvVarBuilder().withName("HOME").withValue("/tmp").build())
        .withVolumeMounts(scriptTemplateMounts.getVolumeMounts(context))
        .addToVolumeMounts(
            new VolumeMountBuilder()
                .withName(StackGresVolume.USER.getName())
                .withMountPath("/local/etc")
                .withSubPath("etc")
                .build())
        .build();
  }

  private List<EnvVar> getClusterEnvVars(ClusterContainerContext context) {

    return ImmutableList.<EnvVar>builder()
        .addAll(scriptTemplateMounts.getDerivedEnvVars(context))
        .addAll(postgresDataMounts.getDerivedEnvVars(context))
        .build();

  }
}
