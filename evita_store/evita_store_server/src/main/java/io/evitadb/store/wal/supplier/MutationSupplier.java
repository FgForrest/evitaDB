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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal.supplier;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.wal.CatalogWriteAheadLog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.BufferUnderflowException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is used to supply Mutation objects from a Write-Ahead Log (WAL) file in forward order.
 */
public final class MutationSupplier extends AbstractMutationSupplier {

	public MutationSupplier(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		boolean avoidPartiallyFilledBuffer,
		@Nullable Runnable onClose
	) {
		super(
			catalogVersion, catalogName, catalogStoragePath,
			walFileIndex, catalogKryoPool, transactionLocationsCache,
			avoidPartiallyFilledBuffer, onClose
		);
	}

	@Override
	public Mutation get() {
		if (this.transactionMutationRead == 0) {
			this.transactionMutationRead++;
			return this.transactionMutation;
		} else {
			if (this.transactionMutationRead <= this.transactionMutation.getMutationCount()) {
				this.transactionMutationRead++;
				return readMutation();
			} else {
				// advance position to the end of the last transaction
				this.filePosition += this.transactionMutation.getTransactionSpan().recordLength();
				try {
					// check the entire transaction was written
					final long currentFileLength = this.walFile.length();
					if (currentFileLength <= this.filePosition + CatalogWriteAheadLog.WAL_TAIL_LENGTH) {
						// we've reached EOF
						if (!moveToNextWalFile(1)) {
							// we've reached EOF and there is no next WAL file
							return null;
						}
					}
					// read content length and next leading transaction mutation
					this.transactionMutation = readAndRecordTransactionMutation();

					// check the entire transaction was written and there are another data that would fully fill the buffer of the observable input
					// Note: there must be some bug in our observable input implementation that is revealed by filling the buffer incompletely with
					// the data from the stream - example: the buffer is 16k long, but the next transaction takes only 2k and then file ends
					// the observable input reads the transaction mutation, but in the meantime another transaction with 4k size has been written
					// the 4k transaction is then failed to be read from the observable input, because the internal pointers are probably somehow misaligned

					// nevertheless, the condition is here to prevent the observable input from reading the next transaction mutation
					// if there is not enough data already written is actually ok for real-world scenarios - we want to fast play transactions
					// in the WAL only when there is a lot of them to be read and processed
					final long requiredLength = this.filePosition + this.transactionMutation.getTransactionSpan().recordLength() +
						(avoidPartiallyFilledBuffer ? this.observableInput.getBuffer().length : 0);
					if (currentFileLength >= requiredLength) {
						this.transactionMutationRead = 1;
						// return the transaction mutation
						return this.transactionMutation;
					} else {
						// we've reached EOF or the tx mutation hasn't been yet completely written
						return null;
					}
				} catch (CorruptedRecordException | BufferUnderflowException | KryoException ex) {
					// we've reached EOF or the tx mutation hasn't been yet completely written
					return null;
				}
			}
		}
	}

	/**
	 * Reads and records a mutation from the given observable input.
	 *
	 * @return The mutation read from the input stream. Returns null if no mutation is found.
	 */
	@Nullable
	private Mutation readMutation() {
		final StorageRecord<Mutation> storageRecord = StorageRecord.read(
			this.observableInput, (stream, length) -> (Mutation) kryo.readClassAndObject(stream)
		);
		return storageRecord.payload();
	}

}
