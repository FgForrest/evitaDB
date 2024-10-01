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
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a new catalog version is propagated shared view.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".NewCatalogVersionPropagated")
@Description("Event that is fired when a new catalog version is propagated to a shared view.")
@Label("New catalog version propagated")
@ExportDurationMetric(label = "New catalog version propagation duration in milliseconds")
@ExportInvocationMetric(label = "Catalog versions propagated")
@Getter
public class NewCatalogVersionPropagatedEvent extends AbstractTransactionEvent {

	@Label("Transactions propagated to live view.")
	@Description("The number of transactions that were propagated to the live view in a single transition.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private int collapsedTransactions;

	public NewCatalogVersionPropagatedEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finishes the event.
	 * @return the event
	 */
	@Nonnull
	public NewCatalogVersionPropagatedEvent finish(int collapsedTransactions) {
		this.end();
		this.collapsedTransactions = collapsedTransactions;
		return this;
	}

}
