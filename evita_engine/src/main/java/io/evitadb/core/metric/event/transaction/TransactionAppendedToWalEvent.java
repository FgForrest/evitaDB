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
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a transaction is fully written into the shared WAL.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".AppendedToWal")
@Description("Event that is fired when a transaction passed conflict resolution stage.")
@Label("Transaction appended to WAL")
@ExportDurationMetric(label = "Appending transaction to shared WAL duration in milliseconds")
@ExportInvocationMetric(label = "Transactions appended to WAL")
@Getter
public class TransactionAppendedToWalEvent extends AbstractTransactionEvent {
	@Label("Atomic mutations appended.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private int appendedAtomicMutations;

	public TransactionAppendedToWalEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finishes the event.
	 * @return the event
	 */
	@Nonnull
	public TransactionAppendedToWalEvent finish(int appendedAtomicMutations) {
		this.appendedAtomicMutations = appendedAtomicMutations;
		this.end();
		return this;
	}
}
