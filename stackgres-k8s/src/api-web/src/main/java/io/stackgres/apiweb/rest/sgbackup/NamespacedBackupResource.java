/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest.sgbackup;

import io.quarkus.security.Authenticated;
import io.stackgres.apiweb.dto.backup.BackupDto;
import io.stackgres.apiweb.exception.ErrorResponse;
import io.stackgres.apiweb.rest.AbstractNamespacedRestService;
import io.stackgres.common.crd.sgbackup.StackGresBackup;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("namespaces/{namespace:[a-z0-9]([-a-z0-9]*[a-z0-9])?}/sgbackups")
@RequestScoped
@Authenticated
@Tag(name = "sgbackup")
@APIResponse(responseCode = "400", description = "Bad Request",
content = {@Content(
    mediaType = "application/json",
    schema = @Schema(implementation = ErrorResponse.class))})
@APIResponse(responseCode = "401", description = "Unauthorized",
content = {@Content(
    mediaType = "application/json",
    schema = @Schema(implementation = ErrorResponse.class))})
@APIResponse(responseCode = "403", description = "Forbidden",
content = {@Content(
    mediaType = "application/json",
    schema = @Schema(implementation = ErrorResponse.class))})
@APIResponse(responseCode = "500", description = "Internal Server Error",
content = {@Content(
    mediaType = "application/json",
    schema = @Schema(implementation = ErrorResponse.class))})
public class NamespacedBackupResource
    extends AbstractNamespacedRestService<BackupDto, StackGresBackup> {

  @APIResponse(responseCode = "200", description = "OK",
      content = {@Content(
          mediaType = "application/json",
          schema = @Schema(implementation = BackupDto.class))})
  @Operation(summary = "Get a sgbackup", description = """
      Get a sgbackup.

      ### RBAC permissions required

      * sgbackup get
      """)
  @Override
  public BackupDto get(String namespace, String name) {
    return super.get(namespace, name);
  }

}
