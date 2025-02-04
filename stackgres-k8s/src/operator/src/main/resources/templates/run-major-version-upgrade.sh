#!/bin/sh

run_op() {
  set -e

  PHASE="pre-checks"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  if [ -z "$TARGET_BACKUP_PATH" ]
  then
    echo "Checking setting postgres version to $TARGET_VERSION, postgres config to $TARGET_POSTGRES_CONFIG, replication mode to async and instances to 1..."
  else
    echo "Checking setting postgres version to $TARGET_VERSION, postgres config to $TARGET_POSTGRES_CONFIG, replication mode to async, instances to 1 and backup path to $TARGET_BACKUP_PATH..."
  fi
  echo
  until {
    CLUSTER="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.version = "'"$TARGET_VERSION"'"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.extensions = '"$TARGET_EXTENSIONS")"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.configurations.sgPostgresConfig = "'"$TARGET_POSTGRES_CONFIG"'"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.replication.mode = "async"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.instances = 1')"
    if [ -n "$TARGET_BACKUP_PATH" ]
    then
      CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '
          if .spec.configurations.sgBackupConfig != null
          then .spec.configurations.backupPath = "'"$TARGET_BACKUP_PATH"'"
          else
            if .spec.configurations.backups != null and (.spec.configurations.backups | length) > 0
            then .spec.configurations.backups[0].path = "'"$TARGET_BACKUP_PATH"'"
            else .
            end
          end')"
    fi
    PATCH_OUTPUT="$(kubectl patch --dry-run "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" --type merge -p "$CLUSTER" 2>&1)"
    }
  do
    echo "FAILURE=$NORMALIZED_OP_NAME failed. $PATCH_OUTPUT" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
    exit 1
  done
  echo "done"
  echo

  if [ "$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
    --template='{{ if .status.dbOps }}{{ if .status.dbOps.majorVersionUpgrade }}true{{ end }}{{ end }}')" != "true" ]
  then
    INITIAL_PODS="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_POD_LABELS" -o name)"
    INITIAL_INSTANCES="$(printf '%s' "$INITIAL_PODS" | cut -d / -f 2 | sort)"
    PRIMARY_POD="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_PRIMARY_POD_LABELS" -o name)"
    PRIMARY_INSTANCE="$(printf '%s' "$PRIMARY_POD" | cut -d / -f 2)"
    if [ "x$PRIMARY_INSTANCE" = "x" ] \
      || ! kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o name > /dev/null
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Primary instance $PRIMARY_INSTANCE not found!" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      return 1
    fi
    echo "Found primary instance $PRIMARY_INSTANCE"
    echo
    SOURCE_VERSION="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .spec.postgres.version }}')"
    SOURCE_EXTENSIONS="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json \
      | jq '.spec.postgres.extensions')"
    SOURCE_POSTGRES_CONFIG="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .spec.configurations.sgPostgresConfig }}')"
    SOURCE_BACKUP_PATH="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ if .spec.configurations.backups }}{{ (index .spec.configurations.backups 0).path }}{{ else }}{{ if .spec.configurations.backupPath }}{{ .spec.configurations.backupPath }}{{ end }}{{ end }}')"
    SOURCE_REPLICATION_MODE="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .spec.replication.mode }}')"
    LOCALE="$(kubectl exec -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$PATRONI_CONTAINER_NAME" \
      -- psql -q -t -A -c "SELECT setting FROM pg_settings WHERE name = 'lc_collate' UNION ALL SELECT datcollate FROM pg_database WHERE datname = 'template1' LIMIT 1")"
    ENCODING="$(kubectl exec -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$PATRONI_CONTAINER_NAME" \
      -- psql -q -t -A -c "SHOW server_encoding")"
    DATA_CHECKSUM="$(kubectl exec -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$PATRONI_CONTAINER_NAME" \
      -- psql -q -t -A -c "SELECT CASE WHEN current_setting('data_checksums')::bool THEN 'true' ELSE 'false' END")"

    if [ -z "${TARGET_VERSION}" ] || [ "${SOURCE_VERSION%%.*}" -ge "${TARGET_VERSION%%.*}" ]
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Can not perform major version upgrade from version $SOURCE_VERSION to version $TARGET_VERSION" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      exit 1
    fi

    echo "Signaling major version upgrade started to cluster"
    echo
    DBOPS_PATCH="$(cat << EOF
      {
        "majorVersionUpgrade":{
          "initialInstances": [$(
            FIRST=true
            for INSTANCE in $INITIAL_INSTANCES
            do
              if "$FIRST"
              then
                printf '%s' "\"$INSTANCE\""
                FIRST=false
              else
                printf '%s' ",\"$INSTANCE\""
              fi
            done
            )],
          "primaryInstance": "$PRIMARY_INSTANCE",
          "phase": "$PHASE",
          "sourcePostgresVersion": "$SOURCE_VERSION",
          "sourcePostgresExtensions": $SOURCE_EXTENSIONS,
          "sourceSgPostgresConfig": "$SOURCE_POSTGRES_CONFIG",
          "sourceReplicationMode": "$SOURCE_REPLICATION_MODE",
          $(
          if [ -n "$SOURCE_BACKUP_PATH" ]
          then
            printf %s '"sourceBackupPath": "$SOURCE_BACKUP_PATH",'
          fi
          )
          "targetPostgresVersion": "$TARGET_VERSION",
          "locale": "$LOCALE",
          "encoding": "$ENCODING",
          "dataChecksum": $DATA_CHECKSUM,
          "link": $LINK,
          "clone": $CLONE,
          "check": $CHECK
        }
      }
