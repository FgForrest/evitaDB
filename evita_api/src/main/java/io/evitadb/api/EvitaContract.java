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
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
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
 * Primary entry point for interacting with an evitaDB instance. This interface provides catalog management,
 * session lifecycle control, and global instance operations. EvitaContract represents a running evitaDB server
 * instance that can manage multiple isolated {@link CatalogContract} instances.
 *
 * **evitaDB Purpose and Design Philosophy**
 *
 * evitaDB is a specialized in-memory database optimized for e-commerce use cases, designed to serve as a fast
 * secondary lookup and search index for application frontends. Key characteristics:
 *
 * - **Performance Focus**: 10x better latency than traditional SQL/NoSQL solutions for e-commerce queries
 * - **Secondary Index**: Not designed for primary data storage; data can be rebuilt from source systems
 * - **No ACID Guarantees**: Optimized for read performance over strict consistency
 * - **Disposable Nature**: Catalog contents can be dropped and reconstructed without data loss
 *
 * **Multi-Catalog Architecture**
 *
 * A single evitaDB instance manages multiple catalogs ({@link CatalogContract}), each representing:
 * - An isolated data container (analogous to a database in traditional RDBMS)
 * - Independent schema and entity collections
 * - Separate session lifecycle and transaction boundaries
 * - Typical use: one catalog per e-commerce store, tenant, or geographical market
 *
 * **Session Management**
 *
 * Sessions ({@link EvitaSessionContract}) are the primary means of interacting with catalog data:
 * - Created via {@link #createSession(SessionTraits)} with catalog binding
 * - Bound to a single catalog for their entire lifetime
 * - Support read-only or read-write access modes
 * - Must be explicitly closed or terminated to release resources
 * - Convenience methods: {@link #createReadOnlySession(String)}, {@link #createReadWriteSession(String)}
 *
 * **Catalog Lifecycle**
 *
 * - **Creation**: {@link #defineCatalog(String)} creates or returns existing catalog schema
 * - **State Management**: Catalogs transition through states (WARMING_UP, ALIVE, INACTIVE)
 * - **Activation/Deactivation**: Load/unload catalogs from memory while preserving disk data
 * - **Backup/Restore**: Point-in-time and full backups via management contract
 * - **Deletion**: {@link #deleteCatalogIfExists(String)} removes catalog and all data
 *
 * **Transaction Patterns**
 *
 * - **Read-only queries**: {@link #queryCatalog(String, Function, SessionFlags...)}
 * - **Read-write updates**: {@link #updateCatalog(String, Function, SessionFlags...)}
 * - **Asynchronous updates**: {@link #updateCatalogAsync} variants for non-blocking operations
 * - **Commit behavior control**: {@link CommitBehavior} defines when transactions are considered complete
 *
 * **Instance Lifecycle**
 *
 * - Start: Typically created via `Evita.create(EvitaConfiguration)`
 * - Active state: {@link #isActive()} returns true
 * - Shutdown: {@link #close()} terminates all catalogs and releases resources
 * - Post-shutdown: All operations throw {@link InstanceTerminatedException}
 *
 * **Management Operations**
 *
 * Access privileged operations via {@link #management()}:
 * - Global catalog statistics
 * - Backup and restore
 * - Task monitoring
 * - System health checks
 *
 * **Thread-Safety**
 *
 * EvitaContract implementations are fully thread-safe. Multiple threads can:
 * - Create sessions concurrently
 * - Query and update different catalogs simultaneously
 * - Invoke management operations in parallel
 *
 * **Resource Cleanup**
 *
 * This interface extends {@link AutoCloseable}, enabling try-with-resources usage:
 * ```
 * try (Evita evita = Evita.create(config)) {
 * // work with evitaDB
 * } // automatically closes and releases resources
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public interface EvitaContract extends AutoCloseable {

	/**
	 * Indicates whether this evitaDB instance is active and ready to serve requests. Returns false after {@link #close()}
	 * has been called or if the instance failed to initialize properly.
	 *
	 * **Use Cases**
	 *
	 * - Health check endpoints to verify instance availability
	 * - Conditional logic before attempting operations
	 * - Graceful degradation in shutdown scenarios
	 *
	 * **Post-Shutdown Behavior**
	 *
	 * Once this method returns false, all subsequent operations will throw {@link InstanceTerminatedException}.
	 * The instance cannot be reactivated; a new instance must be created.
	 *
	 * @return true if instance is active and accepting requests, false if terminated
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
	 * Returns the names of all catalogs known to this evitaDB instance, regardless of their current state
	 * (ALIVE, INACTIVE, CORRUPTED, etc.).
	 *
	 * **Catalog States**
	 *
	 * Returned catalogs may be in various states:
	 * - Active catalogs loaded in memory ({@link CatalogState#ALIVE}, {@link CatalogState#WARMING_UP})
	 * - Inactive catalogs present on disk ({@link CatalogState#INACTIVE})
	 * - Corrupted catalogs that failed to load ({@link CatalogState#CORRUPTED})
	 *
	 * **Use Cases**
	 *
	 * - Enumerating all available catalogs for management dashboards
	 * - Discovering catalog names before creating sessions
	 * - Validating catalog existence before activation or deletion
	 *
	 * **Thread-Safety**
	 *
	 * Returned set is a snapshot at call time. Concurrent catalog creation/deletion operations may make it stale.
	 *
	 * @return immutable set of catalog names, never null but may be empty
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
	 * @return progress that can be used to track the progress of the operation
	 * @see CatalogState
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
	 * @param catalogName    name of the source catalog to duplicate
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
	 * @param catalogName    name of the source catalog to duplicate
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
	Progress<CommitVersions> replaceCatalogWithProgress(
		@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced);

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

	/**
	 * Returns implementation of the proxy factory that is used to wrap the returned {@link SealedEntity} into custom
	 * Java types.
	 */
	@Nonnull
	ProxyFactory getProxyFactory();

}
