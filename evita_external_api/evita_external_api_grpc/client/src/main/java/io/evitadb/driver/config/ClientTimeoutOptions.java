/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.driver.config;

import lombok.ToString;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;

/**
 * Record contains timeout-related settings for the evitaDB Java client.
 *
 * @param timeout              Number of {@link #timeoutUnit()} time units client should wait for server to respond
 *                             before throwing an exception or closing the connection forcefully.
 * @param timeoutUnit          Time unit for {@link #timeout()} property.
 * @param streamingTimeout     Number of {@link #streamingTimeoutUnit()} time units client should wait for server to
 *                             send the next streamed message before it cancels the stream.
 * @param streamingTimeoutUnit Time unit for {@link #streamingTimeout()} property.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ClientTimeoutOptions(
	long timeout,
	@Nonnull TimeUnit timeoutUnit,
	long streamingTimeout,
	@Nonnull TimeUnit streamingTimeoutUnit
) {
	public static final long DEFAULT_TIMEOUT = 5;
	public static final TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.SECONDS;
	public static final long DEFAULT_STREAMING_TIMEOUT = 3600;
	public static final TimeUnit DEFAULT_STREAMING_TIMEOUT_UNIT = TimeUnit.SECONDS;

	/**
	 * Creates a new instance with all default values.
	 */
	public ClientTimeoutOptions() {
		this(DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT, DEFAULT_STREAMING_TIMEOUT, DEFAULT_STREAMING_TIMEOUT_UNIT);
	}

	/**
	 * Builder for the timeout options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static ClientTimeoutOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the timeout options initialized from an existing configuration.
	 */
	@Nonnull
	public static ClientTimeoutOptions.Builder builder(@Nonnull ClientTimeoutOptions timeoutOptions) {
		return new Builder(timeoutOptions);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private long timeout = DEFAULT_TIMEOUT;
		@Nonnull private TimeUnit timeoutUnit = DEFAULT_TIMEOUT_UNIT;
		private long streamingTimeout = DEFAULT_STREAMING_TIMEOUT;
		@Nonnull private TimeUnit streamingTimeoutUnit = DEFAULT_STREAMING_TIMEOUT_UNIT;

		Builder() {
		}

		Builder(@Nonnull ClientTimeoutOptions timeoutOptions) {
			this.timeout = timeoutOptions.timeout();
			this.timeoutUnit = timeoutOptions.timeoutUnit();
			this.streamingTimeout = timeoutOptions.streamingTimeout();
			this.streamingTimeoutUnit = timeoutOptions.streamingTimeoutUnit();
		}

		@Nonnull
		public ClientTimeoutOptions.Builder timeout(long timeout, @Nonnull TimeUnit unit) {
			this.timeout = timeout;
			this.timeoutUnit = unit;
			return this;
		}

		@Nonnull
		public ClientTimeoutOptions.Builder streamingTimeout(long streamingTimeout, @Nonnull TimeUnit unit) {
			this.streamingTimeout = streamingTimeout;
			this.streamingTimeoutUnit = unit;
			return this;
		}

		@Nonnull
		public ClientTimeoutOptions build() {
			return new ClientTimeoutOptions(
				this.timeout, this.timeoutUnit, this.streamingTimeout, this.streamingTimeoutUnit
			);
		}
	}
}
