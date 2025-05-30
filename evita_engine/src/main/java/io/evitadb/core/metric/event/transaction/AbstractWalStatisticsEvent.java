/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Abstract ancestor for WAL related events that produce WAL statistics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Getter
abstract class AbstractWalStatisticsEvent extends AbstractTransactionEvent {

	@Label("Oldest WAL entry timestamp")
	@Description("The timestamp of the oldest WAL entry in the WAL files (either active or historical).")
	@Name("oldestWalEntrySeconds")
	@ExportMetric(metricName = AbstractTransactionEvent.PACKAGE_NAME + ".WalStatistics.oldestWalEntryTimestampSeconds", metricType = MetricType.GAUGE)
	long oldestWalEntryTimestampSeconds;

	protected AbstractWalStatisticsEvent(@Nonnull String catalogName) {
		super(catalogName);
	}

	protected AbstractWalStatisticsEvent() {
		super(null);
	}

}
