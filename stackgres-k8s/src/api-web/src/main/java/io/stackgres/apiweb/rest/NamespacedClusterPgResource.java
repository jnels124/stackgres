/*
 * Copyright (C) 2023 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.security.Authenticated;
import io.stackgres.apiweb.dto.pgstat.PostgresStatDto;
import io.stackgres.apiweb.rest.utils.CommonApiResponses;
import io.stackgres.apiweb.rest.utils.NamespacedClusterPgResourceQueryGenerator;
import io.stackgres.common.EnvoyUtil;
import io.stackgres.common.PatroniUtil;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.patroni.StackGresPasswordKeys;
import io.stackgres.common.postgres.PostgresConnectionManager;
import io.stackgres.common.resource.CustomResourceFinder;
import io.stackgres.common.resource.ResourceFinder;
import io.stackgres.operatorframework.resource.ResourceUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jooq.CloseableDSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultCloseableDSLContext;
import org.jooq.impl.DefaultConnectionProvider;

@Path("namespaces/{namespace:[a-z0-9]([-a-z0-9]*[a-z0-9])?}/sgcluster")
@RequestScoped
@Authenticated
public class NamespacedClusterPgResource {
  public static final String TOP_PG_STAT_STATEMENTS = "top_pg_stat_statements";
  public static final String TOP_PG_STAT_ACTIVITY = "top_pg_stat_activity";
  public static final String TOP_PG_LOCKS = "top_pg_locks";

  private final PostgresConnectionManager postgresConnectionManager;
  private final ResourceFinder<Secret> secretFinder;
  private final CustomResourceFinder<StackGresCluster> customResourceFinder;
  private final NamespacedClusterPgResourceQueryGenerator queryGenerator;

  @Inject
  public NamespacedClusterPgResource(
      final PostgresConnectionManager postgresConnectionManager,
      final ResourceFinder<Secret> secretFinder,
      final CustomResourceFinder<StackGresCluster> customResourceFinder,
      final NamespacedClusterPgResourceQueryGenerator queryGenerator) {
    this.postgresConnectionManager = postgresConnectionManager;
    this.secretFinder = secretFinder;
    this.customResourceFinder = customResourceFinder;
    this.queryGenerator = queryGenerator;
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
          content = { @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(type = "object"))) })
      })
  @CommonApiResponses
  @Path("{name}/query")
  @GET
  public List<Object> statements(
      @PathParam("namespace") String namespace,
      @PathParam("name") String name,
      @QueryParam("named") String named,
      @QueryParam("limit") Integer limit,
      @QueryParam("sort") String sort,
      @QueryParam("dir") String dir) {

    final String secretName = PatroniUtil.secretName(name);

    final Secret secret = secretFinder
            .findByNameAndNamespace(secretName, namespace)
            .orElseThrow(() -> new IllegalArgumentException(
                    String.format("Could not find secret with name: %s", secretName)));

    final StackGresCluster stackGresCluster = customResourceFinder
            .findByNameAndNamespace(name, namespace)
            .orElseThrow(() -> new IllegalArgumentException(
                    String.format("Could not find SGCluster with name %s and namespace %s",
                            name, namespace)));

    final String serviceName = PatroniUtil.readWriteName(stackGresCluster);
    final String host = String.format("%s.%s", serviceName, namespace);
    final int port = EnvoyUtil.PG_PORT;
    final String username = ResourceUtil
            .decodeSecret(secret.getData())
            .get(StackGresPasswordKeys.SUPERUSER_USERNAME_KEY);
    final String password = ResourceUtil
            .decodeSecret(secret.getData())
            .get(StackGresPasswordKeys.SUPERUSER_PASSWORD_KEY);

    try (
        final Connection connection = postgresConnectionManager.getConnection(
            host,
            port,
            StackGresPasswordKeys.SUPERUSER_DATABASE,
            username,
            password);

        final CloseableDSLContext context =
          new DefaultCloseableDSLContext(
              new DefaultConnectionProvider(connection), SQLDialect.POSTGRES
          );

        final PreparedStatement preparedStatement =
            connection.prepareStatement(
                queryGenerator.generateQuery(context, named, sort, dir, limit));

        final ResultSet resultSet = preparedStatement.executeQuery()
    ) {

      final List<Object> dtos = new ArrayList<>();
      final var metadata = resultSet.getMetaData();

      final var resultSetIndex = 0;

      final var columnsCount = metadata.getColumnCount();
      final var maybeColumnNames = new String[columnsCount];
      for (int i = 0; i < columnsCount; i++) {
        maybeColumnNames[i] = metadata.getColumnName(i + 1);
      }

      final var columnNames = List.of(maybeColumnNames);
      final PostgresStatDto dto = new PostgresStatDto(resultSetIndex, columnNames);

      dtos.add(dto);

      while (resultSet.next()) {
        final var data = new ArrayList<>();

        for (final String columnName : columnNames) {
          final var o = resultSet.getObject(columnName);

          if (o == null || o instanceof Boolean || o instanceof Number) {
            data.add(o);
          } else {
            data.add(o.toString());
          }
        }
        dtos.add(data);
      }

      return dtos;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
