/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Evita is a specialized database with easy-to-use API for e-commerce systems. Purpose of this research is creating a fast
 * and scalable engine that handles all complex tasks that e-commerce systems has to deal with on a daily basis. Evita should
 * operate as a fast secondary lookup / search index used by application frontends. We aim for order of magnitude better
 * latency (10x faster or better) for common e-commerce tasks than other solutions based on SQL or NoSQL databases on the
 * same hardware specification. Evita should not be used for storing and handling primary data, and we don't aim for ACID
 * properties nor data corruption guarantees. Evita "index" must be treated as something that could be dropped any time and
 * built up from scratch easily again.
 *
 * This interface represents the main entrance to the evitaDB contents.
 * Evita contract is thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public interface EvitaContract extends AutoCloseable {

	/**
	 * Returns true if the Evita instance is active and ready to serve requests - i.e. method {@link #close()} has not
	 * been called yet.
	 *
	 * @return true if the Evita instance is active
	 */
	boolean isActive();

	/**
	 * Creates {@link EvitaSessionContract} for querying the database.
	 *
	 * Remember to {@link #close()} or {@link #terminateSession(EvitaSessionContract)} when your work with Evita is finished.
	 * EvitaSession is not thread safe!
	 *
	 * @param catalogName - unique name of the catalog, refers to {@link CatalogContract#getName()}
	 * @return new instance of EvitaSession
	 * @see #close()
	 * @see #createSession(SessionTraits) for complete configuration options.
	 */
	@Nonnull
	default EvitaSessionContract createReadOnlySession(@Nonnull String catalogName) {
		return createSession(new SessionTraits(catalogName));
	}

	/**
	 * Creates {@link EvitaSessionContract} for querying and altering the database.
	 *
	 * Remember to {@link #close()} or {@link #terminateSession(EvitaSessionContract)} when your work with Evita is finished.
	 * EvitaSession is not thread safe!
	 *
	 * @param catalogName - unique name of the catalog, refers to {@link CatalogContract#getName()}
	 * @return new instance of EvitaSession
	 * @see #close()
	 * @see #createSession(SessionTraits) for complete configuration options.
	 */
	@Nonnull
	default EvitaSessionContract createReadWriteSession(@Nonnull String catalogName) {
		return createSession(new SessionTraits(catalogName, SessionFlags.READ_WRITE));
	}

	/**
	 * Creates {@link EvitaSessionContract} for querying the database. This is the most versatile method for initializing a new
	 * session allowing to pass all configurable options in `traits` argument.
	 *
	 * Remember to {@link #close()} or {@link #terminateSession(EvitaSessionContract)} when your work with Evita is finished.
	 * EvitaSession is not thread safe!
	 *
	 * @return new instance of EvitaSession
	 * @see #close()
	 */
	@Nonnull
	EvitaSessionContract createSession(@Nonnull SessionTraits traits);

	/**
	 * Method returns active session by its unique id or NULL if such session is not found.
	 */
	@Nonnull
	Optional<EvitaSessionContract> getSessionById(@Nonnull UUID uuid);

	/**
	 * Terminates existing {@link EvitaSessionContract}. When this method is called no additional calls to this EvitaSession
	 * is accepted and all will terminate with {@link InstanceTerminatedException}.
	 */
	void terminateSession(@Nonnull EvitaSessionContract session);

	/**
	 * Returns complete listing of all catalogs known to the Evita instance.
	 */
	@Nonnull
	Set<String> getCatalogNames();

	/**
	 * Returns the state of the catalog of particular name.
	 *
	 * @return {@link CatalogState} of the catalog or empty optional if the catalog doesn't exist
	 */
	@Nonnull
	Optional<CatalogState> getCatalogState(@Nonnull String catalogName);

	/**
	 * Creates new catalog of particular name if it doesn't exist. The schema of the catalog (should it was created or
	 * not) is returned to the response.
	 *
	 * @param catalogName name of the catalog
	 * @return new instance of {@link SealedCatalogSchema} connected with the catalog
	 */
	@Nonnull
	CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName);

	/**
	 * Changes state of the catalog from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}.
	 * At the end of this method the catalog has transitioned to the {@link CatalogState#ALIVE} state and is ready
	 * to be used.
	 *
	 * @see CatalogState
	 */
	default void makeCatalogAlive(@Nonnull String catalogName) {
		makeCatalogAliveWithProgress(catalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Changes state of the catalog from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}. Returns
	 * {@link Progress} that can be used to track the progress of the operation.
	 *
	 * @see CatalogState
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Progress<CommitVersions> makeCatalogAliveWithProgress(@Nonnull String catalogName);

	/**
	 * Duplicates an existing catalog to create a new catalog with the specified name.
	 * The source catalog must exist and be in a valid state (ALIVE or WARMING_UP).
	 * The target catalog name must not already exist.
	 * At the end of this method the new catalog has been created with all data and schema
	 * copied from the source catalog.
	 *
	 * @param catalogName name of the source catalog to duplicate
	 * @param newCatalogName name of the new catalog to create with duplicated contents
	 */
	default void duplicateCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		duplicateCatalogWithProgress(catalogName, newCatalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Duplicates an existing catalog to create a new catalog with the specified name.
	 * The source catalog must exist and be in a valid state (ALIVE or WARMING_UP).
	 * The target catalog name must not already exist.
	 * Returns {@link Progress} that can be used to track the progress of the operation.
	 *
	 * @param catalogName name of the source catalog to duplicate
	 * @param newCatalogName name of the new catalog to create with duplicated contents
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Progress<Void> duplicateCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName);

	/**
	 * Activates catalog by loading it from disk into memory. Changes state of the catalog from
	 * {@link CatalogState#INACTIVE} to {@link CatalogState#ALIVE}.
	 * At the end of this method the catalog has transitioned to the {@link CatalogState#ALIVE} state and is ready
	 * to be used.
	 *
	 * @param catalogName name of the catalog to activate
	 * @see CatalogState
	 */
	default void activateCatalog(@Nonnull String catalogName) {
		activateCatalogWithProgress(catalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Activates catalog by loading it from disk into memory. Changes state of the catalog from
	 * {@link CatalogState#INACTIVE} to {@link CatalogState#ALIVE}. Returns {@link Progress} that can be used
	 * to track the progress of the operation.
	 *
	 * @param catalogName name of the catalog to activate
	 * @return progress that can be used to track the progress of the operation
	 * @see CatalogState
	 */
	@Nonnull
	Progress<Void> activateCatalogWithProgress(@Nonnull String catalogName);

	/**
	 * Deactivates catalog by unloading it from memory. Changes state of the catalog from
	 * {@link CatalogState#ALIVE} to {@link CatalogState#INACTIVE}.
	 * At the end of this method the catalog has transitioned to the {@link CatalogState#INACTIVE} state and is
	 * no longer loaded in memory, but its data remains on disk.
	 *
	 * @param catalogName name of the catalog to deactivate
	 * @see CatalogState
	 */
	default void deactivateCatalog(@Nonnull String catalogName) {
		deactivateCatalogWithProgress(catalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Deactivates catalog by unloading it from memory. Changes state of the catalog from
	 * {@link CatalogState#ALIVE} to {@link CatalogState#INACTIVE}. Returns {@link Progress} that can be used
	 * to track the progress of the operation.
	 *
	 * @param catalogName name of the catalog to deactivate
	 * @return progress that can be used to track the progress of the operation
	 * @see CatalogState
	 */
	@Nonnull
	Progress<Void> deactivateCatalogWithProgress(@Nonnull String catalogName);

	/**
	 * Makes catalog mutable by enabling write operations on it. This allows the catalog to accept
	 * data modifications and schema changes.
	 * At the end of this method the catalog has transitioned to mutable state and is ready
	 * to accept write operations.
	 *
	 * @param catalogName name of the catalog to make mutable
	 */
	default void makeCatalogMutable(@Nonnull String catalogName) {
		makeCatalogMutableWithProgress(catalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Makes catalog mutable by enabling write operations on it. This allows the catalog to accept
	 * data modifications and schema changes. Returns {@link Progress} that can be used
	 * to track the progress of the operation.
	 *
	 * @param catalogName name of the catalog to make mutable
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Progress<Void> makeCatalogMutableWithProgress(@Nonnull String catalogName);

	/**
	 * Makes catalog immutable by disabling write operations on it. This puts the catalog into
	 * read-only mode where no data modifications or schema changes are allowed.
	 * At the end of this method the catalog has transitioned to immutable state and will
	 * reject any write operations.
	 *
	 * @param catalogName name of the catalog to make immutable
	 */
	default void makeCatalogImmutable(@Nonnull String catalogName) {
		makeCatalogImmutableWithProgress(catalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Makes catalog immutable by disabling write operations on it. This puts the catalog into
	 * read-only mode where no data modifications or schema changes are allowed. Returns {@link Progress}
	 * that can be used to track the progress of the operation.
	 *
	 * @param catalogName name of the catalog to make immutable
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Progress<Void> makeCatalogImmutableWithProgress(@Nonnull String catalogName);

	/**
	 * Renames existing catalog to a new name. The `newCatalogName` must not clash with any existing catalog name,
	 * otherwise exception is thrown. If you need to rename catalog to a name of existing catalog use
	 * the {@link #replaceCatalog(String, String)} method instead.
	 *
	 * In case exception occurs the original catalog (`catalogName`) is guaranteed to be untouched,
	 * and the `newCatalogName` will not be present.
	 *
	 * At the end of this method the catalog is already renamed and the new name should be used for any further
	 * operations with the catalog.
	 *
	 * @param catalogName    name of the catalog that will be renamed
	 * @param newCatalogName new name of the catalog
	 * @throws CatalogAlreadyPresentException when another catalog with `newCatalogName` already exists
	 */
	default void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName)
		throws CatalogAlreadyPresentException {
		renameCatalogWithProgress(catalogName, newCatalogName)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Renames existing catalog to a new name. The `newCatalogName` must not clash with any existing catalog name,
	 * otherwise exception is thrown. If you need to rename catalog to a name of existing catalog use
	 * the {@link #replaceCatalog(String, String)} method instead.
	 *
	 * In case exception occurs the original catalog (`catalogName`) is guaranteed to be untouched,
	 * and the `newCatalogName` will not be present.
	 *
	 * @param catalogName    name of the catalog that will be renamed
	 * @param newCatalogName new name of the catalog
	 * @return progress that can be used to track the progress of the operation
	 * @throws CatalogAlreadyPresentException when another catalog with `newCatalogName` already exists
	 */
	@Nonnull
	Progress<CommitVersions> renameCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName)
		throws CatalogAlreadyPresentException;

	/**
	 * Replaces existing catalog of particular with the contents of the another catalog. When this method is
	 * successfully finished, the catalog `catalogNameToBeReplacedWith` will be known under the name of the
	 * `catalogNameToBeReplaced` and the original contents of the `catalogNameToBeReplaced` will be purged entirely.
	 *
	 * In case exception occurs, the original catalog (`catalogNameToBeReplaced`) is guaranteed to be untouched, the
	 * state of `catalogNameToBeReplacedWith` is however unknown and should be treated as damaged.
	 *
	 * At the end of this method the catalog is already replaced and the new name should be used for any further
	 * operations with the catalog.
	 *
	 * @param catalogNameToBeReplacedWith name of the catalog that will become the successor of the original catalog (old name)
	 * @param catalogNameToBeReplaced     name of the catalog that will be replaced and dropped (new name)
	 */
	default void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		replaceCatalogWithProgress(catalogNameToBeReplacedWith, catalogNameToBeReplaced)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Replaces existing catalog of particular with the contents of the another catalog. When this method is
	 * successfully finished, the catalog `catalogNameToBeReplacedWith` will be known under the name of the
	 * `catalogNameToBeReplaced` and the original contents of the `catalogNameToBeReplaced` will be purged entirely.
	 *
	 * In case exception occurs, the original catalog (`catalogNameToBeReplaced`) is guaranteed to be untouched, the
	 * state of `catalogNameToBeReplacedWith` is however unknown and should be treated as damaged.
	 *
	 * @param catalogNameToBeReplacedWith name of the catalog that will become the successor of the original catalog (old name)
	 * @param catalogNameToBeReplaced     name of the catalog that will be replaced and dropped (new name)
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Progress<CommitVersions> replaceCatalogWithProgress(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced);

	/**
	 * Deletes catalog with name `catalogName` along with its contents on disk. At the end of this method the catalog
	 * is guaranteed to be removed from the Evita instance and its contents on disk are also removed.
	 *
	 * @param catalogName name of the removed catalog
	 */
	default void deleteCatalogIfExists(@Nonnull String catalogName) {
		deleteCatalogIfExistsWithProgress(catalogName)
			.ifPresent(
				it -> it.onCompletion()
				        .toCompletableFuture()
				        .join()
			);
	}

	/**
	 * Deletes catalog with name `catalogName` along with its contents on disk.
	 *
	 * @param catalogName name of the removed catalog
	 * @return progress that can be used to track the progress of the operation
	 */
	@Nonnull
	Optional<Progress<Void>> deleteCatalogIfExistsWithProgress(@Nonnull String catalogName);

	/**
	 * Applies top-level engine mutation to the Evita instance. This method is used for applying catalog schema changes
	 * and other engine-level changes that are not related to any particular catalog. The reason why we use mutations
	 * for this is to be able to include those operations to the WAL that is synchronized to replicas.
	 *
	 * Top-level mutations are always applied immediately and cannot be rolled back or wrapped into a larger transaction.
	 * To revert the changes made by this mutation, you have to create a new mutation that performs the opposite operation
	 * (e.g. if you create a new catalog with this mutation, you have to create another mutation that deletes the catalog).
	 */
	@Nonnull
	default <T> Progress<T> applyMutation(@Nonnull EngineMutation<T> engineMutation) {
		return applyMutation(engineMutation, null);
	}

	/**
	 * Applies top-level engine mutation to the Evita instance. This method is used for applying catalog schema changes
	 * and other engine-level changes that are not related to any particular catalog. The reason why we use mutations
	 * for this is to be able to include those operations to the WAL that is synchronized to replicas.
	 *
	 * Top-level mutations are always applied immediately and cannot be rolled back or wrapped into a larger transaction.
	 * To revert the changes made by this mutation, you have to create a new mutation that performs the opposite operation
	 * (e.g. if you create a new catalog with this mutation, you have to create another mutation that deletes the catalog).
	 */
	@Nonnull
	<T> Progress<T> applyMutation(@Nonnull EngineMutation<T> engineMutation, @Nullable IntConsumer intConsumer);

	/**
	 * Executes querying logic in the newly created Evita session. Session is safely closed at the end of this method
	 * and result is returned.
	 *
	 * Query logic is intended to be read-only. For read-write logic use {@link #updateCatalog(String, Function, SessionFlags[]))} or
	 * open a transaction manually in the logic itself.
	 */
	<T> T queryCatalog(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	);

	/**
	 * Executes querying logic in the newly created Evita session. Session is safely closed at the end of this method
	 * and result is returned.
	 *
	 * Query logic is intended to be read-only. For read-write logic use {@link #updateCatalog(String, Consumer, SessionFlags[]))} or
	 * open a transaction manually in the logic itself.
	 */
	void queryCatalog(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> queryLogic,
		@Nullable SessionFlags... flags
	);

	/**
	 * Executes querying logic in the newly created Evita session. Session is safely closed at the end of this method
	 * and result is returned.
	 *
	 * Query logic is intended to be read-only. For read-write logic use {@link #updateCatalog(String, Function, SessionFlags[]))} or
	 * open a transaction manually in the logic itself.
	 *
	 * This is asynchronous variant of {@link #queryCatalog(String, Function, SessionFlags...)} that immediately returns
	 * a future that is completed when the query finishes.
	 *
	 * @return future that is completed when the query finishes
	 */
	@Nonnull
	<T> CompletionStage<T> queryCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	);

	/**
	 * Executes catalog read-write logic in the newly Evita session. When logic finishes without exception, changes are
	 * committed to the index, otherwise changes are roll-backed and no data is affected. Changes made by the updating
	 * logic are visible only within update function. Other threads outside the logic function work with non-changed
	 * data until transaction is committed to the index.
	 *
	 * Current version limitation:
	 * Only single updater can execute in parallel (i.e. updates are expected to be invoked by single thread in serial way).
	 *
	 * @param catalogName name of the catalog
	 * @param updater     application logic that reads and writes data
	 * @param flags       optional flags that can be passed to the session and affect its behavior
	 */
	default <T> T updateCatalog(
		@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> updater,
		@Nullable SessionFlags... flags
	) {
		return updateCatalog(catalogName, updater, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * Overloaded method {@link #updateCatalog(String, Function, SessionFlags[])} that returns no result.
	 *
	 * @see #updateCatalog(String, Function, SessionFlags[])
	 */
	default void updateCatalog(
		@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater, @Nullable SessionFlags... flags) {
		updateCatalog(catalogName, updater, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * Executes catalog read-write logic in the newly Evita session. When logic finishes without exception, changes are
	 * committed to the index, otherwise changes are roll-backed and no data is affected. Changes made by the updating
	 * logic are visible only within update function. Other threads outside the logic function work with non-changed
	 * data until transaction is committed to the index.
	 *
	 * This method blocks indefinitely until the transaction reaches the processing stage defined by
	 * the `commitBehaviour` argument. If you want to have waiting under the control, use
	 * {@link #updateCatalogAsync(String, Function, CommitBehavior, SessionFlags...)} alternative.
	 *
	 * @param catalogName     name of the catalog
	 * @param updater         application logic that reads and writes data
	 * @param commitBehaviour defines when the transaction is considered to be finished
	 * @param flags           optional flags that can be passed to the session and affect its behavior
	 * @return result of the updater function
	 */
	default <T> T updateCatalog(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		try {
			return updateCatalogAsync(catalogName, updater, commitBehaviour, flags).toCompletableFuture().join();
		} catch (EvitaInvalidUsageException | EvitaInternalError e) {
			throw e;
		} catch (Exception e) {
			if (e.getCause() instanceof EvitaInvalidUsageException invalidUsageException) {
				throw invalidUsageException;
			} else if (e.getCause() instanceof EvitaInternalError internalError) {
				throw internalError;
			} else {
				throw new TransactionException("The transaction was rolled back.", e.getCause());
			}
		}
	}

	/**
	 * Executes catalog read-write logic in the newly Evita session. When logic finishes without exception, changes are
	 * committed to the index, otherwise changes are roll-backed and no data is affected. Changes made by the updating
	 * logic are visible only within update function. Other threads outside the logic function work with non-changed
	 * data until transaction is committed to the index.
	 *
	 * Method returns future that is completed when the transaction reaches the processing stage defined by
	 * the `commitBehaviour` argument.
	 *
	 * @param catalogName     name of the catalog
	 * @param updater         application logic that reads and writes data
	 * @param commitBehaviour defines when the transaction is considered to be finished
	 * @param flags           optional flags that can be passed to the session and affect its behavior
	 * @return future that is completed when the transaction reaches the processing stage defined by the `commitBehaviour`
	 */
	@Nonnull
	<T> CompletionStage<T> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	);

	/**
	 * Overloaded method {@link #updateCatalog(String, Function, SessionFlags[])} that returns no result. This method
	 * blocks indefinitely until the transaction reaches the processing stage defined by the `commitBehaviour` argument.
	 * If you want to have waiting under the control, use {@link #updateCatalogAsync(String, Consumer, CommitBehavior, SessionFlags...)}
	 * alternative.
	 *
	 * @see #updateCatalog(String, Function, CommitBehavior, SessionFlags[])
	 */
	default void updateCatalog(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		try {
			updateCatalogAsync(catalogName, updater, commitBehaviour, flags)
				.on(commitBehaviour)
				.toCompletableFuture()
				.join();
		} catch (EvitaInvalidUsageException | EvitaInternalError e) {
			throw e;
		} catch (Exception e) {
			if (e.getCause() instanceof EvitaInvalidUsageException invalidUsageException) {
				throw invalidUsageException;
			} else if (e.getCause() instanceof EvitaInternalError internalError) {
				throw internalError;
			} else {
				throw new TransactionException("The transaction was rolled back.", e.getCause());
			}
		}
	}

	/**
	 * Overloaded method {@link #updateCatalogAsync(String, Function, CommitBehavior, SessionFlags...)} that returns
	 * object containing futures that refer to all transaction processing phases that are completed when the transaction
	 * processing finishes the particular processing stage.
	 *
	 * @param catalogName name of the catalog
	 * @param updater     application logic that reads and writes data
	 * @param flags       optional flags that can be passed to the session and affect its behavior
	 * @return object containing futures that refer to all transaction processing phases
	 * @throws TransactionException when transaction fails
	 * @see #updateCatalog(String, Function, CommitBehavior, SessionFlags[])
	 */
	@Nonnull
	default CommitProgress updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nullable SessionFlags... flags
	) throws TransactionException {
		return updateCatalogAsync(catalogName, updater, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * Overloaded method {@link #updateCatalogAsync(String, Function, CommitBehavior, SessionFlags...)} that returns
	 * object containing futures that refer to all transaction processing phases that are completed when the transaction
	 * processing finishes the particular processing stage.
	 *
	 * @param catalogName     name of the catalog
	 * @param updater         application logic that reads and writes data
	 * @param commitBehaviour defines the commit processing stage at which the session is considered to be finished
	 * @param flags           optional flags that can be passed to the session and affect its behavior
	 * @return object containing futures that refer to all transaction processing phases
	 * @throws TransactionException when transaction fails
	 * @see #updateCatalog(String, Function, CommitBehavior, SessionFlags[])
	 */
	@Nonnull
	CommitProgress updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) throws TransactionException;

	/**
	 * Creates new publisher that emits {@link ChangeSystemCapture}s that match the request.
	 *
	 * @param request defines what events are captured
	 * @return publisher that emits {@link ChangeSystemCapture}s that match the request
	 */
	@Nonnull
	ChangeCapturePublisher<ChangeSystemCapture> registerSystemChangeCapture(
		@Nonnull ChangeSystemCaptureRequest request
	);

	/**
	 * Returns management service that allows to execute various management tasks on the Evita instance and retrieve
	 * global evitaDB information. These operations might require special permissions for execution and are not used
	 * daily and therefore are segregated into special management class.
	 *
	 * @return management service
	 */
	@Nonnull
	EvitaManagementContract management();

}
