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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.TransactionContract;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityMutation;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * The DefaultIsolatedWalService class is a default implementation of the IsolatedWalPersistenceService interface.
 * It provides methods for writing mutations to the Write-Ahead Log (WAL), retrieving metadata about the mutations,
 * obtaining a reference to the WAL data, and closing the service.
 *
 * There is always single instance per {@link TransactionContract} instance identified by same {@link UUID}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DefaultIsolatedWalService implements IsolatedWalPersistenceService {
	/**
	 * The transactionId is the unique identifier for the transaction.
	 */
	@Nonnull @Getter private final UUID transactionId;
	/**
	 * The writeKryo is the Kryo instance used for serializing WAL records = mutations.
	 */
	@Nonnull private final Kryo writeKryo;
	/**
	 * The writeHandle is the handle to the WAL file.
	 */
	@Nonnull private final WriteOnlyOffHeapWithFileBackupHandle writeHandle;
	/**
	 * The mutationCount is the number of mutations written to this isolated WAL instance.
	 */
	@Getter private int mutationCount;
	/**
	 * The mutationSizeInBytes is the total size of the mutations written to this isolated WAL instance.
	 */
	@Getter private long mutationSizeInBytes;

	public DefaultIsolatedWalService(
		@Nonnull UUID transactionId,
		@Nonnull Kryo writeKryo,
		@Nonnull WriteOnlyOffHeapWithFileBackupHandle writeHandle
	) {
		this.transactionId = transactionId;
		this.writeKryo = writeKryo;
		this.writeHandle = writeHandle;
	}

	@Override
	public void write(long catalogVersion, @Nonnull Mutation mutation) {
		this.mutationSizeInBytes += this.writeHandle.checkAndExecute(
			"write mutation",
			() -> { },
			output -> {
				final Mutation mutationToWrite = mutation instanceof ServerEntityMutation sem ?
					sem.getDelegate() : mutation;
				final StorageRecord<Mutation> record = new StorageRecord<>(
					output, catalogVersion, false,
					theOutput -> {
						this.writeKryo.writeClassAndObject(output, mutationToWrite);
						return mutationToWrite;
					}
				);
				return record.fileLocation().recordLength();
			}
		);
		this.mutationCount++;
	}

	@Nonnull
	@Override
	public OffHeapWithFileBackupReference getWalReference() {
		return this.writeHandle.toReadOffHeapWithFileBackupReference();
	}

	@Override
	public void close() {
		this.writeHandle.close();
	}

}
