/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

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
	Optional<EvitaSessionContract> getSessionById(@Nonnull String catalogName, @Nonnull UUID uuid);

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
	 * Creates new catalog of particular name if it doesn't exist. The schema of the catalog (should it was created or
	 * not) is returned to the response.
	 *
	 * @param catalogName name of the catalog
	 * @return new instance of {@link SealedCatalogSchema} connected with the catalog
	 */
	@Nonnull
	CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName);

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
	 * @throws CatalogAlreadyPresentException when another catalog with `newCatalogName` already exists
	 */
	void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName)
		throws CatalogAlreadyPresentException;

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
	 */
	void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced);

	/**
	 * Deletes catalog with name `catalogName` along with its contents on disk.
	 *
	 * @param catalogName name of the removed catalog
	 * @return true if catalog was found in Evita and its contents were successfully removed
	 */
	boolean deleteCatalogIfExists(@Nonnull String catalogName);

	/**
	 * Applies catalog mutation affecting entire catalog.
	 * The reason why we use mutations for this is to be able to include those operations to the WAL that is
	 * synchronized to replicas.
	 */
	void update(@Nonnull TopLevelCatalogSchemaMutation... catalogMutations);

	/**
	 * Executes querying logic in the newly created Evita session. Session is safely closed at the end of this method
	 * and result is returned.
	 *
	 * Query logic is intended to be read-only. For read-write logic use {@link #updateCatalog(String, Function, SessionFlags[]))} or
	 * open a transaction manually in the logic itself.
	 */
	<T> T queryCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags);

	/**
	 * Executes querying logic in the newly created Evita session. Session is safely closed at the end of this method
	 * and result is returned.
	 *
	 * Query logic is intended to be read-only. For read-write logic use {@link #updateCatalog(String, Consumer, SessionFlags[]))} or
	 * open a transaction manually in the logic itself.
	 */
	void queryCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic, @Nullable SessionFlags... flags);

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
	default <T> T updateCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> updater, @Nullable SessionFlags... flags) {
		return updateCatalog(catalogName, updater, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * Overloaded method {@link #updateCatalog(String, Function, SessionFlags[])} that returns no result.
	 *
	 * @see #updateCatalog(String, Function, SessionFlags[])
	 */
	default void updateCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater, @Nullable SessionFlags... flags) {
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
			return updateCatalogAsync(catalogName, updater, commitBehaviour, flags).join();
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
	<T> CompletableFuture<T> updateCatalogAsync(
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
			updateCatalogAsync(catalogName, updater, commitBehaviour, flags).join();
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
	 * future that is completed when the transaction reaches the processing stage defined by the `commitBehaviour`
	 * argument.
	 *
	 * @return future that is completed when the transaction reaches the processing stage defined by the `commitBehaviour`
	 * argument. Long represents catalog version where the transaction changes will be visible.
	 * @throws TransactionException when transaction fails
	 * @see #updateCatalog(String, Function, CommitBehavior, SessionFlags[])
	 */
	CompletableFuture<Long> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	)
		throws TransactionException;

	/**
	 * Retrieves the current system status of the EvitaDB server.
	 *
	 * @return the system status of the EvitaDB server
	 */
	@Nonnull
	SystemStatus getSystemStatus();

}
