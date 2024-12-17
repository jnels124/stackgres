---
title: Connection Pooling Configuration
weight: 5
url: /administration/configuration/pool
aliases: [ /tutorial/complete-cluster/pooling-config, /administration/cluster/pool/ ]
description: Details about how to update the pooling configuration.
showToc: true
---

By default, StackGres deploys Postgres clusters with a sidecar containing a connection pooler.
StackGres currently uses [PgBouncer](https://www.pgbouncer.org/) as the connection pooler.
The connection pooler fronts the database and controls the incoming connections (fan-in).
This keeps Postgres operating with a lower number of concurrent connections, while allowing a higher number
 of external connections (from the application to the pooler).
If no custom pooling configuration is specified at cluster creation, StackGres will create a default
 configuration, which you can see in the [default values table](#default-values).
 This default configuration will also be used for those parameters that are not specified during the
 creation or modification of the resource.

Some of the configuration's parameters are part of a blocklist and specifying them will is possible during
 the creation or modification of the resource but those values will be ignored. For the complete list of those
 parameters see the [blocked parameters table](#blocked-parameters).

You can provide your own pooling configuration, by creating an instance of the
 [SGPoolingConfig]({{% relref "06-crd-reference/04-sgpoolingconfig" %}}) CRD.
The `SGPoolingConfig` can be referenced from one or more Postgres clusters.

This is an example PgBouncer configuration definition:

```yaml
apiVersion: stackgres.io/v1
kind: SGPoolingConfig
metadata:
  name: poolconfig
spec:
  pgBouncer:
    pgbouncer.ini:
      pgbouncer:
        max_client_conn: '200'
        default_pool_size: '200'
        pool_mode: transaction
```

This definition is created in Kubernetes (e.g. using `kubectl apply`) and can be inspected
 (`kubectl describe sgpoolconfig poolconfig`) like any other Kubernetes resource.

An SGCluster can reference this configuration as follows:

```yaml
apiVersion: stackgres.io/v1
kind: SGCluster
metadata:
  name: cluster
spec:
# [...]
  configurations:
    sgPoolingConfig: 'poolconfig'
```

The [SGPoolingConfig Customizing Pooling Configuration Section]({{% relref "06-crd-reference/04-sgpoolingconfig/#pgbouncer" %}})
 explains the different options for scaling connections properly.

Check the following sections for more insights related to how to configure the connection pooler:

{{% children style="li" depth="1" description="true" %}}

<!--
TODO keep?
If you happen to be reading this, it's because you are aware of your application characteristics and needs
 for scaling connections on a production environment.

A simple way to target this correctly, is to verify the usage of Prepared Statements, on top of which
 `session` mode will be the only compatible.

Some applications, do not handle connection closing properly, which may require to add certain timeouts
 for releasing server connections.
-->

## Apply Configuration changes

Each configuration, once applied, is automatically _reloaded_.

## Disabling Pooling

Certain set of applications, particularly those for reporting or OLAP, may not need a pooling middleware in
 order to issue large queries and a low number of connections.
It is possible to disable pooling by setting `disableConnectionPooling` to `true` at the Cluster
 configuration (for more information, see
 [CRD Cluster Pods configuration]({{% relref "06-crd-reference/01-sgcluster/" %}})).

```yaml
apiVersion: stackgres.io/v1
kind: SGCluster
metadata:
  name: cluster
spec:
# [...]
  pods:
    disableConnectionPooling: false
```

Either way, if your application does internal pooling or it already has a pooling middleware, you can consider
 disabling internal pooling mechanisms.
Although, we encourage the user to keep pooling enabled internally, as it serves as a contention barrier for
 unexpected connection spikes that may occur, bringing more stability to the cluster.

## Blocked Parameters

The list of blocked PgBouncer parameters:

{{% pgbouncer-blocklist %}}

## Default Values

The default PgBouncer parameters (when not specified):

{{% pgbouncer-default-values %}}
