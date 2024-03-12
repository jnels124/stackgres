/*
 * Copyright (C) 2024 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgcluster;

import org.jetbrains.annotations.NotNull;

public enum StackGresClusterBackupEncryptionMethod {

  SODIUM("Sodium"),
  OPEN_PGP("OpenPGP");

  private final String method;

  StackGresClusterBackupEncryptionMethod(String method) {
    this.method = method;
  }

  @Override
  public @NotNull String toString() {
    return method;
  }

  public static @NotNull StackGresClusterBackupEncryptionMethod fromString(@NotNull String method) {
    return switch (method) {
      case "Sodium" -> SODIUM;
      case "OpenPGP" -> OPEN_PGP;
      default -> throw new IllegalArgumentException("Unknown method " + method);
    };
  }
}
