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


import io.evitadb.api.exception.CatalogBeingUpgradedException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Executes the per-catalog storage-protocol upgrade work driven by `UpgradeCatalogFormatMutation`.
 *
 * Production code uses `DefaultUpgradeExecutor`, which delegates to
 * `CatalogPersistenceServiceFactory#upgradeStorageProtocol` to run the lazy, mutation-driven on-disk migration.
 * The `NoOpUpgradeExecutor` fallback is used by tests and standalone operator usage that only exercises
 * state-transition bookkeeping without touching disk.
 *
 * Implementations are invoked inside the work phase of `UpgradeCatalogFormatMutationOperator` (i.e. after the
 * transition updater has moved the catalog to `BEING_UPGRADED`, and before the completion updater restores it).
 * They are expected to perform all heavy disk I/O for the migration and may throw
 * `CatalogBeingUpgradedException` if the upgrade cannot proceed immediately — the operator translates that into a
 * transient retryable failure rather than wedging the engine.
 *
 * Forward-replay note: the upgrade executor has non-trivial disk side effects, so operators backed by this
 * interface should leave `EngineMutationOperator#replayCompletionState` at its `Optional.empty()` default. The
 * transaction manager then wedges loudly on `walV == stateV + 1` for an upgrade mutation, which is safer than
 * silently re-running the migration mid-crash-recovery.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface UpgradeExecutor {

	/**
	 * Performs the storage-protocol upgrade for the specified catalog. Implementations run long disk I/O here and
	 * may throw {@link CatalogBeingUpgradedException} to surface transient retryable failures to callers.
	 *
	 * @param catalogName name of the catalog to upgrade
	 * @throws CatalogBeingUpgradedException when the upgrade cannot proceed right now and should be retried
	 */
	void upgradeCatalog(@Nonnull String catalogName) throws CatalogBeingUpgradedException;

	/**
	 * No-op implementation used by tests and standalone operator usage that only exercises state-transition
	 * bookkeeping without touching disk. Logs the intent so operators and CDC consumers can still observe that the
	 * upgrade was invoked, but performs no disk work. Production code uses `DefaultUpgradeExecutor` instead, wired
	 * by `Evita` at boot through the five-arg `EngineTransactionManager` constructor.
	 */
	@Slf4j
	final class NoOpUpgradeExecutor implements UpgradeExecutor {

		/**
		 * Singleton instance of the no-op executor — safe to share because the implementation holds no state.
		 */
		public static final NoOpUpgradeExecutor INSTANCE = new NoOpUpgradeExecutor();

		private NoOpUpgradeExecutor() {
		}

		@Override
		public void upgradeCatalog(@Nonnull String catalogName) {
			log.info(
				"NoOpUpgradeExecutor: received request to upgrade catalog `{}` — no-op test/standalone path; " +
					"production wires `DefaultUpgradeExecutor` to perform the on-disk data migration.",
				catalogName
			);
		}
	}
}
