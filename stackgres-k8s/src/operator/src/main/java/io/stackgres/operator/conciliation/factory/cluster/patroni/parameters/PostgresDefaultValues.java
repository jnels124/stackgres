/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.patroni.parameters;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import com.google.common.collect.Maps;
import io.stackgres.common.StackGresUtil;
import org.jetbrains.annotations.NotNull;

public class PostgresDefaultValues {

  private enum PostgresVersion {
    PG_DEFAULT_VALUES("/postgresql-default-values.properties"),
    PG_13_VALUES("/postgresql-default-values-pg13.properties");

    private final @NotNull Properties propFile;

    PostgresVersion(@NotNull String file) {
      this.propFile = StackGresUtil.loadProperties(file);
    }

  }

  public static @NotNull Properties getProperties(@NotNull String pgVersion) {
    Objects.requireNonNull(pgVersion, "pgVersion parameter is null");
    int majorVersion = Integer.parseInt(pgVersion.split("\\.")[0]);
    if (majorVersion >= 13) {
      return copy(PostgresVersion.PG_13_VALUES.propFile);
    }
    return copy(PostgresVersion.PG_DEFAULT_VALUES.propFile);
  }

  public static @NotNull Map<String, String> getDefaultValues(@NotNull String pgVersion) {
    return Maps.fromProperties(getProperties(pgVersion));
  }

  private static Properties copy(Properties p) {
    Properties copy = new Properties();
    copy.putAll(p);
    return copy;
  }

}