EOF
    )"

    until {
      DBOPS="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
      DBOPS="$(printf '%s' "$DBOPS" | jq -c '.status.dbOps = '"$DBOPS_PATCH")"
      printf '%s' "$DBOPS" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
      }
    do
      sleep 1
    done
  else
    PREVIOUS_TARGET_VERSION="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .status.dbOps.majorVersionUpgrade.targetPostgresVersion }}')"
    SOURCE_VERSION="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .status.dbOps.majorVersionUpgrade.sourcePostgresVersion }}')"
    SOURCE_EXTENSIONS="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      -o json | jq '.status.dbOps.majorVersionUpgrade.sourcePostgresExtensions')"
    if [ "${PREVIOUS_TARGET_VERSION%.*}" != "${TARGET_VERSION%.*}" ]
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Can not perform major version upgrade from version $SOURCE_VERSION to version $TARGET_VERSION since a major version upgrade to $PREVIOUS_TARGET_VERSION did not complete" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      exit 1
    fi
    SOURCE_POSTGRES_CONFIG="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .status.dbOps.majorVersionUpgrade.sourceSgPostgresConfig }}')"
    SOURCE_BACKUP_PATH="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ if .status.dbOps.majorVersionUpgrade.sourceBackupPath }}{{ .status.dbOps.majorVersionUpgrade.sourceBackupPath }}{{ end }}')"
    SOURCE_REPLICATION_MODE="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ .status.dbOps.majorVersionUpgrade.sourceReplicationMode }}')"
    INITIAL_INSTANCES="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ with .status.dbOps.majorVersionUpgrade.initialInstances }}{{ . }}{{ end }}')"
    INITIAL_INSTANCES="$(printf '%s' "$INITIAL_INSTANCES" | tr -d '[]' | tr ' ' '\n')"
    if [ "x$INITIAL_INSTANCES" = "x" ]
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Initial instances was not set!" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      return 1
    fi
    PRIMARY_INSTANCE="$(kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
      --template='{{ with .status.dbOps.majorVersionUpgrade.primaryInstance }}{{ . }}{{ end }}')"
    if [ "x$PRIMARY_INSTANCE" = "x" ]
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Primary instance was not set!" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      return 1
    fi

    PHASE="pre-upgrade"
    echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

    until kubectl patch "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" --type=json \
        -p "$(cat << EOF
