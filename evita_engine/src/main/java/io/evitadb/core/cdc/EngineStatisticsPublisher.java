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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.transaction.WalStatisticsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

/**
 * A subscriber that listens for system-level change capture events and publishes statistics
 * about the evitaDB engine and its catalogs.
 *
 * This class implements the Flow.Subscriber interface to receive ChangeSystemCapture events
 * that contain engine mutations. When catalog-related mutations occur, it triggers the emission
 * of appropriate statistics events:
 *
 * - When a catalog is created, it emits both evitaDB system statistics and catalog-specific statistics
 * - When a catalog is removed, it emits system statistics and special "deletion" events for the catalog
 * - When a catalog is renamed, it handles the statistics updates for both the old and new catalog names
 *
 * The class is part of the Change Data Capture (CDC) system that monitors and reacts to
 * changes in the evitaDB engine's state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class EngineStatisticsPublisher implements Flow.Subscriber<ChangeSystemCapture> {
	private final Runnable emitEvitaStatistics;
	private final Consumer<String> emitCatalogStatistics;
	private Subscription subscription;

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		subscription.request(1);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		final EngineMutation mutation = item.body();
		if (mutation instanceof CreateCatalogSchemaMutation ccsm) {
			this.emitEvitaStatistics.run();
			this.emitCatalogStatistics.accept(ccsm.getCatalogName());
		} else if (mutation instanceof RemoveCatalogSchemaMutation rcsm) {
			this.emitEvitaStatistics.run();
			emitDeleteObservabilityEvents(rcsm.getCatalogName());
		} else if (mutation instanceof ModifyCatalogSchemaNameMutation mcsnm) {
			this.emitEvitaStatistics.run();
			if (mcsnm.isOverwriteTarget() && !Objects.equals(mcsnm.getCatalogName(), mcsnm.getNewCatalogName())) {
				emitDeleteObservabilityEvents(mcsnm.getCatalogName());
			}
			this.emitCatalogStatistics.accept(mcsnm.getNewCatalogName());
		} else if (mutation instanceof ModifyCatalogSchemaMutation mcsm) {
			this.emitCatalogStatistics.accept(mcsm.getCatalogName());
		}

		this.subscription.request(1);
	}

	@Override
	public void onError(Throwable throwable) {
		// log the error, but should not occur
		log.error("Error occurred in EngineStatisticsPublisher: ", throwable);
	}

	@Override
	public void onComplete() {
		// do nothing
	}

	/**
	 * Emits delete observability events for the specified catalog.
	 *
	 * This method is responsible for generating specific events related to
	 * the deletion of a catalog, including statistics about the catalog
	 * and its Write-Ahead Log (WAL).
	 *
	 * @param catalogName the name of the catalog for which to emit delete observability events
	 */
	private static void emitDeleteObservabilityEvents(@Nonnull String catalogName) {
		// emit statistics event
		new CatalogStatisticsEvent(
			catalogName,
			0,
			0,
			null
		).commit();
		// emit nullifying WAL event
		new WalStatisticsEvent(
			catalogName,
			null
		).commit();
	}

}
