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
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sets the internal state of the catalog to active or inactive based on the provided mutation.
 * This method handles the activation or deactivation of a catalog and notifies the observer about the progress
 * while executing the task. It also triggers completion or failure callbacks accordingly.
 *
 * Forward-replay is intentionally **not** implemented here. Activation requires a previously-loaded `Catalog`
 * (produced by `evita.loadCatalogInternal`) and deactivation closes all sessions and terminates the catalog. Neither
 * side effect can be recreated from the WAL mutation alone at replay time without re-running the work phase. The
 * default `Optional.empty()` in `EngineMutationOperator` causes the transaction manager to wedge loudly — safer than
 * silently producing an inconsistent catalog snapshot.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class SetCatalogStateMutationOperator implements EngineMutationOperator<Void, SetCatalogStateMutation> {
	/**
	 * Warned when releasing the catalog's resources fails - see
	 * {@link CatalogTerminationHelper#terminateQuietly} for why the failure is not propagated.
	 */
	private static final String TERMINATION_FAILURE = "Failed to terminate catalog `{}` while changing its " +
		"state - the handles its persistence service holds into the storage folder stay open until the server " +
		"is restarted.";
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull SetCatalogStateMutation engineMutation) {
		if (engineMutation.isActive()) {
			return "Activating catalog `" + engineMutation.getCatalogName() + "`";
		} else {
			return "Deactivating catalog `" + engineMutation.getCatalogName() + "`";
		}
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull SetCatalogStateMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		final CatalogState transitionState = mutation.isActive() ?
			CatalogState.BEING_ACTIVATED : CatalogState.BEING_DEACTIVATED;
		final CatalogContract theCatalog = evita.getCatalogInstanceOrThrowException(catalogName);
		final boolean readOnly = evita.getEngineState().isReadOnly(catalogName);

		transitionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withCatalog(
							SetCatalogStateMutationOperator.this.folderContext.createUnusableCatalog(
								catalogName,
								transitionState,
								(cn, folderId, root) ->
									new CatalogTransitioningException(cn, folderId, root, transitionState)
							)
						).build();
				}
			}
		);

		if (mutation.isActive()) {
			return new ProgressingFuture<>(
				0,
				Collections.singletonList(evita.loadCatalogInternal(catalogName, readOnly)),
				(progressingFuture, loadedCatalog) -> {
					// the instance the load's write-ahead log replay settled on - see `Evita#loadCatalogInternal`
					final CatalogContract installed = loadedCatalog.iterator().next();
					// Failing to record the activation must leave no live instance of this catalog behind, and by
					// now the load's success callback has already published `installed` into the engine state, where
					// sessions may be opening on it. Nothing else will ever close it either: shutdown makes this
					// path reachable, because `Evita#closeCatalogs` clears the engine state before draining the
					// mutations still in flight, and an instance nobody terminates keeps its storage handles open
					// for the life of the process. So the failure branch below first withdraws the instance from the
					// engine state, so that no reader is handed a closed catalog, and only then terminates it.
					boolean installedIntoEngineState = false;
					try {
						completionEngineStateUpdater.accept(
							new AbstractEngineStateUpdater(transactionId, mutation) {
								@Override
								public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
									return ExpandedEngineState
										.builder(expandedEngineState)
										.withVersion(version)
										.withCatalog(
											newestOf(installed, expandedEngineState.getCatalog(catalogName).orElse(null))
										)
										.build();
								}
							}
						);
						installedIntoEngineState = true;
					} finally {
						if (!installedIntoEngineState) {
							withdrawFailedActivation(
								evita, transactionId, mutation, catalogName, transitionEngineStateUpdater
							);
							CatalogTerminationHelper.terminateQuietly(log, installed, catalogName, TERMINATION_FAILURE);
						}
					}
					// Emit the host event AFTER the engine state has been updated so the host
					// event lands strictly after the underlying mutation in the system CDC stream.
					evita.notifyCatalogStateSettled(catalogName, installed.getCatalogState());
					return null;
				}
			);
		} else {
			return new ProgressingFuture<>(
				0,
				progressingFuture -> {
					// Installs a registry when the catalog has none - see `Evita#suspendCatalogSessions`. Without
					// it a catalog nobody has queried since boot is not quiesced at all, and a session opened
					// while it is being deactivated is served against a catalog about to be terminated.
					evita.suspendCatalogSessions(catalogName, SuspendOperation.REJECT);

					// The teardown below has to run even when the engine state update fails, and shutdown makes
					// that reachable: `Evita#closeCatalogs` clears the engine state before draining the mutations
					// still in flight. What that shutdown pass terminated on its way through is the
					// `UnusableCatalog` placeholder the transition phase installed, whose `terminate()` only flips
					// a flag - the real `Catalog` is held by this operator and by nothing else. Letting the failure
					// skip the block below therefore leaves the real catalog's folder lock held for the life of
					// the process, and the next `Evita` opened over the same directory fails with
					// `FolderAlreadyUsedException`.
					boolean engineStateTransitioned = false;
					try {
						completionEngineStateUpdater.accept(
							new AbstractEngineStateUpdater(transactionId, mutation) {
								@Override
								public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
									return ExpandedEngineState
										.builder(expandedEngineState)
										.withVersion(version)
										.withCatalog(
											SetCatalogStateMutationOperator.this.folderContext.createUnusableCatalog(
												catalogName, CatalogState.INACTIVE,
												CatalogInactiveException::new
											)
										)
										.build();
								}
							}
						);
						engineStateTransitioned = true;
					} finally {
						// Wrap the destructive side-effects in try-finally so the host event fires
						// even if `theCatalog.terminate()` throws — the engine state has already
						// transitioned to INACTIVE and HOST subscribers must observe that
						// transition regardless of downstream cleanup failures.
						try {
							evita.removeCatalogSessionRegistryIfPresent(catalogName);
							CatalogTerminationHelper.terminateQuietly(
								log, theCatalog, catalogName, TERMINATION_FAILURE
							);
						} finally {
							// Emit the host event AFTER the engine state and the live `Catalog`
							// resources have been torn down so subscribers see the INACTIVE settlement
							// strictly after the mutation in the system CDC stream. Only when the state
							// actually transitioned, though: announcing a settlement the engine state never
							// took would misinform every subscriber.
							if (engineStateTransitioned) {
								evita.notifyCatalogStateSettled(catalogName, CatalogState.INACTIVE);
							}
						}
					}
					return null;
				}
			);
		}
	}

	/**
	 * Picks the catalog instance the activation installs: the one the load settled on, unless the engine state
	 * already holds a newer instance of the very same catalog.
	 *
	 * The future of `Evita#loadCatalogInternal` yields the instance its write-ahead log was replayed to, and its
	 * success callback has already put that instance into the engine state. From that moment sessions open on it
	 * and commits move it further, each of them swapping its successor in through `Evita#replaceCatalogReference`.
	 * Installing the instance the future yielded over such a successor would roll the activated catalog back by
	 * those commits until the next commit replaces it again, so the newer instance is kept.
	 *
	 * Any real `Catalog` present here belongs to that replayed lineage, which is why the version alone tells the
	 * instances apart. The transition phase installed an `UnusableCatalog` placeholder; a concurrent activation,
	 * deactivation or go-live of the same catalog is refused by its conflict key; and while it is being activated,
	 * the only code installing a real instance under its name is the load's success callback and the commit
	 * pipeline of the instance that callback published.
	 *
	 * **This keeps a commit that landed before the state was read, not every commit.** The choice is made from
	 * the state read under the engine state lock, but published only after the engine write-ahead log append that
	 * follows. `Evita#replaceCatalogReference` swaps a commit into the wrapper of the snapshot being replaced, not
	 * into the fresh one this activation publishes, so a commit landing during that append is dropped from the
	 * engine state until the next commit replaces the instance again. Readers see the chosen instance for that
	 * span; nothing is lost, because every commit builds on its transaction manager's last finalized catalog rather
	 * than on the engine state.
	 *
	 * @param loaded  the instance the load future completed with
	 * @param current the instance the engine state holds right now, `null` when it holds none
	 * @return the instance to install
	 */
	@Nonnull
	private static CatalogContract newestOf(@Nonnull CatalogContract loaded, @Nullable CatalogContract current) {
		return current instanceof Catalog liveCatalog && liveCatalog.getVersion() > loaded.getVersion()
			? liveCatalog
			: loaded;
	}

	/**
	 * Takes the instance of a catalog whose activation could not be recorded back out of the engine state, putting
	 * the `INACTIVE` placeholder the catalog is persisted as behind its name.
	 *
	 * Only a failure ahead of the durability boundary reaches this. `EngineTransactionManager` reports no failure
	 * past the engine write-ahead log append, and an append that fails leaves the engine bootstrap record where it
	 * was, so the persisted engine state still lists the catalog as inactive - and the in-memory state must not
	 * claim more. Yet the load's success callback has already published the instance the caller is about to
	 * terminate; left there, the engine state would list an active catalog whose storage is closed until the
	 * process restarts.
	 *
	 * The exchange keeps the engine state version, as nothing is being committed. It is best-effort: a shutdown
	 * has already cleared the engine state, which then holds nothing to withdraw, and any other failure is logged
	 * so that the caller still terminates the instance.
	 *
	 * @param evita                        the engine whose state is corrected
	 * @param transactionId                id of the activation transaction
	 * @param mutation                     the activation mutation that could not be recorded
	 * @param catalogName                  name of the catalog being activated
	 * @param transitionEngineStateUpdater updater that exchanges the engine state without persisting it
	 */
	private void withdrawFailedActivation(
		@Nonnull Evita evita,
		@Nonnull UUID transactionId,
		@Nonnull SetCatalogStateMutation mutation,
		@Nonnull String catalogName,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater
	) {
		try {
			transitionEngineStateUpdater.accept(
				new AbstractEngineStateUpdater(transactionId, mutation) {
					@Override
					public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
						return ExpandedEngineState
							.builder(expandedEngineState)
							.withCatalog(
								SetCatalogStateMutationOperator.this.folderContext.createUnusableCatalog(
									catalogName, CatalogState.INACTIVE, CatalogInactiveException::new
								)
							)
							.build();
					}
				}
			);
			// the load's success callback has already announced the replayed catalog as settled, so the
			// subscribers that heard it have to hear that it went back
			evita.notifyCatalogStateSettled(catalogName, CatalogState.INACTIVE);
		} catch (InstanceTerminatedException shuttingDown) {
			// the engine state is cleared during shutdown, so there is no published instance left to withdraw
			log.debug("Engine shut down while withdrawing the failed activation of catalog `{}`.", catalogName);
		} catch (Throwable withdrawalFailure) {
			log.error(
				"Failed to withdraw catalog `{}` from the engine state after its activation could not be " +
					"recorded - it stays listed as active while its storage is closed, until the server is " +
					"restarted. Nothing on disk is damaged; the engine records the catalog as inactive.",
				catalogName, withdrawalFailure
			);
		}
	}

}
