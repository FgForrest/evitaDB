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

import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired in each transaction processing stage to reflect the time transaction waited in the queue before
 * it was picked up for processing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".Queued")
@Label("Transaction waiting in a queue")
@Description("Event fired at each stage of transaction processing to indicate the amount of time the transaction waited in the queue before being picked up for processing.")
@ExportDurationMetric(label = "Transaction wait time in a queue.")
@Getter
public class TransactionQueuedEvent extends AbstractTransactionEvent {

	/**
	 * The name of the stage the transaction is waiting for.
	 */
	@Label("Transaction stage")
	@Description("The name of the stage the transaction is waiting for.")
	@ExportMetricLabel
	private final String stage;

	public TransactionQueuedEvent(@Nonnull String catalogName, @Nonnull String stage) {
		super(catalogName);
		this.stage = stage;
		this.begin();
	}

	/**
	 * Finishes the event.
	 * @return the event
	 */
	@Nonnull
	public TransactionQueuedEvent finish() {
		this.end();
		return this;
	}

}
