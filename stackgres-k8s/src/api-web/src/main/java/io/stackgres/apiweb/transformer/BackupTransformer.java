/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.transformer;

import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.stackgres.apiweb.dto.backup.BackupDto;
import io.stackgres.apiweb.dto.backup.BackupInformation;
import io.stackgres.apiweb.dto.backup.BackupProcess;
import io.stackgres.apiweb.dto.backup.BackupSpec;
import io.stackgres.apiweb.dto.backup.BackupStatus;
import io.stackgres.common.crd.sgbackup.StackGresBackup;
import io.stackgres.common.crd.sgbackup.StackGresBackupSpec;
import io.stackgres.common.crd.sgbackup.StackGresBackupStatus;

@ApplicationScoped
public class BackupTransformer extends AbstractResourceTransformer<BackupDto, StackGresBackup> {

  private final BackupConfigTransformer backupConfigTransformer;
  private final ObjectMapper mapper;

  @Inject
  public BackupTransformer(BackupConfigTransformer backupConfigTransformer, ObjectMapper mapper) {
    this.backupConfigTransformer = backupConfigTransformer;
    this.mapper = mapper;
  }

  @Override
  public StackGresBackup toCustomResource(BackupDto source, StackGresBackup original) {
    StackGresBackup transformation = Optional.ofNullable(original)
        .orElseGet(StackGresBackup::new);
    transformation.setMetadata(getCustomResourceMetadata(source, original));
    transformation.setSpec(getCustomResourceSpec(source.getSpec()));
    return transformation;
  }

  @Override
  public BackupDto toDto(StackGresBackup source) {
    BackupDto transformation = new BackupDto();
    transformation.setMetadata(getDtoMetadata(source));
    transformation.setSpec(getResourceSpec(source.getSpec()));
    transformation.setStatus(getResourceStatus(source.getStatus()));
    return transformation;
  }

  private StackGresBackupSpec getCustomResourceSpec(BackupSpec source) {
    if (source == null) {
      return null;
    }

    return mapper.convertValue(source, StackGresBackupSpec.class);
  }

  private BackupSpec getResourceSpec(StackGresBackupSpec source) {
    if (source == null) {
      return null;
    }
    return mapper.convertValue(source, BackupSpec.class);
  }

  private BackupStatus getResourceStatus(StackGresBackupStatus source) {
    if (source == null) {
      return null;
    }
    BackupStatus transformation = new BackupStatus();
    transformation.setTested(source.getTested());
    transformation.setInternalName(source.getInternalName());
    transformation.setBackupConfig(
        backupConfigTransformer.getResourceSpec(source.getBackupConfig()));

    transformation.setBackupInformation(
        mapper.convertValue(source.getBackupInformation(), BackupInformation.class)
    );

    transformation.setProcess(
        mapper.convertValue(source.getProcess(), BackupProcess.class)
    );
    return transformation;
  }

}
