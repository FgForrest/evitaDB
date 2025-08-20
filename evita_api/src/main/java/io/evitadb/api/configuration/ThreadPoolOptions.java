/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
 * @param minThreadCount Defines count of threads that are spun up in {@link java.util.concurrent.ExecutorService} for handling
 *                       input requests as well as maintenance tasks. The more catalog in Evita
 *                       DB there is, the higher count of thread count might be required.
 * @param maxThreadCount Defines count of threads that might be spun up at the maximum (i.e. when
 *                       there are not enough threads to process input requests and background tasks)
 * @param threadPriority Defines a {@link Thread#getPriority()} for background threads. The number must be in
 *                       interval 1-10. The threads with higher priority should be preferred over the ones
 *                       with lesser priority.
 * @param queueSize      maximum amount of task accepted to thread pool to wait for a free thread
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record ThreadPoolOptions(
	int minThreadCount,
	int maxThreadCount,
	int threadPriority,
	int queueSize
) {
	public static final int DEFAULT_REQUEST_MIN_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 10;
	public static final int DEFAULT_REQUEST_MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 20;
	public static final int DEFAULT_REQUEST_THREAD_PRIORITY = 8;
	public static final int DEFAULT_REQUEST_QUEUE_SIZE = 100;
	public static final int DEFAULT_TRANSACTION_MIN_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
	public static final int DEFAULT_TRANSACTION_MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() << 1;
	public static final int DEFAULT_TRANSACTION_THREAD_PRIORITY = 5;
	public static final int DEFAULT_TRANSACTION_QUEUE_SIZE = 100;
	public static final int DEFAULT_MIN_SERVICE_THREAD_COUNT = Math.min(Runtime.getRuntime().availableProcessors(), 1);
	public static final int DEFAULT_MAX_SERVICE_THREAD_COUNT = Math.min(Runtime.getRuntime().availableProcessors() << 1, 1);
	public static final int DEFAULT_SERVICE_THREAD_PRIORITY = 1;
	public static final int DEFAULT_SERVICE_QUEUE_SIZE = 20;

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

		public ThreadPoolOptions build() {
			return new ThreadPoolOptions(
				this.minThreadCount,
				this.maxThreadCount,
				this.threadPriority,
				this.queueSize
			);
		}

	}

}
