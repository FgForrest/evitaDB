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


import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CatalogBeingUpgradedException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Operator that drives a per-catalog storage-protocol upgrade.
 *
 * The operator orchestrates the three-step state dance that makes an upgrade durable and observable:
 *
 * 1. **Transition phase** — replaces the in-memory catalog reference with an `UnusableCatalog` placeholder in state
 *    `BEING_UPGRADED` so any concurrent access fails fast with `CatalogBeingUpgradedException` instead of
 *    silently reading half-migrated data.
 * 2. **Work phase** — delegates the actual upgrade execution to the injected `UpgradeExecutor`. Production
 *    deployments use `DefaultUpgradeExecutor`, which `Evita` injects at boot time through the seven-arg
 *    `EngineTransactionManager` ctor; that executor runs the on-disk data migration
 *    via `CatalogPersistenceServiceFactory#upgradeStorageProtocol`. The no-op `NoOpUpgradeExecutor` remains
 *    the fallback for tests and standalone operator usage (selected by the single-arg convenience ctor) — keeping
 *    the work phase behind an injectable interface lets tests drive state-transition bookkeeping without touching disk.
 * 3. **Completion phase** — restores the prior catalog reference so the catalog lands back in its original
 *    operational state (typically `ALIVE`, but also `INACTIVE`, `WARMING_UP`, etc. — whatever it was before the
 *    upgrade started). The restored reference is exactly the catalog contract captured in the work phase; no
 *    reload is performed because the upgrade executor finishes with every storage handle closed.
 *
 * Forward-replay is intentionally **not** implemented. The upgrade executor will eventually perform non-pure disk I/O
 * (migrating data files in place), and replaying that during crash recovery could corrupt an already-migrated
 * catalog. The default `Optional.empty()` in `EngineMutationOperator#replayCompletionState` therefore wedges the
 * engine loudly on a crash mid-upgrade — safer than silent re-execution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UpgradeCatalogFormatMutationOperator
	implements EngineMutationOperator<Void, UpgradeCatalogFormatMutation> {

	/**
	 * Storage root directory used to build the placeholder catalog path for the transient `UnusableCatalog`
	 * installed while the upgrade is running.
	 */
	@Nonnull private final Path storageDirectory;
	/**
	 * Executor invoked during the work phase. Production wiring injects `DefaultUpgradeExecutor`
	 * through the full constructor (driven by `Evita` at boot); the single-arg convenience ctor
	 * defaults to `NoOpUpgradeExecutor` for tests and standalone usage.
	 */
	@Nonnull private final UpgradeExecutor upgradeExecutor;

	/**
	 * Convenience constructor defaulting to `NoOpUpgradeExecutor.INSTANCE`. Intended for tests and
	 * standalone operator usage that only exercises state-transition bookkeeping without touching
	 * disk. Production code paths go through the two-arg constructor with `DefaultUpgradeExecutor`.
	 *
	 * @param storageDirectory storage root directory
	 */
	public UpgradeCatalogFormatMutationOperator(@Nonnull Path storageDirectory) {
		this(storageDirectory, UpgradeExecutor.NoOpUpgradeExecutor.INSTANCE);
	}

	/**
	 * Full constructor. This is the production wiring path — `Evita` injects a `DefaultUpgradeExecutor` here through
	 * the five-arg `EngineTransactionManager` constructor at boot time, so the operator drives the real on-disk
	 * v4→v5 migration.
	 *
	 * @param storageDirectory storage root directory
	 * @param upgradeExecutor  upgrade executor invoked during the work phase
	 */
	public UpgradeCatalogFormatMutationOperator(
		@Nonnull Path storageDirectory,
		@Nonnull UpgradeExecutor upgradeExecutor
	) {
		this.storageDirectory = storageDirectory;
		this.upgradeExecutor = upgradeExecutor;
	}

	@Nonnull
	@Override
	public String getOperationName(@Nonnull UpgradeCatalogFormatMutation engineMutation) {
		return "Upgrading catalog `" + engineMutation.getCatalogName() + "` from protocol v" +
			engineMutation.getFromProtocolVersion() + " to v" + engineMutation.getToProtocolVersion();
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull UpgradeCatalogFormatMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		final CatalogContract priorCatalog = evita.getEngineState().getCatalog(catalogName).orElse(null);
		Assert.isPremiseValid(
			priorCatalog != null,
			() -> "Catalog `" + catalogName + "` is not registered with the engine — " +
				"UpgradeCatalogFormatMutation requires a pre-existing catalog."
		);

		final Path catalogFolder = this.storageDirectory.resolve(catalogName);

		// Transition phase — install the BEING_UPGRADED placeholder so any concurrent access fails fast
		// with a transient `CatalogBeingUpgradedException` while the work phase runs. We use
		// `withInFlightPlaceholder` (not `withCatalog`) so the catalog's persisted bucket (active /
		// inactive) is preserved: a crash mid-work-phase leaves the name in its original bucket, and
		// the next boot's load-throws-then-retry path auto-issues a fresh `UpgradeCatalogFormatMutation`.
		transitionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withInFlightPlaceholder(
							new UnusableCatalog(
								catalogName,
								CatalogState.BEING_UPGRADED,
								catalogFolder,
								(cn, path) -> new CatalogBeingUpgradedException(cn)
							)
						)
						.build();
				}
			}
		);

		// Work phase — delegate the actual migration to the injected executor. Production uses
		// `DefaultUpgradeExecutor` (wired by `Evita` at boot) which runs the on-disk v4→v5 migration
		// via `CatalogPersistenceServiceFactory#upgradeStorageProtocol`; the no-op executor is the
		// test/standalone fallback and only logs the intent without touching disk.
		return new ProgressingFuture<>(
			0,
			progressingFuture -> {
				this.upgradeExecutor.upgradeCatalog(catalogName);

				// Completion phase — restore the catalog reference captured before the transition so the
				// catalog lands back in its prior operational state (typically ALIVE). We use
				// `withInFlightPlaceholder` (not `withCatalog`) so the persisted bucket is preserved
				// regardless of whether the prior reference was a live `Catalog` or an `UnusableCatalog`
				// placeholder (boot path — the name is in `activeCatalogs` but the load hasn't installed
				// a live `Catalog` yet; `withCatalog` would mis-bucket that into `inactiveCatalogs` via
				// its `instanceof Catalog` branch). If the upgrade was issued at runtime against an
				// already-loaded `Catalog`, the name was already in `activeCatalogs` and stays there —
				// the real `Catalog` instance is then re-installed by the regular load or runtime path
				// via `Evita#replaceCatalogReference`, which uses the correct bucket move at that time.
				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								.withInFlightPlaceholder(priorCatalog)
								.build();
						}
					}
				);

				return null;
			}
		);
	}

}
