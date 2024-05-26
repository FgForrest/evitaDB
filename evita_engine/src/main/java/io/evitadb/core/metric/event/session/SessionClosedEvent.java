/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.metric.event.session;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Event that is fired when a transaction is started.
 */
@Name(AbstractSessionEvent.PACKAGE_NAME + ".SessionClosed")
@Description("Event that is fired when a session is closed.")
@ExportInvocationMetric(label = "Sessions closed")
@ExportDurationMetric(label = "Session lifespan duration in milliseconds")
@Label("Session closed")
@Getter
public class SessionClosedEvent extends AbstractSessionEvent {
	@Label("Number of queries performed in session")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int queries;

	@Label("Number of mutation calls performed in session")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int mutations;

	@Label("Oldest session timestamp")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long oldestSessionTimestampSeconds;

	public SessionClosedEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Increases count of queries performed in this session.
	 */
	public void recordQuery() {
		this.queries++;
	}

	/**
	 * Increases count of mutations performed in this session.
	 */
	public void recordMutation() {
		this.mutations++;
	}

	/**
	 * Finishes the event.
	 * @return this event
	 */
	@Nonnull
	public SessionClosedEvent finish(
		@Nullable OffsetDateTime oldestSessionTimestampSeconds
	) {
		this.oldestSessionTimestampSeconds = oldestSessionTimestampSeconds == null ?
			0 : oldestSessionTimestampSeconds.toEpochSecond();
		this.end();
		return this;
	}

}
