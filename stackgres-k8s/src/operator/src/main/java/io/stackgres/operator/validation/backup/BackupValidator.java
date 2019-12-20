/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.validation.backup;

import io.stackgres.operator.validation.BackupReview;
import io.stackgres.operatorframework.Validator;

public interface BackupValidator extends Validator<BackupReview> {
}
