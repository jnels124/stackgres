#!/bin/sh

set -e

RESTAPI_IMAGE_NAME="${RESTAPI_IMAGE_NAME:-"stackgres/restapi:main"}"
BASE_IMAGE="registry.access.redhat.com/ubi8-minimal:8.4-208"
TARGET_RESTAPI_IMAGE_NAME="${TARGET_RESTAPI_IMAGE_NAME:-$RESTAPI_IMAGE_NAME}"

docker build -t "$TARGET_RESTAPI_IMAGE_NAME" --build-arg BASE_IMAGE="$BASE_IMAGE" -f api-web/src/main/docker/Dockerfile.native api-web
