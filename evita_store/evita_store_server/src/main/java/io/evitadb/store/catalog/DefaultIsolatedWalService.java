/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DefaultIsolatedWalService implements IsolatedWalPersistenceService {
	@Nonnull @Getter private final UUID transactionId;
	@Nonnull private final Kryo writeKryo;
	@Nonnull private final WriteOnlyOffHeapWithFileBackupHandle writeHandle;
	@Getter private int mutationCount;
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
		mutationSizeInBytes += writeHandle.checkAndExecute(
			"write mutation",
			() -> { },
			output -> {
				final StorageRecord<Mutation> record = new StorageRecord<>(
					output, catalogVersion, false,
					theOutput -> {
						writeKryo.writeClassAndObject(output, mutation);
						return mutation;
					}
				);
				return record.fileLocation().recordLength();
			}
		);
		mutationCount++;
	}

	@Nonnull
	@Override
	public OffHeapWithFileBackupReference getWalReference() {
		return writeHandle.toReadOffHeapWithFileBackupReference();
	}

	@Override
	public void close() {
		writeHandle.close();
	}

}
