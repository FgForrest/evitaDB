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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.transaction.WalCacheSizeChangedEvent;
import io.evitadb.core.metric.event.transaction.WalRotationEvent;
import io.evitadb.core.metric.event.transaction.WalStatisticsEvent;
import io.evitadb.store.wal.requestResponse.CatalogTransactionChangesContainer;
import io.evitadb.store.wal.requestResponse.CatalogTransactionChangesContainer.CatalogTransactionChanges;
import io.evitadb.store.wal.requestResponse.EntityCollectionChanges;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * CatalogWriteAheadLog is a class for managing a Write-Ahead Log (WAL) file for a catalog.
 * It allows appending transaction mutations to the WAL file. The class also provides a method to check and truncate
 * incomplete WAL files at the time it's created.
 *
 * The WAL file is used for durability, ensuring that changes made to the catalog are durable
 * and can be recovered in the case of crashes or failures.
 *
 * The class is not thread-safe and should be used from a single thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public class CatalogWriteAheadLog extends AbstractMutationLog<CatalogBoundMutation> {
	/**
	 * The name of the catalog.
	 */
	private final String catalogName;
	/**
	 * This lambda allows trimming the bootstrap file to the given date.
	 */
	protected final LongConsumer bootstrapFileTrimmer;
	/**
	 * Callback to be called when the WAL file is purged.
	 */
	protected final WalPurgeCallback onWalPurgeCallback;

	/**
	 * Creates a new instance of CatalogTransactionChanges based on the given parameters.
	 *
	 * @param txMutation           the transaction mutation.
	 * @param catalogSchemaChanges the number of catalog schema changes.
	 * @param aggregations         the map of entity collection changes.
	 * @return a new instance of CatalogTransactionChanges.
	 */
	@Nonnull
	private static CatalogTransactionChanges createTransactionChanges(
		@Nonnull TransactionMutation txMutation,
		int catalogSchemaChanges,
		@Nonnull Map<String, EntityCollectionChangesTriple> aggregations
	) {
		return new CatalogTransactionChanges(
			txMutation.getVersion(),
			txMutation.getCommitTimestamp(),
			catalogSchemaChanges,
			txMutation.getMutationCount(),
			txMutation.getWalSizeInBytes(),
			aggregations.entrySet().stream()
			            .map(
				            it -> new EntityCollectionChanges(
					            it.getKey(),
					            it.getValue().getSchemaChanges(),
					            it.getValue().getUpserted(),
					            it.getValue().getRemoved()
				            )
			            )
			            .sorted(Comparator.comparing(EntityCollectionChanges::entityName))
			            .toArray(EntityCollectionChanges[]::new)
		);
	}

	public CatalogWriteAheadLog(
		long catalogVersion,
		@Nullable String catalogName,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull Pool<Kryo> kryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer bootstrapFileTrimmer,
		@Nonnull WalPurgeCallback onWalPurgeCallback
	) {
		super(
			catalogVersion,
			walFileNameProvider,
			storageFolder,
			kryoPool,
			storageOptions,
			transactionOptions,
			scheduler
		);
		this.catalogName = catalogName;
		this.bootstrapFileTrimmer = bootstrapFileTrimmer;
		this.onWalPurgeCallback = onWalPurgeCallback;
	}

	/**
	 * Constructor for internal use only. It is used to create a new WAL file with the given parameters.
	 */
	public CatalogWriteAheadLog(
		long catalogVersion,
		@Nullable String catalogName,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull Pool<Kryo> kryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		int walFileIndex
	) {
		super(
			catalogVersion,
			walFileNameProvider,
			storageFolder,
			kryoPool,
			storageOptions,
			transactionOptions,
			scheduler,
			walFileIndex
		);
		this.catalogName = catalogName;
		this.bootstrapFileTrimmer = null;
		this.onWalPurgeCallback = null;
	}

	/**
	 * Calculates descriptor for particular version in history.
	 *
	 * @param version              the catalog version to describe
	 * @param previousKnownVersion the previous known catalog version (delimits transactions incorporated in
	 *                             previous version of the catalog), -1 if there is no known previous version
	 * @param introducedAt         the time when the version was introduced
	 * @return the descriptor for the version in history or NULL if the version is not present in the WAL
	 */
	@Override
	@Nullable
	public WriteAheadLogVersionDescriptor getWriteAheadLogVersionDescriptor(
		long version,
		long previousKnownVersion,
		@Nonnull OffsetDateTime introducedAt
	) {
		try (
			final MutationSupplier<?> supplier = createSupplier(
				previousKnownVersion + 1, null
			)
		) {
			TransactionMutation txMutation = (TransactionMutation) supplier.get();
			if (txMutation == null) {
				return null;
			} else {
				final List<CatalogTransactionChanges> txChanges = new ArrayList<>(Math.toIntExact(version - previousKnownVersion));
				final Map<String, EntityCollectionChangesTriple> aggregations = CollectionUtils.createHashMap(16);
				int catalogSchemaChanges = 0;
				while (txMutation != null) {
					final Mutation nextMutation = supplier.get();
					if (nextMutation instanceof TransactionMutation anotherTxMutation) {
						txChanges.add(
							createTransactionChanges(txMutation, catalogSchemaChanges, aggregations)
						);
						if (anotherTxMutation.getVersion() >= version + 1) {
							txMutation = null;
						} else {
							txMutation = anotherTxMutation;
							aggregations.clear();
							catalogSchemaChanges = 0;
						}
					} else if (nextMutation == null) {
						txChanges.add(
							createTransactionChanges(txMutation, catalogSchemaChanges, aggregations)
						);
						txMutation = null;
					} else {
						if (nextMutation instanceof ModifyEntitySchemaMutation schemaMutation) {
							aggregations
								.computeIfAbsent(schemaMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordSchemaChange();
						} else if (nextMutation instanceof LocalCatalogSchemaMutation) {
							catalogSchemaChanges++;
						} else if (nextMutation instanceof EntityUpsertMutation entityMutation) {
							aggregations
								.computeIfAbsent(entityMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordUpsert();
						} else if (nextMutation instanceof EntityRemoveMutation entityMutation) {
							aggregations
								.computeIfAbsent(entityMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordRemoval();
						} else {
							throw new InvalidMutationException(
								"Unexpected mutation type: " + nextMutation.getClass().getName(),
								"Unexpected mutation type."
							);
						}
					}
				}
				return new WriteAheadLogVersionDescriptor(
					version,
					introducedAt,
					new CatalogTransactionChangesContainer(
						txChanges.toArray(CatalogTransactionChanges[]::new)
					)
				);
			}
		}
	}

	@Override
	protected void updateFirstVersionKept(long firstVersionToBeKept) {
		// first trim the bootstrap record file
		this.bootstrapFileTrimmer.accept(firstVersionToBeKept);
		// call the listener to remove the obsolete files
		if (firstVersionToBeKept > -1) {
			this.onWalPurgeCallback.purgeFilesUpTo(firstVersionToBeKept);
		}
	}

	@Override
	protected @Nonnull DelayedAsyncTask createDelayedAsyncTask(
		@Nonnull String name,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier lambda,
		long intervalInMillis
	) {
		return new DelayedAsyncTask(
			this.catalogName,
			name,
			scheduler,
			lambda,
			intervalInMillis, TimeUnit.MILLISECONDS
		);
	}

	@Nonnull
	@Override
	protected DataFileCompactEvent createDataFileCompactEvent() {
		return new DataFileCompactEvent(
			this.catalogName,
			FileType.WAL,
			FileType.WAL.name()
		);
	}

	@Override
	protected void emitWalStatisticsEvent(@Nonnull OffsetDateTime commitTimestamp) {
		new WalStatisticsEvent(this.catalogName, commitTimestamp).commit();
	}

	@Nonnull
	@Override
	protected WalRotationEvent createWalRotationEvent() {
		return new WalRotationEvent(this.catalogName);
	}

	@Override
	protected void emitCacheSizeEvent(int cacheSize) {
		// emit the event
		new WalCacheSizeChangedEvent(
			this.catalogName,
			cacheSize
		).commit();
	}

	/**
	 * Represents a triplet of recorded changes in an entity collection.
	 */
	@Getter
	private static class EntityCollectionChangesTriple {
		/**
		 * The number of schema mutations.
		 */
		private int schemaChanges;
		/**
		 * The number of upserted entities.
		 */
		private int upserted;
		/**
		 * The number of removed entities.
		 */
		private int removed;

		/**
		 * Increments the count of upserted entities in the entity collection changes.
		 */
		public void recordUpsert() {
			this.upserted++;
		}

		/**
		 * Records the removal of an entity in the entity collection changes.
		 */
		public void recordRemoval() {
			this.removed++;
		}

		/**
		 * Increments the count of schema changes in the entity collection changes.
		 */
		public void recordSchemaChange() {
			this.schemaChanges++;
		}

	}

}