[
  {"op":"replace","path":"/status/dbOps/majorVersionUpgrade/targetPostgresVersion","value": "$TARGET_VERSION"},
  {"op":"replace","path":"/status/dbOps/majorVersionUpgrade/link","value": $LINK},
  {"op":"replace","path":"/status/dbOps/majorVersionUpgrade/clone","value": $CLONE},
  {"op":"replace","path":"/status/dbOps/majorVersionUpgrade/check","value": $CHECK}
]
EOF
        )"
    do
      sleep 1
    done
  fi

  PHASE="pre-upgrade"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  INITIAL_INSTANCES_COUNT="$(printf '%s' "$INITIAL_INSTANCES" | tr ' ' 's' | tr '\n' ' ' | wc -w)"
  echo "Initial instances:"
  echo "$INITIAL_INSTANCES" | sed 's/^/ - /'
  echo

  update_status init

  RETRY=3
  while [ "$RETRY" != 0 ]
  do
    CURRENT_PRIMARY_POD="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_PRIMARY_POD_LABELS" -o name)"
    if [ -n "$CURRENT_PRIMARY_POD" ]
    then
      break
    fi
    RETRY="$((RETRY-1))"
    sleep 5
  done
  CURRENT_PRIMARY_INSTANCE="$(printf '%s' "$CURRENT_PRIMARY_POD" | cut -d / -f 2)"
  if [ "x$CURRENT_PRIMARY_INSTANCE" != "x" ] \
    && [ "$PRIMARY_INSTANCE" != "$CURRENT_PRIMARY_INSTANCE" ]
  then
    echo "FAILURE=$NORMALIZED_OP_NAME failed. Please check pod $PRIMARY_INSTANCE logs for more info" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
    exit 1
  fi

  PHASE="downscale"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  if [ "$SOURCE_REPLICATION_MODE" != async ]
  then
    echo "Setting replication mode to async..."
    echo
    until {
      CLUSTER="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
      CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.replication.mode = "async"')"
      REPLACE_OUTPUT="$(printf '%s' "$CLUSTER" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f - 2>&1)"
      }
    do
      if ! printf %s "$REPLACE_OUTPUT" | grep -q 'the object has been modified; please apply your changes to the latest version and try again'
      then
        echo "FAILURE=$NORMALIZED_OP_NAME failed. $REPLACE_OUTPUT" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
        exit 1
      fi 
      sleep 1
    done
    echo "done"
    echo
  fi

  downscale_cluster_instances

  PHASE="checkpoint"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  echo "Running a double CHECKPOINT on the primary instance $PRIMARY_INSTANCE before major version upgrade..."
  if ! kubectl exec -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$PATRONI_CONTAINER_NAME" \
      -- psql -q -t -A -c "CHECKPOINT" -c "CHECKPOINT"
  then
    echo "FAILURE=$NORMALIZED_OP_NAME failed. Please check pod $PRIMARY_INSTANCE logs for more info" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
    return 1
  fi

  PHASE="upgrade"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  if [ -z "$TARGET_BACKUP_PATH" ]
  then
    echo "Setting postgres version to $TARGET_VERSION and postgres config to $TARGET_POSTGRES_CONFIG..."
  else
    echo "Setting postgres version to $TARGET_VERSION, postgres config to $TARGET_POSTGRES_CONFIG and backup path to $TARGET_BACKUP_PATH..."
  fi
  echo
  until {
    CLUSTER="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.version = "'"$TARGET_VERSION"'"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.extensions = '"$TARGET_EXTENSIONS")"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.configurations.sgPostgresConfig = "'"$TARGET_POSTGRES_CONFIG"'"')"
    if [ -n "$TARGET_BACKUP_PATH" ]
    then
      CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '
          if .spec.configurations.sgBackupConfig != null
          then .spec.configurations.backupPath = "'"$TARGET_BACKUP_PATH"'"
          else
            if .spec.configurations.backups != null and (.spec.configurations.backups | length) > 0
            then .spec.configurations.backups[0].path = "'"$TARGET_BACKUP_PATH"'"
            else .
            end
          end')"
    fi
    REPLACE_OUTPUT="$(printf '%s' "$CLUSTER" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f - 2>&1)"
    }
  do
    if ! printf %s "$REPLACE_OUTPUT" | grep -q 'the object has been modified; please apply your changes to the latest version and try again'
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. $REPLACE_OUTPUT" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      exit 1
    fi 
    sleep 1
  done
  echo "done"
  echo

  echo "Waiting StatefulSet to be updated..."
  echo
  while true
  do
    IS_STATEFULSET_UPDATED="$(kubectl get sts -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json \
      | jq "(.spec.template.spec.initContainers | any(.name == \"$MAJOR_VERSION_UPGRADE_CONTAINER_NAME\"))
        and (.spec.template.metadata.annotations[\"$POSTGRES_VERSION_KEY\"] == \"$TARGET_VERSION\")" || printf false)"
    if [ "$IS_STATEFULSET_UPDATED" = "true" ]
    then
      break
    fi
    sleep 1
  done
  echo "done"
  echo

  if [ "$CHECK" != true ]
  then
    echo "Restarting primary instance $PRIMARY_INSTANCE to perform major version upgrade..."
    create_event "MajorVersionUpgradeStarted" "Normal" "Major version upgrade started on instance $PRIMARY_INSTANCE"

    kubectl delete pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE"

    echo "done"
    echo

    echo "Waiting primary instance $PRIMARY_INSTANCE major version upgrade..."

    wait_for_major_version_upgrade "$PRIMARY_INSTANCE"
    if [ "$?" != 0 ] # TODO: check if this is needed only due to a shell test framework bug
    then
      return 1
    fi
    create_event "MajorVersionUpgradeCompleted" "Normal" "Major version upgrade completed on instance $PRIMARY_INSTANCE"

    PHASE="post-upgrade"
    echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

    echo "Major version upgrade completed successfully, removing old data from instance $PRIMARY_INSTANCE"
    kubectl exec -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$PATRONI_CONTAINER_NAME" -- sh -c "$(
      cat << EOF
rm -rf "$PG_UPGRADE_PATH/$SOURCE_VERSION/data"
if [ -d "$PG_RELOCATED_BASE_PATH/$SOURCE_VERSION" ]
then
  chmod -R a+rw "$PG_RELOCATED_BASE_PATH/$SOURCE_VERSION"
  rm -rf "$PG_RELOCATED_BASE_PATH/$SOURCE_VERSION"
fi
if [ -d "$PG_EXTENSIONS_BASE_PATH/${SOURCE_VERSION%.*}" ]
then
  chmod -R a+rw "$PG_EXTENSIONS_BASE_PATH/${SOURCE_VERSION%.*}"
  rm -rf "$PG_EXTENSIONS_BASE_PATH/${SOURCE_VERSION%.*}"
fi
EOF
      )"
  else
    echo "Restarting primary instance $PRIMARY_INSTANCE to perform major version upgrade check..."
    create_event "MajorVersionUpgradeCheckStarted" "Normal" "Major version upgrade check started on instance $PRIMARY_INSTANCE"

    kubectl delete pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE"

    echo "done"
    echo

    echo "Waiting primary instance $PRIMARY_INSTANCE major version upgrade check..."

    wait_for_major_version_upgrade_check "$PRIMARY_INSTANCE"
    if [ "$?" != 0 ] # TODO: check if this is needed only due to a shell test framework bug
    then
      return 1
    fi
    create_event "MajorVersionUpgradeCheckCompleted" "Normal" "Major version upgrade check completed on instance $PRIMARY_INSTANCE"
  fi

  CURRENT_PRIMARY_POD="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_PRIMARY_POD_LABELS" -o name)"
  CURRENT_PRIMARY_INSTANCE="$(printf '%s' "$CURRENT_PRIMARY_POD" | cut -d / -f 2)"
  if [ "$PRIMARY_INSTANCE" != "$CURRENT_PRIMARY_INSTANCE" ]
  then
    echo "FAILURE=$NORMALIZED_OP_NAME failed. Please check pod $PRIMARY_INSTANCE logs for more info" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
    return 1
  fi

  echo "done"
  echo

  PHASE="upscale"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  update_status

  upscale_cluster_instances

  PHASE="cleanup"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  update_status

  if [ "$SOURCE_REPLICATION_MODE" != async ]
  then
    echo "Cleaning up major version upgrade by setting replication mode to $SOURCE_REPLICATION_MODE..."
    echo
    until {
      CLUSTER="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
      CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.replication.mode = "'"$SOURCE_REPLICATION_MODE"'"')"
      printf '%s' "$CLUSTER" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
      }
    do
      sleep 1
    done
    echo "done"
    echo
  fi

  echo "Signaling major version upgrade finished to cluster"
  echo

  until kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json | \
      jq 'del(.status.dbOps)' | \
      kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
  do
    sleep 1
  done
}

update_status() {
  PHASE="$(grep '^PHASE=' "$SHARED_PATH/$KEBAB_OP_NAME.out" | tail -n 1 | cut -d = -f 2)"
  STATEFULSET_UPDATE_REVISION="$(kubectl get sts -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" \
    --template='{{ .status.updateRevision }}')"
  if [ "$1" = "init" ]
  then
    PENDING_TO_RESTART_INSTANCES="$INITIAL_INSTANCES"
  else
    PENDING_TO_RESTART_INSTANCES="$(echo "$INITIAL_INSTANCES" | tr ' ' '\n' | grep -vxF '' \
      | while read INSTANCE
        do
          PODS="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_POD_LABELS" -o name)"
          if ! printf '%s' "$PODS" | cut -d / -f 2 | grep -q "^$INSTANCE$"
          then
            echo "$INSTANCE"
            continue
          fi
          PATRONI_STATUS="$(kubectl get pod -n "$CLUSTER_NAMESPACE" "$INSTANCE" \
            --template='{{ .metadata.annotations.status }}')"
          POD_STATEFULSET_REVISION="$(kubectl get pod -n "$CLUSTER_NAMESPACE" "$INSTANCE" \
            --template='{{ index .metadata.labels "controller-revision-hash" }}')"
          if [ "$STATEFULSET_UPDATE_REVISION" != "$POD_STATEFULSET_REVISION" ] \
            || echo "$PATRONI_STATUS" | grep -q '"pending_restart":true'
          then
            echo "$INSTANCE"
          fi
        done)"
  fi
  PENDING_TO_RESTART_INSTANCES_COUNT="$(echo "$PENDING_TO_RESTART_INSTANCES" | tr ' ' 's' | tr '\n' ' ' | wc -w)"
  EXISTING_PODS="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_POD_LABELS" -o name)"
  RESTARTED_INSTANCES="$(echo "$EXISTING_PODS" | cut -d / -f 2 | grep -v "^\($(
      echo "$PENDING_TO_RESTART_INSTANCES" | tr '\n' ' ' | sed '{s/ $//;s/ /\\|/g}'
    )\)$" | sort)"
  RESTARTED_INSTANCES_COUNT="$(echo "$RESTARTED_INSTANCES" | tr ' ' 's' | tr '\n' ' ' | wc -w)"
  echo "Pending to $NORMALIZED_OP_NAME instances:"
  if [ "$PENDING_TO_RESTART_INSTANCES_COUNT" = 0 ]
  then
    echo '<none>'
  else
    echo "$PENDING_TO_RESTART_INSTANCES" | tr ' ' '\n' | sed 's/^/ - /'
  fi
  echo

  OPERATION="$(kubectl get "$DBOPS_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$DBOPS_NAME" \
    --template='{{ if .status.majorVersionUpgrade }}replace{{ else }}add{{ end }}')"
  kubectl patch "$DBOPS_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$DBOPS_NAME" --type=json \
    -p "$(cat << EOF
