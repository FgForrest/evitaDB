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

import com.carrotsearch.hppc.LongSet;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.transaction.WalCacheSizeChangedEvent;
import io.evitadb.core.metric.event.transaction.WalRotationEvent;
import io.evitadb.core.metric.event.transaction.WalStatisticsEvent;
import io.evitadb.store.spi.model.wal.EngineTransactionChanges;
import io.evitadb.store.wal.supplier.MutationSupplier;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
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
public class EngineMutationLog extends AbstractMutationLog<EngineMutation<?>> {

	/**
	 * Creates a new instance of EngineTransactionChanges based on the given parameters.
	 *
	 * @param txMutation           the transaction mutation.
	 * @param changes 			   the array of change descriptions in the transaction.
	 * @return a new instance of CatalogTransactionChanges.
	 */
	@Nonnull
	private static EngineTransactionChanges createTransactionChanges(
		@Nonnull TransactionMutation txMutation,
		@Nonnull String[] changes
	) {
		return new EngineTransactionChanges(
			txMutation.getVersion(),
			txMutation.getCommitTimestamp(),
			txMutation.getMutationCount(),
			txMutation.getWalSizeInBytes(),
			changes
		);
	}

	public EngineMutationLog(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull Pool<Kryo> kryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		super(
			version,
			walFileNameProvider,
			storageFolder,
			kryoPool,
			storageOptions,
			transactionOptions,
			scheduler
		);
	}

	@Override
	@Nonnull
	public List<WriteAheadLogVersionDescriptor> getWriteAheadLogVersionDescriptor(
		@Nonnull LongSet lookedUpVersions,
		@Nonnull MaterializedVersionBlock materializedVersionBlock
	) {
		try (
			final MutationSupplier<?> supplier = createSupplier(
				materializedVersionBlock.startVersion(), null
			)
		) {
			TransactionMutation txMutation = (TransactionMutation) supplier.get();
			if (txMutation == null) {
				return Collections.emptyList();
			} else {
				final List<String> descriptions = new ArrayList<>(8);
				Mutation nextMutation;
				final List<WriteAheadLogVersionDescriptor> result = new LinkedList<>();
				do {
					nextMutation = supplier.get();
					if (nextMutation instanceof TransactionMutation anotherTxMutation) {
						if (lookedUpVersions.contains(txMutation.getVersion())) {
							result.add(
								new WriteAheadLogVersionDescriptor(
									txMutation.getVersion(),
									txMutation.getTransactionId(),
									materializedVersionBlock.introducedAt(),
									createTransactionChanges(txMutation, descriptions.toArray(String[]::new)),
									materializedVersionBlock.startVersion() == txMutation.getVersion()
								)
							);
						}
						txMutation = anotherTxMutation;
						descriptions.clear();
						// materialized block was completely processed
						if (anotherTxMutation.getVersion() > materializedVersionBlock.endVersion()) {
							return result;
						}
					} else if (nextMutation != null && lookedUpVersions.contains(txMutation.getVersion())) {
						descriptions.add(nextMutation.toString());
					}
				} while (nextMutation != null);

				// if we reach here, we have to process also the last transaction
				if (lookedUpVersions.contains(txMutation.getVersion())) {
					result.add(
						new WriteAheadLogVersionDescriptor(
							txMutation.getVersion(),
							txMutation.getTransactionId(),
							materializedVersionBlock.introducedAt(),
							createTransactionChanges(txMutation, descriptions.toArray(String[]::new)),
							materializedVersionBlock.startVersion() == txMutation.getVersion()
						)
					);
				}

				// return collected transactions
				return result;
			}
		}
	}

	@Override
	protected void updateFirstVersionKept(long firstVersionToBeKept) {
		// do nothing, this is not used in engine WAL
	}

	@Override
	protected void emitWalStatisticsEvent(@Nonnull OffsetDateTime commitTimestamp) {
		new WalStatisticsEvent(commitTimestamp).commit();
	}

	@Nonnull
	@Override
	protected WalRotationEvent createWalRotationEvent() {
		return new WalRotationEvent();
	}

	@Override
	protected void emitCacheSizeEvent(int cacheSize) {
		// emit the event
		new WalCacheSizeChangedEvent(cacheSize).commit();
	}

	@Nonnull
	@Override
	protected DelayedAsyncTask createDelayedAsyncTask(
		@Nonnull String name,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier lambda,
		long intervalInMillis
	) {
		return new DelayedAsyncTask(
			null,
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
			FileType.WAL,
			FileType.WAL.name()
		);
	}

}
