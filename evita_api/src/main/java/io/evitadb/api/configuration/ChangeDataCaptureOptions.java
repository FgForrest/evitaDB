/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import lombok.ToString;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow;

/**
 * Change Data Capture (CDC) options define how the server should capture and publish changes to the database.
 * CDC allows clients to subscribe to a stream of changes that occur in the database, enabling real-time
 * data synchronization, event-driven architectures, and audit logging.
 *
 * @param enabled                Whether CDC is enabled. If true, the server captures all changes to the database
 *                               and makes them available for subscription. If false, CDC is disabled and no changes
 *                               are captured or published.
 * @param recentEventsCacheLimit The maximum number of recent change events to keep in memory. This buffer
 *                               allows subscribers to catch up with recent changes even if they temporarily
 *                               disconnect. Once the buffer is full, older events are discarded.
 * @param subscriberBufferSize   The buffer size for each subscriber. This controls how many events can be
 *                               queued for a subscriber before it must process them. If a subscriber cannot
 *                               keep up with the rate of changes, it may miss events once its buffer is full.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ChangeDataCaptureOptions(
	boolean enabled,
	int recentEventsCacheLimit,
	int subscriberBufferSize
) {
	public static final boolean DEFAULT_CDC_ENABLED = true;
	public static final int DEFAULT_RECENT_EVENTS_CACHE_LIMIT = Flow.defaultBufferSize();
	public static final int DEFAULT_SUBSCRIBER_BUFFER_SIZE = Flow.defaultBufferSize();

	/**
	 * Builder for the CDC options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ChangeDataCaptureOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the CDC options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ChangeDataCaptureOptions.Builder builder(@Nonnull ChangeDataCaptureOptions changeDataCaptureOptions) {
		return new Builder(changeDataCaptureOptions);
	}

	public ChangeDataCaptureOptions() {
		this(
			DEFAULT_CDC_ENABLED,
			DEFAULT_RECENT_EVENTS_CACHE_LIMIT,
			DEFAULT_SUBSCRIBER_BUFFER_SIZE
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private boolean enabled = DEFAULT_CDC_ENABLED;
		private int recentEventsCacheLimit = DEFAULT_RECENT_EVENTS_CACHE_LIMIT;
		private int subscriberBufferSize = DEFAULT_SUBSCRIBER_BUFFER_SIZE;

		Builder() {
		}

		Builder(@Nonnull ChangeDataCaptureOptions changeDataCaptureOptions) {
			this.enabled = changeDataCaptureOptions.enabled();
			this.recentEventsCacheLimit = changeDataCaptureOptions.recentEventsCacheLimit();
			this.subscriberBufferSize = changeDataCaptureOptions.subscriberBufferSize();
		}

		@Nonnull
		public ChangeDataCaptureOptions.Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		@Nonnull
		public ChangeDataCaptureOptions.Builder recentEventsCacheLimit(int recentEventsCacheLimit) {
			this.recentEventsCacheLimit = recentEventsCacheLimit;
			return this;
		}

		@Nonnull
		public ChangeDataCaptureOptions.Builder subscriberBufferSize(int subscriberBufferSize) {
			this.subscriberBufferSize = subscriberBufferSize;
			return this;
		}

		@Nonnull
		public ChangeDataCaptureOptions build() {
			return new ChangeDataCaptureOptions(
				this.enabled,
				this.recentEventsCacheLimit,
				this.subscriberBufferSize
			);
		}
	}
}
