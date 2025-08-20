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

package io.evitadb.store.wal.supplier;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * This class is used to supply Mutation objects from a Write-Ahead Log (WAL) file in reverse order.
 */
public final class ReverseMutationSupplier<T extends Mutation> extends AbstractMutationSupplier<T> {
	private int mutationIndex;
	@Nullable private FileLocation[] mappedPositions;

	public ReverseMutationSupplier(
		long catalogVersion,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageOptions storageOptions,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		@Nullable Runnable onClose
	) {
		super(
			catalogVersion, walFileNameProvider, catalogStoragePath, storageOptions,
			walFileIndex, catalogKryoPool, transactionLocationsCache,
			false, onClose
		);
		this.mutationIndex = this.transactionMutation == null ?
			0: this.transactionMutation.getMutationCount();
	}

	@Nullable
	@Override
	public T get() {
		if (this.transactionMutation == null) {
			return null;
		} else {
			final FileLocation[] mappedPositions = getMappedPositions();
			if (this.mutationIndex == this.transactionMutation.getMutationCount()) {
				this.mutationIndex--;
				//noinspection unchecked
				return (T) this.transactionMutation;
			} else if (this.mutationIndex >= 0) {
				Assert.isPremiseValid(
					this.observableInput != null,
					"Observable input stream is not available!"
				);
				// transaction is fully mapped - we could read it backwards
				final FileLocation currentLocation = mappedPositions[this.mutationIndex--];
				final StorageRecord<Mutation> storageRecord = StorageRecord.read(
					this.observableInput, currentLocation,
					(stream, length, control) -> (Mutation) this.kryo.readClassAndObject(stream)
				);
				//noinspection unchecked
				return (T) storageRecord.payload();
			} else {
				this.transactionMutation = findPreviousTransactionMutation(this.transactionMutation);
				this.mappedPositions = null;
				this.mutationIndex = this.transactionMutation == null ? 0 : this.transactionMutation.getMutationCount();
				return get();
			}
		}
	}

	/**
	 * Retrieves the mapped positions of mutations in the transaction.
	 *
	 * @return An array of FileLocation objects representing the mapped positions.
	 */
	@Nonnull
	private FileLocation[] getMappedPositions() {
		if (this.mappedPositions == null) {
			Assert.isPremiseValid(
				this.observableInput != null,
				"Observable input stream is not available!"
			);
			Assert.isPremiseValid(
				this.transactionMutation != null,
				"Transaction mutation is not available!"
			);
			final FileLocation transactionMutationLocation = StorageRecord.readFileLocation(
				this.observableInput,
				this.transactionMutation.getTransactionSpan().startingPosition() + 4
			);
			FileLocation[] newlyMappedPositions = new FileLocation[this.transactionMutation.getMutationCount()];
			long startingPosition = transactionMutationLocation.startingPosition() + transactionMutationLocation.recordLength();
			for (int i = 0; i < this.transactionMutation.getMutationCount(); i++) {
				final FileLocation mutationLocation = StorageRecord.readFileLocation(
					this.observableInput,
					startingPosition
				);
				newlyMappedPositions[i] = mutationLocation;
				startingPosition = mutationLocation.startingPosition() + mutationLocation.recordLength();
			}
			final long finalStartingPosition = startingPosition - this.transactionMutation.getTransactionSpan().startingPosition();
			Assert.isPremiseValid(
				finalStartingPosition == this.transactionMutation.getTransactionSpan().recordLength(),
				() -> new WriteAheadLogCorruptedException(
					"Transaction mutation span is not fully mapped!",
					"Transaction mutation span is not fully mapped (" + finalStartingPosition + " vs. " + this.transactionMutation.getTransactionSpan().recordLength() + ")!"
				)
			);
			this.mappedPositions = newlyMappedPositions;
		}
		return this.mappedPositions;
	}

	/**
	 * Finds the previous transaction mutation with the given current transaction mutation.
	 *
	 * @param currentTxMutation The current transaction mutation with location.
	 * @return The previous transaction mutation with location, or null if not found.
	 */
	@Nullable
	private TransactionMutationWithLocation findPreviousTransactionMutation(
		@Nonnull TransactionMutationWithLocation currentTxMutation
	) {
		if (currentTxMutation.getTransactionSpan().startingPosition() == 0) {
			// we've reached the beginning of the file
			if (!moveToNextWalFile(-1)) {
				// we've reached the beginning of the file and there is no previous WAL file
				return null;
			}
		}
		final long previousCatalogVersion = currentTxMutation.getVersion() - 1;
		this.filePosition = this.transactionLocationsCache.computeIfAbsent(
			this.walFileIndex,
			(index) -> new TransactionLocations()
		).findNearestLocation(previousCatalogVersion);

		Assert.isPremiseValid(
			this.observableInput != null,
			"Observable input stream is not available!"
		);

		// move forward, until we reach EOL or current tx mutation
		this.observableInput.seekWithUnknownLength(this.filePosition);

		final long walFileLength = this.walFile.length();
		Optional<TransactionMutationWithLocation> examinedTxMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
		// move cursor to the end of the lead mutation
		while (
			examinedTxMutation
				.map(
					it -> it.getVersion() < previousCatalogVersion &&
						this.filePosition + it.getTransactionSpan().recordLength() + AbstractMutationLog.WAL_TAIL_LENGTH < walFileLength)
				.orElse(false)
		) {
			// move cursor to the next transaction mutation
			final FileLocation transactionSpan = examinedTxMutation.get().getTransactionSpan();
			this.filePosition += transactionSpan.recordLength();
			this.observableInput.seekWithUnknownLength(this.filePosition);
			// read content length and leading transaction mutation
			examinedTxMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
			// if the file is shorter than the expected size of the transaction mutation, we've reached EOF
			if (walFileLength < this.filePosition + transactionSpan.recordLength() + AbstractMutationLog.WAL_TAIL_LENGTH) {
				break;
			}
		}
		return examinedTxMutation
			.filter(it -> it.getVersion() == previousCatalogVersion)
			.orElse(null);
	}

}
