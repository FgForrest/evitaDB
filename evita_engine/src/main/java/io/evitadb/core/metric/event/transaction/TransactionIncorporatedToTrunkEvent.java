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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a transaction was incorporated into a shared data structures.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".TransactionIncorporatedToTrunkEvent")
@Description("Event that is fired when a transaction was incorporated into a shared data structures.")
@Label("Transaction incorporated to trunk")
@ExportDurationMetric(value = "incorporationDurationMilliseconds", label = "Incorporation duration in milliseconds")
@Getter
public class TransactionIncorporatedToTrunkEvent extends AbstractTransactionEvent {
	@ExportMetric(metricName = "transactionsIncorporatedTotal", metricType = MetricType.COUNTER)
	private int collapsedTransactions;

	public TransactionIncorporatedToTrunkEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finishes the event and records the number of transactions processed in single run.
	 * @param collapsedTransactions the number of transactions processed in single run
	 * @return the event
	 */
	@Nonnull
	public TransactionIncorporatedToTrunkEvent finish(int collapsedTransactions) {
		this.collapsedTransactions = collapsedTransactions;
		this.end();
		return this;
	}
}
