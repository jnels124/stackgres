/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.patroni;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ongres.process.FluentProcess;
import com.ongres.process.Output;
import io.fabric8.kubernetes.client.CustomResource;
import io.stackgres.common.PatroniUtil;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.YamlMapperProvider;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdistributedlogs.StackGresDistributedLogs;
import io.stackgres.common.labels.LabelFactoryForCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.lambda.Seq;

@ApplicationScoped
public class PatroniCtl {

  final ObjectMapper objectMapper;
  final YAMLMapper yamlMapper;
  final LabelFactoryForCluster<StackGresCluster> clusterLabelFactory;
  final LabelFactoryForCluster<StackGresDistributedLogs> distributedLogsLabelFactory;

  @Inject
  public PatroniCtl(ObjectMapper objectMapper, YamlMapperProvider yamlMapperProvider,
      LabelFactoryForCluster<StackGresCluster> clusterLabelFactory,
      LabelFactoryForCluster<StackGresDistributedLogs> distributedLogsLabelFactory) {
    this.objectMapper = objectMapper;
    this.yamlMapper = yamlMapperProvider.get();
    this.clusterLabelFactory = clusterLabelFactory;
    this.distributedLogsLabelFactory = distributedLogsLabelFactory;
  }

  public PatroniCtlInstance instanceFor(CustomResource<?, ?> customResource) {
    if (customResource instanceof StackGresCluster cluster) {
      var instance = new PatroniCtlInstance(cluster);
      instance.writeConfig();
      return instance;
    }
    if (customResource instanceof StackGresDistributedLogs distributedLogs) {
      var instance = new PatroniCtlInstance(distributedLogs);
      instance.writeConfig();
      return instance;
    }
    throw new IllegalArgumentException("Can not retrieve instance for "
        + customResource.getClass().getSimpleName());
  }

  public class PatroniCtlInstance {

    private static final TypeReference<List<PatroniMember>> LIST_TYPE_REFERENCE =
        new TypeReference<List<PatroniMember>>() { };

    private static final TypeReference<List<PatroniHistoryEntry>> HISTORY_TYPE_REFERENCE =
        new TypeReference<List<PatroniHistoryEntry>>() { };

    private static final String PYTHON_COMMAND = "python3";

    final CustomResource<?, ?> customResource;
    final String scope;
    final String patroniCtlCommand;
    final Path configPath;
    final String config;

    PatroniCtlInstance(StackGresCluster cluster) {
      this.customResource = cluster;
      this.scope = PatroniUtil.clusterScope(cluster);
      this.patroniCtlCommand = "/bin/patronictl-" + StackGresUtil.getPatroniVersion(cluster);
      this.configPath = getConfigPath();
      this.config = PatroniUtil.getInitialConfig(
          cluster, clusterLabelFactory, yamlMapper, objectMapper);
    }

    PatroniCtlInstance(StackGresDistributedLogs distributedLogs) {
      this.customResource = distributedLogs;
      this.scope = PatroniUtil.clusterScope(distributedLogs);
      this.patroniCtlCommand = "/bin/patronictl-" + StackGresUtil.getPatroniVersion(distributedLogs);
      this.configPath = getConfigPath();
      this.config = PatroniUtil.getInitialConfig(
          distributedLogs, distributedLogsLabelFactory, yamlMapper, objectMapper);
    }

    final Path getConfigPath() {
      return Paths.get("/tmp/patroni/"
          + customResource.getMetadata().getNamespace()
          + "/" + customResource.getMetadata().getName()
          + "/config.yaml");
    }

    final void writeConfig() {
      try {
        Files.createDirectories(configPath.getParent());
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
          fos.write(config.getBytes(StandardCharsets.UTF_8));
        }
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public List<PatroniMember> list() {
      Output output = patronictl("list", "-f", "json", "-e").withoutCloseAfterLast().tryGet();
      String result = getOutputOrFail(output);
      try {
        return objectMapper.readValue(result, LIST_TYPE_REFERENCE);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public List<PatroniHistoryEntry> history() {
      Output output = patronictl("history", "-f", "json").withoutCloseAfterLast().tryGet();
      String result = getOutputOrFail(output);
      try {
        return objectMapper.readValue(result, HISTORY_TYPE_REFERENCE);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public void remove() {
      Output output = patronictl("remove", scope)
          .inputStream(
              Seq.of(
                  scope,
                  "Yes I am aware")
              .append(list()
                  .stream()
                  .filter(member -> member.isPrimary())
                  .map(PatroniMember::getMember)))
          .withoutCloseAfterLast()
          .tryGet();
      getOutputOrFail(output);
    }

    public PatroniConfig showConfig() {
      Output output = patronictl("show-config").withoutCloseAfterLast().tryGet();
      String result = getOutputOrFail(output);
      try {
        return yamlMapper.readValue(result, PatroniConfig.class);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public ObjectNode showConfigJson() {
      Output output = patronictl("show-config").withoutCloseAfterLast().tryGet();
      String result = getOutputOrFail(output);
      try {
        return (ObjectNode) yamlMapper.readTree(result);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public void editConfig(PatroniConfig patroniConfig) {
      try (InputStream in = new ByteArrayInputStream(yamlMapper.writeValueAsBytes(patroniConfig))) {
        Output output = patronictl("edit-config", scope, "--apply", "-")
            .inputStream(in)
            .withoutCloseAfterLast()
            .tryGet();
        getOutputOrFail(output);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public void editConfigJson(ObjectNode patroniConfig) {
      try (InputStream in = new ByteArrayInputStream(patroniConfig.toString().getBytes(StandardCharsets.UTF_8))) {
        Output output = patronictl("edit-config", scope, "--apply", "-")
            .inputStream(in)
            .withoutCloseAfterLast()
            .tryGet();
        getOutputOrFail(output);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public void switchover(String leader, String candidate) {
      Output output = patronictl("switchover", scope, "--leader", leader, "--candidate", candidate, "--force")
          .withoutCloseAfterLast()
          .tryGet();
      getOutputOrFail(output);
    }

    public void restart(String member) {
      Output output = patronictl("restart", scope, member, "--force")
          .withoutCloseAfterLast()
          .tryGet();
      getOutputOrFail(output);
    }

    public JsonNode queryPrimary(String query) {
      Output output = patronictl("query", "-c", query, "--primary", "--format", "json")
          .withoutCloseAfterLast()
          .tryGet();
      String result = getOutputOrFail(output);
      try {
        return (ObjectNode) objectMapper.readTree(result);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    private FluentProcess patronictl(String command, String... args) {
      return FluentProcess
          .start(PYTHON_COMMAND, Seq.of(patroniCtlCommand)
              .append("-c", configPath.toString())
              .append(command)
              .append(args)
              .toArray(String[]::new));
    }

    private String getOutputOrFail(Output output) {
      if (output.exception().isPresent()) {
        throw new RuntimeException(output.exception().get().getMessage()
            + output.error().map(stderr -> "\n\nstderr: " + stderr).orElse("")
            + output.output().map(stdout -> "\n\nstdout:" + stdout).orElse(""),
            output.exception().get());
      }
      String result = output.output()
          .orElseThrow(() -> new IllegalStateException("Empty output"));
      return result;
    }
  }
}