[
  {"op":"$OPERATION","path":"/status/majorVersionUpgrade","value":{
      "phase": "$PHASE",
      "sourcePostgresVersion": "$SOURCE_VERSION",
      "targetPostgresVersion": "$TARGET_VERSION",
      "primaryInstance": "$PRIMARY_INSTANCE",
      "initialInstances": [$(
        FIRST=true
        for INSTANCE in $INITIAL_INSTANCES
        do
          if "$FIRST"
          then
            printf '%s' "\"$INSTANCE\""
            FIRST=false
          else
            printf '%s' ",\"$INSTANCE\""
          fi
        done
        )],
      "pendingToRestartInstances": [$(
        FIRST=true
        for INSTANCE in $PENDING_TO_RESTART_INSTANCES
        do
          if "$FIRST"
          then
            printf '%s' "\"$INSTANCE\""
            FIRST=false
          else
            printf '%s' ",\"$INSTANCE\""
          fi
        done
        )],
      "restartedInstances": [$(
        FIRST=true
        for INSTANCE in $RESTARTED_INSTANCES
        do
          if "$FIRST"
          then
            printf '%s' "\"$INSTANCE\""
            FIRST=false
          else
            printf '%s' ",\"$INSTANCE\""
          fi
        done
        )]
    }
  }
]
EOF
    )"
}

