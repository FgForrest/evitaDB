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
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Abstract ancestor for both {@link MutationSupplier} and {@link ReverseMutationSupplier} to reuse common logic and
 * state fields.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract sealed class AbstractMutationSupplier<T extends Mutation> implements Supplier<T>, Closeable
	permits MutationSupplier, ReverseMutationSupplier {
	/**
	 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
	 */
	protected final Pool<Kryo> catalogKryoPool;
	/**
	 * The Kryo object for serializing {@link TransactionMutation} obtained in constructor from the Kryo pool.
	 */
	protected final Kryo kryo;
	/**
	 * The function that provides the name of the WAL file based on the index.
	 */
	protected final IntFunction<String> walFileNameProvider;
	/**
	 * Path to the storage folder where the WAL file is stored.
	 */
	protected final Path storageFolder;
	/**
	 * The storage options from evita configuration.
	 */
	protected final StorageOptions storageOptions;
	/**
	 * The cache of already scanned WAL files. The locations might not be complete, but they will be always cover
	 * the start of the particular WAL file, but they may be later appended with new records that are not yet scanned
	 * or gradually added to the working WAL file. The index key in this map is the {@link #walFileIndex} of the WAL file.
	 */
	protected final ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache;
	/**
	 * This flag is used to prevent the observable input from reading the next transaction mutation if there is not
	 * enough data already written to fully fill the buffer of the observable input.
	 */
	protected final boolean avoidPartiallyFilledBuffer;
	/**
	 * The Write-Ahead Log (WAL) file reference
	 */
	protected File walFile;
	/**
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	protected int walFileIndex;
	/**
	 * The ObservableInput for reading {@link TransactionMutation} from the WAL file.
	 */
	@Nullable protected ObservableInput<RandomAccessFileInputStream> observableInput;
	/**
	 * The current {@link TransactionMutation} being read from the WAL file.
	 */
	@Nullable protected TransactionMutationWithLocation transactionMutation;
	/**
	 * The current position in the WAL file.
	 */
	protected long filePosition;
	/**
	 * The number of mutations read from the current transaction.
	 */
	protected int transactionMutationRead;
	/**
	 * The number of transactions read from the WAL file.
	 */
	@Getter protected int transactionsRead;
	/**
	 * Callback to be executed when the supplier is closed.
	 */
	private final Runnable onClose;

	public AbstractMutationSupplier(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull StorageOptions storageOptions,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		boolean avoidPartiallyFilledBuffer,
		@Nullable Runnable onClose
	) {
		this.walFile = storageFolder.resolve(walFileNameProvider.apply(walFileIndex)).toFile();
		this.walFileIndex = walFileIndex;
		this.walFileNameProvider = walFileNameProvider;
		this.storageFolder = storageFolder;
		this.storageOptions = storageOptions;
		this.transactionLocationsCache = transactionLocationsCache;
		this.avoidPartiallyFilledBuffer = avoidPartiallyFilledBuffer;
		this.onClose = onClose;
		if (!this.walFile.exists() || this.walFile.length() < 4) {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = null;
			this.observableInput = null;
			this.transactionMutation = null;
		} else {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = catalogKryoPool.obtain();
			try {
				this.observableInput = new ObservableInput<>(
					new RandomAccessFileInputStream(
						new RandomAccessFile(this.walFile, "r"),
						true
					)
				);
				if (this.storageOptions.computeCRC32C()) {
					this.observableInput.computeCRC32();
				}
				if (this.storageOptions.compress()) {
					this.observableInput.compress();
				}
				// fast path if the record is found in cache
				Optional<TransactionMutationWithLocation> initialTransactionMutation;
				do {
					this.filePosition = ofNullable(this.transactionLocationsCache.get(this.walFileIndex))
						.map(it -> it.findNearestLocation(version))
						.orElse(0L);

					this.observableInput.seekWithUnknownLength(this.filePosition);

					final long walFileLength = this.walFile.length();
					initialTransactionMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
					// move cursor to the end of the lead mutation
					while (initialTransactionMutation.map(it -> it.getVersion() < version).orElse(false)) {
						// move cursor to the next transaction mutation
						this.filePosition += initialTransactionMutation.get().getTransactionSpan().recordLength();
						this.observableInput.seekWithUnknownLength(this.filePosition);
						// read content length and leading transaction mutation
						initialTransactionMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
						// if the file is shorter than the expected size of the transaction mutation, we've reached EOF
						if (initialTransactionMutation.map(it -> walFileLength < this.filePosition + it.getTransactionSpan().recordLength()).orElse(true)) {
							initialTransactionMutation = empty();
							break;
						}
					}
				} while (
					initialTransactionMutation.isEmpty() &&
						// if we've reached EOF, check whether there is file with next WAL index
						moveToNextWalFile(1)
				);
				// we've reached the first transaction mutation with the catalog version >= requested catalog version
				this.transactionMutation = initialTransactionMutation.orElse(null);
			} catch (BufferUnderflowException e) {
				// we've reached EOF or the tx mutation hasn't been yet completely written
				if (this.observableInput != null) {
					this.observableInput.close();
				}
				this.transactionMutation = null;
				this.observableInput = null;
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to read WAL file `" + this.walFile.getName() + "`!",
					"Failed to read WAL file!",
					e
				);
			}
		}
	}

	@Nullable
	@Override
	public abstract T get();

	@Override
	public void close() {
		if (this.kryo != null) {
			this.catalogKryoPool.free(this.kryo);
		}
		if (this.observableInput != null) {
			this.observableInput.close();
		}
		if (this.onClose != null) {
			this.onClose.run();
		}
	}

	/**
	 * Moves pointers to the next Write-Ahead-Log (WAL) file.
	 * @param delta The delta that should be applied to index to move to the next/prev WAL file
	 */
	protected boolean moveToNextWalFile(int delta) {
		if (this.observableInput != null) {
			this.observableInput.close();
		}

		final File nextWalFile = this.storageFolder.resolve(
			this.walFileNameProvider.apply(this.walFileIndex + delta)
		).toFile();

		if (nextWalFile.exists()) {
			try {
				this.walFile = nextWalFile;
				this.walFileIndex += delta;
				this.filePosition = 0L;
				this.observableInput = new ObservableInput<>(
					new RandomAccessFileInputStream(
						new RandomAccessFile(nextWalFile, "r"),
						true
					)
				);
				if (this.storageOptions.computeCRC32C()) {
					this.observableInput.computeCRC32();
				}
				if (this.storageOptions.compress()) {
					this.observableInput.compress();
				}

				return true;
			} catch (FileNotFoundException ignored) {
				// this should not happen when we checked before that the file exists
				// but let's pretend it hasn't existed
				return false;
			}
		} else {
			return false;
		}
	}

	/**
	 * Reads and records a transaction mutation from the given observable input.
	 *
	 * @return the transaction mutation read from the input stream
	 */
	@Nonnull
	protected Optional<TransactionMutationWithLocation> readAndRecordTransactionMutation(long startPosition, long fileSize) {
		final ObservableInput<RandomAccessFileInputStream> theObservableInput = this.observableInput;
		Assert.isPremiseValid(
			this.observableInput != null,
			"Observable input is not initialized!"
		);

		if (startPosition + 4 > fileSize) {
			// we've reached EOF
			return empty();
		}

		// read content length and leading transaction mutation
		final long totalBefore = theObservableInput.total();
		// the expected total length of current transaction (leading mutation plus all other mutations)
		final int contentLength = theObservableInput.simpleIntRead();

		if (startPosition + 4 + contentLength > fileSize) {
			// we've reached EOF
			return empty();
		}

		final TransactionMutation transactionMutation = Objects.requireNonNull(
			StorageRecord.read(
				theObservableInput, (stream, length) -> (TransactionMutation) this.kryo.readClassAndObject(stream)
			).payload()
		);
		// measure the lead mutation size + verify the content length
		final int leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBefore);
		Assert.isPremiseValid(
			contentLength + 4 == leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
			"Invalid WAL file on position `" + this.filePosition + "`!"
		);
		this.transactionLocationsCache.computeIfAbsent(
			this.walFileIndex, it -> new TransactionLocations()
		).register(this.filePosition, transactionMutation);

		this.transactionsRead++;
		return of(
			new TransactionMutationWithLocation(
				transactionMutation,
				new FileLocation(this.filePosition, contentLength + 4),
				this.walFileIndex
			)
		);
	}

}
