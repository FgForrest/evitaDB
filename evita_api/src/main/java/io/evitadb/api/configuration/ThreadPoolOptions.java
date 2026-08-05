/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.configuration;

import io.evitadb.utils.Assert;
import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * Record contains settings for particular thread pool used inside evitaDB.
 *
 * The request and transaction pools are backed by a {@code ThreadPoolExecutor} configured with a
 * *threads-first* (Tomcat-style) queueing policy: the pool grows from {@link #minThreadCount}
 * up to {@link #maxThreadCount} *before* any task is parked in the backlog, and only once all
 * {@code maxThreadCount} threads are busy does the {@link #queueSize}-bounded backlog start filling.
 * This trades a little CPU oversubscription for lower latency — idle thread headroom is used
 * immediately instead of letting requests wait behind busy core threads. evitaDB's request workload
 * is a mix of CPU work and blocking (I/O, serialization, lock waits), so headroom above the CPU count
 * is required to keep cores busy while some threads are parked.
 *
 * The core-derived defaults reflect this: {@code minThreadCount = availableProcessors()} (a warm
 * baseline equal to the CPU count) and {@code maxThreadCount = availableProcessors() * 4} (enough
 * blocking headroom while staying below the ~5×CPU point where empirical throughput begins to halve
 * from context-switch thrash). Request and transaction pools share identical sizing and queueing
 * behaviour. Production deployments are expected to override these with explicit values; the defaults
 * are only a sane unconfigured baseline.
 *
 * @param minThreadCount Core thread count of the backing `ThreadPoolExecutor`
 *                       (`corePoolSize`). Threads are created on demand up to
 *                       this count and kept ready for handling input requests
 *                       and maintenance tasks; idle threads are reclaimed after
 *                       a keep-alive period. The more catalogs in evitaDB there
 *                       are, the higher count might be required.
 * @param maxThreadCount Maximum thread count of the backing `ThreadPoolExecutor`
 *                       (`maximumPoolSize`) — the effective concurrency ceiling.
 *                       The pool grows from `minThreadCount` up to this many
 *                       threads *before* it starts queueing, so the configured
 *                       maximum is actually reachable for blocking workloads
 *                       (decoupled from the available CPU count).
 * @param threadPriority Defines a `Thread.getPriority()` for background
 *                       threads. The number must be in interval 1-10.
 *                       The threads with higher priority should be
 *                       preferred over the ones with lesser priority.
 * @param queueSize      Maximum number of tasks allowed to wait in the backlog
 *                       once all `maxThreadCount` threads are busy. Beyond this
 *                       the task is rejected. The exception type depends on the pool:
 *                       server-side pools throw `RejectedExecutionException`, while the
 *                       gRPC client pool throws `EvitaClientPoolSaturatedException`, which
 *                       deliberately does **not** extend `RejectedExecutionException` so that
 *                       driver saturation cannot be mistaken for a consumer's own scheduler
 *                       shutting down.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record ThreadPoolOptions(
	int minThreadCount,
	int maxThreadCount,
	int threadPriority,
	int queueSize
) {
	public static final int DEFAULT_REQUEST_MIN_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
	public static final int DEFAULT_REQUEST_MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() << 2;
	public static final int DEFAULT_REQUEST_THREAD_PRIORITY = 8;
	public static final int DEFAULT_REQUEST_QUEUE_SIZE = 100;
	public static final int DEFAULT_TRANSACTION_MIN_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
	public static final int DEFAULT_TRANSACTION_MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() << 2;
	public static final int DEFAULT_TRANSACTION_THREAD_PRIORITY = 5;
	public static final int DEFAULT_TRANSACTION_QUEUE_SIZE = 100;
	public static final int DEFAULT_MIN_SERVICE_THREAD_COUNT = Math.max(Runtime.getRuntime().availableProcessors(), 1);
	public static final int DEFAULT_MAX_SERVICE_THREAD_COUNT = Math.max(Runtime.getRuntime().availableProcessors() << 1, 1);
	public static final int DEFAULT_SERVICE_THREAD_PRIORITY = 1;
	public static final int DEFAULT_SERVICE_QUEUE_SIZE = 20;
	public static final int DEFAULT_CLIENT_MIN_THREAD_COUNT = 0;
	public static final int DEFAULT_CLIENT_MAX_THREAD_COUNT = Math.max(Runtime.getRuntime().availableProcessors() << 2, 4);
	public static final int DEFAULT_CLIENT_THREAD_PRIORITY = Thread.NORM_PRIORITY;
	public static final int DEFAULT_CLIENT_QUEUE_SIZE = 100;

	/**
	 * Builder for the thread pool options with recommended defaults for request tasks.
	 */
	public static ThreadPoolOptions.Builder requestThreadPoolBuilder() {
		return Builder.requestThreadPool();
	}

	/**
	 * Builder for the thread pool options with recommended defaults for transaction processing.
	 */
	public static ThreadPoolOptions.Builder transactionThreadPoolBuilder() {
		return Builder.transactionThreadPool();
	}

	/**
	 * Builder for the thread pool options with recommended defaults for service tasks.
	 */
	public static ThreadPoolOptions.Builder serviceThreadPoolBuilder() {
		return Builder.serviceThreadPool();
	}

	/**
	 * Builder for the thread pool options with recommended defaults for client-side tasks.
	 */
	public static ThreadPoolOptions.Builder clientThreadPoolBuilder() {
		return Builder.clientThreadPool();
	}

	/**
	 * Builder for the thread pool options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ThreadPoolOptions.Builder builder(@Nonnull ThreadPoolOptions threadPoolOptions) {
		return new Builder(threadPoolOptions);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private int minThreadCount;
		private int maxThreadCount;
		private int threadPriority;
		private int queueSize;

		@Nonnull
		static ThreadPoolOptions.Builder requestThreadPool() {
			return new ThreadPoolOptions.Builder(
				DEFAULT_REQUEST_MIN_THREAD_COUNT,
				DEFAULT_REQUEST_MAX_THREAD_COUNT,
				DEFAULT_REQUEST_THREAD_PRIORITY,
				DEFAULT_REQUEST_QUEUE_SIZE
			);
		}

		@Nonnull
		static ThreadPoolOptions.Builder transactionThreadPool() {
			return new ThreadPoolOptions.Builder(
				DEFAULT_TRANSACTION_MIN_THREAD_COUNT,
				DEFAULT_TRANSACTION_MAX_THREAD_COUNT,
				DEFAULT_TRANSACTION_THREAD_PRIORITY,
				DEFAULT_TRANSACTION_QUEUE_SIZE
			);
		}

		@Nonnull
		static ThreadPoolOptions.Builder serviceThreadPool() {
			return new ThreadPoolOptions.Builder(
				DEFAULT_MIN_SERVICE_THREAD_COUNT,
				DEFAULT_MAX_SERVICE_THREAD_COUNT,
				DEFAULT_SERVICE_THREAD_PRIORITY,
				DEFAULT_SERVICE_QUEUE_SIZE
			);
		}

		@Nonnull
		static ThreadPoolOptions.Builder clientThreadPool() {
			return new ThreadPoolOptions.Builder(
				DEFAULT_CLIENT_MIN_THREAD_COUNT,
				DEFAULT_CLIENT_MAX_THREAD_COUNT,
				DEFAULT_CLIENT_THREAD_PRIORITY,
				DEFAULT_CLIENT_QUEUE_SIZE
			);
		}

		Builder(int minThreadCount, int maxThreadCount, int threadPriority, int queueSize) {
			this.minThreadCount = minThreadCount;
			this.maxThreadCount = maxThreadCount;
			this.threadPriority = threadPriority;
			Assert.isTrue(
				queueSize < 100_000,
				"Queue size must be less than 100_000, " +
					"because evitaDB keeps internal array blocking queue to track timeouts."
			);
			this.queueSize = queueSize;
		}

		Builder(@Nonnull ThreadPoolOptions threadPoolOptions) {
			this.minThreadCount = threadPoolOptions.minThreadCount();
			this.maxThreadCount = threadPoolOptions.maxThreadCount();
			this.threadPriority = threadPoolOptions.threadPriority();
			this.queueSize = threadPoolOptions.queueSize();
		}

		@Nonnull
		public ThreadPoolOptions.Builder minThreadCount(int minThreadCount) {
			this.minThreadCount = minThreadCount;
			return this;
		}

		@Nonnull
		public ThreadPoolOptions.Builder maxThreadCount(int maxThreadCount) {
			this.maxThreadCount = maxThreadCount;
			return this;
		}

		@Nonnull
		public ThreadPoolOptions.Builder threadPriority(int threadPriority) {
			this.threadPriority = threadPriority;
			return this;
		}

		@Nonnull
		public ThreadPoolOptions.Builder queueSize(int queueSize) {
			this.queueSize = queueSize;
			return this;
		}

		@Nonnull
		public ThreadPoolOptions build() {
			Assert.isTrue(
				this.queueSize < 100_000,
				"Queue size must be less than 100_000, " +
					"because evitaDB keeps internal array " +
					"blocking queue to track timeouts."
			);
			return new ThreadPoolOptions(
				this.minThreadCount,
				this.maxThreadCount,
				this.threadPriority,
				this.queueSize
			);
		}

	}

}