wait_for_instance() {
  local INSTANCE="$1"
  until kubectl get pod -n "$CLUSTER_NAMESPACE" "$INSTANCE" -o name >/dev/null 2>&1
  do
    sleep 1
  done
  until kubectl wait pod -n "$CLUSTER_NAMESPACE" "$INSTANCE" --for condition=Ready --timeout 0 >/dev/null 2>&1
  do
    sleep 1
  done
}

wait_for_major_version_upgrade() {
  local PRIMARY_INSTANCE="$1"
  until kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o name >/dev/null 2>&1
  do
    sleep 1
  done
  until kubectl wait pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" --for condition=Ready --timeout 0 >/dev/null 2>&1
  do
    POD_INIT_CONTAINER_FAILURES="$(kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o json \
      | jq '.status.containerStatuses + .status.initContainerStatuses + [] | map(select(.restartCount > 0)) | length' \
      || printf 0)"
    if [ "$POD_INIT_CONTAINER_FAILURES" -gt 0 ]
    then
      echo "FAILURE=$NORMALIZED_OP_NAME failed. Please check pod $PRIMARY_INSTANCE logs for more info" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
      echo
      echo
      sleep 10
      kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o json \
        | jq '[ .status.containerStatuses, .status.initContainerStatuses ]' || true
      echo
      kubectl logs -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" --all-containers --prefix --timestamps --ignore-errors || true
      echo
      rollback_major_version_upgrade "$PRIMARY_INSTANCE"
      return 1
    fi
    sleep 1
  done
}

