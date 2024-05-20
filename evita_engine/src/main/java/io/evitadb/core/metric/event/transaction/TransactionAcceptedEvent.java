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

import io.evitadb.core.metric.annotation.ExportDurationMetric;
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a transaction passed conflict resolution stage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".TransactionAcceptedEvent")
@Description("Event that is fired when a transaction passed conflict resolution stage.")
@Label("Transaction accepted")
@ExportDurationMetric(value = "resolutionDurationMilliseconds", label = "Conflict resolution duration in milliseconds")
@ExportInvocationMetric(value = "transactionsAcceptedTotal", label = "Transactions accepted")
@Getter
public class TransactionAcceptedEvent extends AbstractTransactionEvent {
	/**
	 * The resolution of the transaction (either commit or rollback) - rollback happens in case the transaction is in
	 * conflict with another already committed and accepted transaction.
	 */
	@Label("Transaction resolution")
	@ExportMetricLabel
	private String resolution;

	public TransactionAcceptedEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finish the transaction with the given resolution.
	 * @param resolution the resolution of the transaction
	 * @return this event
	 */
	@Nonnull
	public TransactionAcceptedEvent finishWithResolution(@Nonnull TransactionResolution resolution) {
		this.resolution = resolution.name();
		this.end();
		return this;
	}

}
