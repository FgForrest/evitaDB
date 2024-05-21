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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.core.metric.annotation.ExportDurationMetric;
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetric;
import io.evitadb.core.metric.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Event that is fired when a transaction is finished (either committed or rolled back).
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".Finished")
@Label("Transaction finished")
@ExportDurationMetric(label = "Transaction lifespan duration in milliseconds")
@ExportInvocationMetric(label = "Transactions finished")
@Description(
	"Event that is fired when a transaction is finished either by commit or rollback and corresponding session is closed. " +
		"This also includes waiting for transaction reaching requested stage of processing."
)
@Getter
public class TransactionFinishedEvent extends AbstractTransactionEvent {

	@Label("Transaction resolution")
	@ExportMetricLabel
	private String resolution;

	@Label("Oldest transaction timestamp")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long oldestTransactionTimestampSeconds;

	public TransactionFinishedEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finishes the transaction with the given resolution.
	 * @param oldestTransactionTimestamp the timestamp of the oldest non-finished transaction in the catalog
	 * @param resolution the resolution of the transaction
	 * @return this event
	 */
	@Nonnull
	public TransactionFinishedEvent finishWithResolution(
		@Nullable OffsetDateTime oldestTransactionTimestamp,
        @Nonnull TransactionResolution resolution
	) {
		this.oldestTransactionTimestampSeconds = oldestTransactionTimestamp == null ?
			0 : oldestTransactionTimestamp.toEpochSecond();
		this.resolution = resolution.name();
		this.end();
		return this;
	}

}