wait_for_major_version_upgrade_check() {
  local PRIMARY_INSTANCE="$1"
  until kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o name >/dev/null 2>&1
  do
    sleep 1
  done
  while true
  do
    MAJOR_VERSION_UPGRADE_LOGS="$(
      kubectl logs -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$MAJOR_VERSION_UPGRADE_CONTAINER_NAME" 2>/dev/null \
        | grep "^Major version upgrade check " || true)"
    POD_INIT_CONTAINER_FAILURES="$(kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o json \
      | jq '.status.containerStatuses + .status.initContainerStatuses + [] | map(select(.restartCount > 0)) | length' \
      || printf 0)"
    if [ "$POD_INIT_CONTAINER_FAILURES" -gt 0 ] \
      || echo "$MAJOR_VERSION_UPGRADE_LOGS" | grep -qxF "Major version upgrade check performed"
    then
      if [ "$POD_INIT_CONTAINER_FAILURES" -gt 0 ]
      then
        echo "Major version upgrade check failed"
        echo
        echo
        sleep 10
        kubectl get pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -o json \
          | jq '[ .status.containerStatuses, .status.initContainerStatuses ]' || true
        echo
        kubectl logs -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" --all-containers --prefix --timestamps --ignore-errors || true
        echo
      else
        if echo "$MAJOR_VERSION_UPGRADE_LOGS" | grep -qxF "Major version upgrade check failed"
        then
          echo "Major version upgrade check failed"
        else
          echo "Major version upgrade check completed successfully"
        fi
        echo
        kubectl logs -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE" -c "$MAJOR_VERSION_UPGRADE_CONTAINER_NAME" || true
        echo
      fi
      rollback_major_version_upgrade "$PRIMARY_INSTANCE"
      if [ "$POD_INIT_CONTAINER_FAILURES" -gt 0 ] \
        || echo "$MAJOR_VERSION_UPGRADE_LOGS" | grep -qxF "Major version upgrade check failed"
      then
        return 1
      fi
      break
    fi
    sleep 1
  done
}

