/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.configuration;

import lombok.ToString;

/**
 * DTO contains base server wide settings for the evitaDB.
 *
 * @param coreThreadCount                       Defines count of threads that are spun up in {@link java.util.concurrent.ExecutorService} for handling
 *                                              input requests as well as maintenance tasks. The more catalog in Evita
 *                                              DB there is, the higher count of thread count might be required.
 * @param maxThreadCount                        Defines count of threads that might by spun up at the maximum (i.e. when
 *                                              there are not enough threads to process input requests and background tasks)
 * @param threadPriority                        Defines a {@link Thread#getPriority()} for background threads. The number must be in
 *                                              interval 1-10. The threads with higher priority should be preferred over the ones
 *                                              with lesser priority.
 * @param queueSize                             maximum amount of task accepted to thread pool to wait for a free thread
 * @param closeSessionsAfterSecondsOfInactivity sets the timeout in seconds after which the session is closed
 *                                              automatically if there is no activity observed on it
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ServerOptions(
	int coreThreadCount,
	int maxThreadCount,
	int threadPriority,
	int queueSize,
	int closeSessionsAfterSecondsOfInactivity
) {

	public static final int DEFAULT_CORE_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 10;
	public static final int DEFAULT_MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 20;
	public static final int DEFAULT_THREAD_PRIORITY = 5;
	public static final int DEFAULT_QUEUE_SIZE = 100;
	public static final int DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY = 60 * 20;

	/**
	 * Builder for the server options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ServerOptions.Builder builder() {
		return new Builder();
	}

	public ServerOptions() {
		this(
			DEFAULT_CORE_THREAD_COUNT,
			DEFAULT_MAX_THREAD_COUNT,
			DEFAULT_THREAD_PRIORITY,
			DEFAULT_QUEUE_SIZE,
			DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private int coreThreadCount = DEFAULT_CORE_THREAD_COUNT;
		private int maxThreadCount = DEFAULT_MAX_THREAD_COUNT;
		private int threadPriority = DEFAULT_THREAD_PRIORITY;
		private int queueSize = DEFAULT_QUEUE_SIZE;
		private int closeSessionsAfterSecondsOfInactivity = DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY;

		Builder() {
		}

		public ServerOptions.Builder coreThreadCount(int coreThreadCount) {
			this.coreThreadCount = coreThreadCount;
			return this;
		}

		public ServerOptions.Builder maxThreadCount(int maxThreadCount) {
			this.maxThreadCount = maxThreadCount;
			return this;
		}

		public ServerOptions.Builder threadPriority(int threadPriority) {
			this.threadPriority = threadPriority;
			return this;
		}

		public ServerOptions.Builder queueSize(int queueSize) {
			this.queueSize = queueSize;
			return this;
		}

		public ServerOptions.Builder closeSessionsAfterSecondsOfInactivity(int closeSessionsAfterSecondsOfInactivity) {
			this.closeSessionsAfterSecondsOfInactivity = closeSessionsAfterSecondsOfInactivity;
			return this;
		}

		public ServerOptions build() {
			return new ServerOptions(
				coreThreadCount,
				maxThreadCount,
				threadPriority,
				queueSize,
				closeSessionsAfterSecondsOfInactivity
			);
		}

	}

}
