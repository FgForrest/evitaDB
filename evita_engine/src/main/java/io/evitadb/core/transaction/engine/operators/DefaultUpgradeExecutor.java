/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.CatalogBeingUpgradedException;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.ServiceLoader;

/**
 * Production implementation of {@link UpgradeExecutor} that delegates to
 * {@link CatalogPersistenceServiceFactory#upgradeStorageProtocol} to run the on-disk storage-protocol migration for
 * a given catalog.
 *
 * This executor is wired into the {@link io.evitadb.core.transaction.engine.EngineTransactionManager}
 * at Evita startup and is invoked from
 * {@link UpgradeCatalogFormatMutationOperator}'s work phase once the `BEING_UPGRADED` placeholder
 * is visible in the engine state. It does **not** hold a live catalog persistence service — the
 * factory opens, upgrades, and closes all handles inside a single call.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class DefaultUpgradeExecutor implements UpgradeExecutor {
	@Nonnull private final StorageOptions storageOptions;
	@Nonnull private final TransactionOptions transactionOptions;
	@Nonnull private final Scheduler scheduler;
	@Nonnull private final ExportService exportService;
	@Nonnull private final CatalogFolderContext folderContext;
	@Nonnull private final CatalogPersistenceServiceFactory factory;

	/**
	 * Creates an executor bound to the current Evita configuration. The underlying
	 * {@link CatalogPersistenceServiceFactory} is resolved via the standard ServiceLoader lookup —
	 * the same mechanism used by {@code Catalog.loadCatalog} — so the executor uses the exact same
	 * storage implementation that opens catalogs during normal boot.
	 *
	 * @param storageOptions     storage configuration options
	 * @param transactionOptions transaction configuration options
	 * @param scheduler          scheduler for background tasks during the upgrade
	 * @param exportService      service used to create the pre-migration backup archive
	 * @param folderContext      catalog folder bindings, used to look the catalog's folder token up
	 */
	public DefaultUpgradeExecutor(
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService,
		@Nonnull CatalogFolderContext folderContext
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.exportService = exportService;
		this.folderContext = folderContext;
		this.factory = ServiceLoader
			.load(CatalogPersistenceServiceFactory.class)
			.findFirst()
			.orElseThrow(
				() -> new GenericEvitaInternalError(
					"CatalogPersistenceServiceFactory is not available on the module path — " +
						"DefaultUpgradeExecutor cannot run storage-protocol upgrades."
				)
			);
	}

	@Override
	public void upgradeCatalog(@Nonnull String catalogName) throws CatalogBeingUpgradedException {
		log.info(
			"DefaultUpgradeExecutor: starting storage-protocol upgrade for catalog `{}`.",
			catalogName
		);
		this.factory.upgradeStorageProtocol(
			catalogName, this.folderContext.folderIdFor(catalogName),
			this.storageOptions, this.transactionOptions, this.scheduler, this.exportService
		);
		log.info(
			"DefaultUpgradeExecutor: catalog `{}` storage-protocol upgrade completed.",
			catalogName
		);
	}
}
