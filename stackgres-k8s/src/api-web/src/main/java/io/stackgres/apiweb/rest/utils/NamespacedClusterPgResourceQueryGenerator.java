/*
 * Copyright (C) 2023 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest.utils;

import org.jooq.DSLContext;

public interface NamespacedClusterPgResourceQueryGenerator {
  String generateQuery(final DSLContext context,
                       final String table,
                       final String sort,
                       final String dir,
                       final Integer limit);
}