rollback_major_version_upgrade() {
  local PRIMARY_INSTANCE="$1"

  PHASE="pre-rollback"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  if [ -z "$SOURCE_BACKUP_PATH" ]
  then
    echo "Rollback major version upgrade by setting postgres version to $SOURCE_VERSION, postgres config to $SOURCE_POSTGRES_CONFIG and replication mode to async..."
  else
    echo "Rollback major version upgrade by setting postgres version to $SOURCE_VERSION, postgres config to $SOURCE_POSTGRES_CONFIG, replication mode to async and backup path to $SOURCE_BACKUP_PATH..."
  fi
  echo
  until {
    CLUSTER="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.version = "'"$SOURCE_VERSION"'"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.postgres.extensions = '"$SOURCE_EXTENSIONS")"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.configurations.sgPostgresConfig = "'"$SOURCE_POSTGRES_CONFIG"'"')"
    CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '.spec.replication.mode = "async"')"
    if [ -n "$SOURCE_BACKUP_PATH" ]
    then
      CLUSTER="$(printf '%s' "$CLUSTER" | jq -c '
          if .spec.configurations.sgBackupConfig != null
          then .spec.configurations.backupPath = "'"$SOURCE_BACKUP_PATH"'"
          else
            if .spec.configurations.backups != null and (.spec.configurations.backups | length) > 0
            then .spec.configurations.backups[0].path = "'"$SOURCE_BACKUP_PATH"'"
            else .
            end
          end')"
    fi
    printf '%s' "$CLUSTER" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
    }
  do
    sleep 1
  done
  echo "done"
  echo

  echo "Signaling major version upgrade rollback started to cluster"
  echo
  until {
    DBOPS="$({ kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json || printf .; } | jq -c .)"
    DBOPS="$(printf '%s' "$DBOPS" | jq -c '.status.dbOps.majorVersionUpgrade.rollback = true')"
    printf '%s' "$DBOPS" | kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
    }
  do
    sleep 1
  done

  echo "Waiting StatefulSet to be updated..."
  echo
  while true
  do
    IS_STATEFULSET_UPDATED="$(kubectl get sts -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json \
      | jq "(.spec.template.spec.initContainers | any(.name == \"$MAJOR_VERSION_UPGRADE_CONTAINER_NAME\"))
        and (.spec.template.spec.initContainers[] | select(.name == \"$MAJOR_VERSION_UPGRADE_CONTAINER_NAME\")
          | .env | any(.name == \"ROLLBACK\" and .value == \"true\"))" || printf false)"
    if [ "$IS_STATEFULSET_UPDATED" = "true" ]
    then
      break
    fi
    sleep 1
  done
  echo "done"
  echo

  PHASE="rollback"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  echo "Restarting primary instance $PRIMARY_INSTANCE to perform major version upgrade rollback..."
  create_event "MajorVersionUpgradeRollbackStarted" "Normal" "Major version upgrade rollback started on instance $PRIMARY_INSTANCE"

  kubectl delete pod -n "$CLUSTER_NAMESPACE" "$PRIMARY_INSTANCE"

  echo "done"
  echo

  echo "Waiting primary instance $PRIMARY_INSTANCE major version upgrade rollback..."

  wait_for_instance "$PRIMARY_INSTANCE"
  create_event "MajorVersionUpgradeRollbackCompleted" "Normal" "Major version upgrade rollback completed on instance $PRIMARY_INSTANCE"

  CURRENT_PRIMARY_POD="$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_PRIMARY_POD_LABELS" -o name)"
  CURRENT_PRIMARY_INSTANCE="$(printf '%s' "$CURRENT_PRIMARY_POD" | cut -d / -f 2)"
  if [ "$PRIMARY_INSTANCE" != "$CURRENT_PRIMARY_INSTANCE" ]
  then
    echo "FAILURE=$NORMALIZED_OP_NAME failed: expected primary pod to be $PRIMARY_INSTANCE but was $CURRENT_PRIMARY_INSTANCE." >> "$SHARED_PATH/$KEBAB_OP_NAME.out"
    return 1
  fi

  echo "done"
  echo

  PHASE="cleanup"
  echo "PHASE=$PHASE" >> "$SHARED_PATH/$KEBAB_OP_NAME.out"

  echo "Signaling major version upgrade rollback finished to cluster"
  echo

  until kubectl get "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" -o json | \
      jq 'del(.status.dbOps)' | \
      kubectl replace --raw /apis/"$CRD_GROUP"/v1/namespaces/"$CLUSTER_NAMESPACE"/"$CLUSTER_CRD_NAME"/"$CLUSTER_NAME" -f -
  do
    sleep 1
  done
}

downscale_cluster_instances() {
  if [ "$INITIAL_INSTANCES_COUNT" -gt 1 ]
  then
    echo "Downscaling cluster to 1 instance"
    create_event "DecreasingInstances" "Normal" "Decreasing instances of $CLUSTER_CRD_NAME $CLUSTER_NAME"
    echo

    kubectl patch "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" --type=json \
      -p "$(cat << EOF
[
  {"op":"replace","path":"/spec/instances","value":1}
]
EOF
        )"

    echo "Waiting cluster downscale..."

    until [ "$(kubectl get pods -n "$CLUSTER_NAMESPACE" -l "$CLUSTER_POD_LABELS" -o name | cut -d / -f 2)" = "$PRIMARY_INSTANCE" ]
    do
      sleep 1
    done

    echo "done"
    create_event "InstancesDecreased" "Normal" "Decreased instances of $CLUSTER_CRD_NAME $CLUSTER_NAME"
    echo
  fi
}

upscale_cluster_instances() {
  if [ "$INITIAL_INSTANCES_COUNT" -gt 1 ]
  then
    echo "Upscaling cluster to $INITIAL_INSTANCES_COUNT instances"
    create_event "IncreasingInstances" "Normal" "Increasing instances of $CLUSTER_CRD_NAME $CLUSTER_NAME"
    echo

    kubectl patch "$CLUSTER_CRD_NAME.$CRD_GROUP" -n "$CLUSTER_NAMESPACE" "$CLUSTER_NAME" --type=json \
      -p "$(cat << EOF
[
  {"op":"replace","path":"/spec/instances","value":$INITIAL_INSTANCES_COUNT}
]
EOF
        )"

    echo "Waiting cluster upscale"
    echo

    for INSTANCE in $INITIAL_INSTANCES
    do
      if [ "$INSTANCE" = "$PRIMARY_INSTANCE" ]
      then
        continue
      fi

      echo "Waiting instance $INSTANCE to become ready..."

      wait_for_instance "$INSTANCE"

      echo "done"
      echo

      update_status
    done

    echo "Cluster upscale done"
    create_event "InstancesIncreased" "Normal" "Increased instances of $CLUSTER_CRD_NAME $CLUSTER_NAME"
    echo
  fi
}
