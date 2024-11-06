/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.quarkus.runtime.ShutdownEvent;
import io.stackgres.common.OperatorProperty;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.lambda.Seq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ReconciliatorWorkerThreadPool {

  protected static final Logger LOGGER = LoggerFactory.getLogger(
      ReconciliatorWorkerThreadPool.class.getName());

  private final PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();

  private final ReconciliatorThreadPoolExecutor executor;

  private final AtomicInteger threadIndex = new AtomicInteger(0);

  @Inject
  public ReconciliatorWorkerThreadPool() {
    final Integer threads = OperatorProperty.RECONCILIATION_THREADS
        .get()
        .map(Integer::parseInt)
        .orElseGet(() -> (Runtime.getRuntime().availableProcessors() + 1) / 2);
    this.executor = new ReconciliatorThreadPoolExecutor(
        threads,
        queue,
        r -> new Thread(r, "ReconciliationWorker-" + threadIndex.getAndIncrement()));
  }

  void onStop(@Observes ShutdownEvent ev) {
    executor.shutdown();
  }

  public synchronized void scheduleReconciliation(Runnable runnable, String configId, boolean priority) {
    var prioritizedRunnable = new ReconciliationRunnable(
        executor, runnable, configId, priority);
    if (LOGGER.isTraceEnabled()) {
      synchronized (executor.executingReconciliations) {
        final long currentTimestamp = System.currentTimeMillis();
        LOGGER.trace("{} will be scheduled, current state of the pool:\n\nqueue:\n\n{}\n\nexecuting:\n\n{}\n",
            configId,
            Seq.of(queue.toArray())
            .map(ReconciliationRunnable.class::cast)
            .groupBy(r -> r.priority)
            .entrySet()
            .stream()
            .flatMap(group -> group.getValue().size() <= 10
                ? Seq.<Object>seq(group.getValue())
                    : Seq.<Object>seq(group.getValue()).limit(10)
                    .append("...and other " + (group.getKey() ? "high" : "low")
                        + " priority found: " + group.getValue().size()
                        + " (max " + group.getValue().stream()
                        .mapToLong(r -> r.timestamp)
                        .map(t -> System.currentTimeMillis() - t)
                        .max()
                        .orElse(0) + "ms)"))
            .map(Object::toString)
            .collect(Collectors.joining("\n")),
            executor.executingReconciliations.entrySet().stream()
            .map(entry -> entry.getKey().toString() + " " + (currentTimestamp - entry.getValue()) + "ms")
            .collect(Collectors.joining("\n")));
      }
    }
    var inversePrioritizedRunnable = new ReconciliationRunnable(
        executor, runnable, configId, !priority);
    if (priority) {
      final boolean removed = queue.remove(new ReconciliationRunnable(
          executor, runnable, configId, false));
      if (removed) {
        LOGGER.trace("{} with low priority has been removed from the reconciliation queue", configId);
      }
    } else if (queue.contains(new ReconciliationRunnable(
        executor, runnable, configId, true))) {
      LOGGER.trace("{} with high priority is already present in the reconciliation queue", configId);
      return;
    }
    if (executor.isExecuting(prioritizedRunnable)
        || executor.isExecuting(inversePrioritizedRunnable)) {
      LOGGER.trace("{} is already executing, will be scheduled to be reconcilied when current one finishes", configId);
      executor.executeWhenCompleted(prioritizedRunnable);
    } else {
      LOGGER.trace("{} has been scheduled to be reconcilied", configId);
      executor.execute(prioritizedRunnable);
    }
  }

  static class ReconciliationRunnable implements Runnable, Comparable<ReconciliationRunnable> {

    final ReconciliatorThreadPoolExecutor executor;
    final Runnable runnable;
    final long timestamp;
    final String configId;
    final Boolean priority;
    final ClassLoader contextClassLoader;

    public ReconciliationRunnable(
        ReconciliatorThreadPoolExecutor executor,
        Runnable runnable,
        String configId,
        boolean priority) {
      this.executor = executor;
      this.runnable = runnable;
      this.timestamp = System.currentTimeMillis();
      this.configId = configId;
      this.priority = priority;
      this.contextClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void run() {
      executor.executeReconciliation(this);
    }

    @Override
    public int compareTo(ReconciliationRunnable o) {
      return o.priority.compareTo(priority);
    }

    @Override
    public int hashCode() {
      return Objects.hash(configId, priority);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ReconciliationRunnable)) {
        return false;
      }
      ReconciliationRunnable other = (ReconciliationRunnable) obj;
      return Objects.equals(configId, other.configId)
          && Objects.equals(priority, other.priority);
    }

    @Override
    public String toString() {
      return (priority ? "* " : "  ") + configId + " " + (System.currentTimeMillis() - timestamp) + "ms";
    }

  }

  static class ReconciliatorThreadPoolExecutor {

    final ThreadPoolExecutor threadPoolExecutor;
    final Map<ReconciliationRunnable, Long> executingReconciliations = Collections.synchronizedMap(new HashMap<>());
    final Set<ReconciliationRunnable> toExecuteReconciliations = Collections.synchronizedSet(new HashSet<>());

    public ReconciliatorThreadPoolExecutor(
        int threads,
        BlockingQueue<Runnable> workQueue,
        ThreadFactory threadFactory) {
      this.threadPoolExecutor = new ThreadPoolExecutor(
          threads,
          threads,
          0L,
          TimeUnit.MILLISECONDS,
          workQueue,
          threadFactory);
    }

    void executeReconciliation(ReconciliationRunnable r) {
      executingReconciliations.put(r, System.currentTimeMillis());
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("{} started executing",
            r);
      }
      final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(r.contextClassLoader);
        r.runnable.run();
      } finally {
        Thread.currentThread().setContextClassLoader(contextClassLoader);
        if (LOGGER.isTraceEnabled()) {
          LOGGER.trace("{} finished executing after {}ms",
              r,
              Optional.ofNullable(executingReconciliations.get(r))
              .map(start -> System.currentTimeMillis() - start)
              .map(Object::toString)
              .orElse("?"));
        }
        executingReconciliations.remove(r);
        if (!isShutdown()
            && !isTerminated()) {
          if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("{} has been scheduled to be reconcilied",
                r);
          }
          toExecuteReconciliations.stream().filter(r::equals).forEach(this::execute);
          toExecuteReconciliations.remove(r);
        }
      }
    }

    boolean isExecuting(ReconciliationRunnable r) {
      return executingReconciliations.containsKey(r);
    }

    void executeWhenCompleted(ReconciliationRunnable r) {
      toExecuteReconciliations.add(r);
    }

    public void execute(ReconciliationRunnable r) {
      threadPoolExecutor.execute(r);
    }

    public void shutdown() {
      this.threadPoolExecutor.shutdown();
    }

    public boolean isShutdown() {
      return this.threadPoolExecutor.isShutdown();
    }

    public boolean isTerminated() {
      return this.threadPoolExecutor.isTerminated();
    }

  }

}
