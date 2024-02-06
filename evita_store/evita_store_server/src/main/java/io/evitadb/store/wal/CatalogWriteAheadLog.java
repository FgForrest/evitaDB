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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serial;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;

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
public class CatalogWriteAheadLog implements Closeable {
	/**
	 * The size of a transaction mutation in the WAL file.
	 */
	public static final int TRANSACTION_MUTATION_SIZE = StorageRecord.OVERHEAD_SIZE + TransactionMutationSerializer.RECORD_SIZE;
	/**
	 * The size of a transaction mutation in the WAL file with a reserve for the class id written by Kryo automatically.
	 */
	public static final int TRANSACTION_MUTATION_SIZE_WITH_RESERVE = TRANSACTION_MUTATION_SIZE + 32;
	/**
	 * The name of the catalog.
	 */
	private final String catalogName;
	/**
	 * The path to the WAL file.
	 */
	private final Path walFilePath;
	/**
	 * The file channel for writing to the WAL file.
	 */
	private final FileChannel walFileChannel;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ByteArrayOutputStream transactionMutationOutputStream = new ByteArrayOutputStream(TRANSACTION_MUTATION_SIZE);
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ObservableOutput<ByteArrayOutputStream> output;
	/**
	 * Buffer used to serialize the overall content length and {@link TransactionMutation} at the front of the
	 * transaction log.
	 *
	 * @see ByteBuffer
	 */
	private final ByteBuffer contentLengthBuffer = ByteBuffer.allocate(4 + TRANSACTION_MUTATION_SIZE_WITH_RESERVE);
	/**
	 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
	 */
	private final Pool<Kryo> catalogKryoPool;
	/**
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	private final int walFileIndex;

	/**
	 * Method checks if the WAL file is complete and if not, it truncates it. The non-complete WAL file record is
	 * recognized by scanning the file from the beginning and jumping from one read offset to another until the end
	 * is reached. If the last jump is ok, the last transaction consistency is verified and if it's ok, the file is
	 * assumed to be correct.
	 *
	 * If the last jump is not ok, we check the previous jump (last correctly finished transaction) and verify it
	 * instead. If it's ok, the file is backed up and truncated to the last correctly finished transaction.
	 *
	 * If the consistency of the "last" transaction is not ok, the WAL is considered as damaged and exception is thrown,
	 * which effectively leads to making the catalog as corrupted.
	 *
	 * @param walFilePath     the path to the WAL file to check and truncate
	 * @param catalogKryoPool the Kryo object pool to use for deserialization
	 * @param computeCRC32C   a flag indicating whether to compute CRC32C checksums for the input
	 */
	static void checkAndTruncate(@Nonnull Path walFilePath, @Nonnull Pool<Kryo> catalogKryoPool, boolean computeCRC32C) {
		if (!walFilePath.toFile().exists()) {
			// WAL file does not exist, nothing to check
			return;
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try (
			final RandomAccessFile walFile = new RandomAccessFile(walFilePath.toFile(), "rw");
			final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(
					walFile
				)
			)
		) {
			if (computeCRC32C) {
				input.computeCRC32();
			}
			final long fileLength = walFile.length();
			if (fileLength == 0) {
				// WAL file is empty, nothing to check
				return;
			}

			int transactionSize = 0;
			int previousTransactionSize;
			long previousOffset;
			long offset = 0;
			do {
				input.skip(transactionSize);
				previousTransactionSize = transactionSize;
				previousOffset = offset;
				transactionSize = input.readInt();
				offset += 4 + transactionSize;
			} while (offset < fileLength);

			final long lastTxStartPosition;
			final int lastTxLength;
			final long consistentLength;
			if (offset > fileLength) {
				final Path backupFilePath = walFilePath.getParent().resolve("_damaged_wal.bck");
				log.warn(
					"WAL file `" + walFilePath + "` was not written completely! Truncating to the last complete" +
						" transaction (offset `" + previousOffset + "`) and backing up the original file to: `" +
						backupFilePath + "`!"
				);
				Files.copy(walFilePath, backupFilePath);
				lastTxStartPosition = previousOffset - previousTransactionSize - 4;
				lastTxLength = previousTransactionSize;
				consistentLength = previousOffset;
			} else {
				lastTxStartPosition = previousOffset;
				lastTxLength = transactionSize;
				consistentLength = offset;
			}

			try {
				input.seekWithUnknownLength(lastTxStartPosition + 4);
				final TransactionMutation transactionMutation = (TransactionMutation) StorageRecord.read(
					input, (stream, length) -> kryo.readClassAndObject(stream)
				).payload();

				final long calculatedTransactionSize = input.total() + transactionMutation.getWalSizeInBytes();
				Assert.isPremiseValid(
					lastTxLength == calculatedTransactionSize,
					() -> new WriteAheadLogCorruptedException(
						"The transaction size `" + lastTxLength + "` does not match the actual size `" +
							calculatedTransactionSize + "`!",
						"The transaction size does not match the actual size!"
					)
				);

				if (consistentLength < fileLength) {
					// truncate the WAL file to the last consistent transaction
					walFile.setLength(consistentLength);
				}

			} catch (Exception ex) {
				log.error(
					"Failed to read the last transaction from WAL file `" + walFilePath + "`! The file is probably" +
						" corrupted! The catalog will be marked as corrupted!",
					ex
				);
				if (ex instanceof WriteAheadLogCorruptedException) {
					// just rethrow
					throw ex;
				} else {
					// wrap original exception
					throw new WriteAheadLogCorruptedException(
						"Failed to read the last transaction from WAL file `" + walFilePath + "`! The file is probably" +
							" corrupted! The catalog will be marked as corrupted!",
						"Failed to read the last transaction from WAL file!",
						ex
					);
				}
			}

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open WAL file `" + walFilePath + "`!",
				"Failed to open WAL file!",
				e
			);
		} finally {
			catalogKryoPool.free(kryo);
		}
	}

	/**
	 * Creates a Write-Ahead Log (WAL) file if it does not already exist.
	 *
	 * @param walFilePath The path of the WAL file.
	 * @throws IOException If an I/O error occurs.
	 */
	private static void createWalFileNotExists(@Nonnull Path walFilePath) throws IOException {
		final File walFile = walFilePath.toFile();
		if (!walFile.exists()) {
			final File parentDirectory = walFilePath.getParent().toFile();
			if (!parentDirectory.exists()) {
				Assert.isPremiseValid(
					parentDirectory.mkdirs(),
					"Failed to create parent directory `" + parentDirectory + "` for WAL file `" + walFilePath + "`!"
				);
			}
			Assert.isPremiseValid(
				walFile.createNewFile(),
				"Failed to create WAL file `" + walFilePath + "`!"
			);
		}
	}

	public CatalogWriteAheadLog(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull WalFileReference walFileReference,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions
	) {
		this.walFileIndex = walFileReference.fileIndex();
		try {
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, this.walFileIndex));
			checkAndTruncate(walFilePath, catalogKryoPool, storageOptions.computeCRC32C());

			this.catalogName = catalogName;
			this.catalogKryoPool = catalogKryoPool;

			// create the WAL file if it does not exist
			createWalFileNotExists(walFilePath);
			this.walFilePath = walFilePath;
			this.walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);
			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				transactionMutationOutputStream,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);
			this.output = storageOptions.computeCRC32C() ?
				theOutput.computeCRC32() : theOutput;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open WAL file `" + getWalFileName(catalogName, this.walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
	}

	/**
	 * Appends a transaction mutation to the Write-Ahead Log (WAL) file.
	 *
	 * @param transactionMutation The transaction mutation to append.
	 * @param walReference        The reference to the WAL file.
	 */
	public void append(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		final Kryo kryo = catalogKryoPool.obtain();
		try {
			// write transaction mutation to memory buffer
			transactionMutationOutputStream.reset();
			output.reset();
			final StorageRecord<TransactionMutation> record = new StorageRecord<>(
				output,
				transactionMutation.getCatalogVersion(),
				false,
				theOutput -> {
					kryo.writeClassAndObject(theOutput, transactionMutation);
					return transactionMutation;
				}
			);
			output.flush();

			// write content length first
			final int contentLength = walReference.getContentLength();
			final int contentLengthWithTxMutation = contentLength + record.fileLocation().recordLength();
			contentLengthBuffer.clear();
			contentLengthBuffer.put((byte) contentLengthWithTxMutation);
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 8));
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 16));
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 24));
			contentLengthBuffer.put(transactionMutationOutputStream.toByteArray(), 0, record.fileLocation().recordLength());

			int written = 0;
			contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			while (contentLengthBuffer.hasRemaining()) {
				written += this.walFileChannel.write(contentLengthBuffer);
			}
			Assert.isPremiseValid(
				written == record.fileLocation().recordLength() + 4,
				"Failed to write content length to WAL file!"
			);

			if (walReference.getBuffer().isPresent()) {
				// write the buffer contents
				final ByteBuffer byteBuffer = walReference.getBuffer().get();
				written = 0;
				while (byteBuffer.hasRemaining()) {
					written += this.walFileChannel.write(byteBuffer);// Write buffer to file
				}
			} else if (walReference.getFilePath().isPresent()) {
				try (
					final FileChannel readChannel = FileChannel.open(
						walReference.getFilePath().get(),
						StandardOpenOption.READ
					)
				) {
					written = 0;
					while (written < contentLength) {
						written += Math.toIntExact(readChannel.transferTo(written, contentLength, this.walFileChannel));
					}
				}
			}

			Assert.isPremiseValid(
				written == contentLength,
				"Failed to write all bytes (" + written + " of " + contentLength + " Bytes) to WAL file!"
			);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to append WAL to catalog `" + this.catalogName + "`!",
				"Failed to append WAL to catalog!",
				e
			);
		} finally {
			catalogKryoPool.free(kryo);
		}
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param catalogVersion the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<Mutation> getCommittedMutationStream(long catalogVersion) {
		final File walFile = this.walFilePath.toFile();
		if (!walFile.exists() || walFile.length() < 4) {
			// WAL file does not exist or is empty, nothing to read
			return Stream.empty();
		} else {
			// TODO JNO - consider some kind of caching for last queried positions (FIFO, search nearest and go from there)
			final MutationSupplier supplier = new MutationSupplier(
				catalogVersion, walFile, this.walFileIndex, this.catalogKryoPool
			);
			return Stream.generate(supplier)
				.takeWhile(Objects::nonNull)
				.onClose(supplier::close);
		}
	}

	@Override
	public void close() throws IOException {
		this.output.close();
		this.walFileChannel.close();
		this.transactionMutationOutputStream.close();
	}

	/**
	 * Gets the reference to a WAL (Write-Ahead Log) file with the last processed WAL record position.
	 *
	 * @param lastProcessedTransaction The last processed WAL record position.
	 * @return The WAL file reference with last processed WAL record position.
	 */
	@Nonnull
	public WalFileReference getWalFileReference(@Nullable TransactionMutation lastProcessedTransaction) {
		Assert.isPremiseValid(
			lastProcessedTransaction instanceof TransactionMutationWithLocation,
			"Invalid last processed transaction!"
		);
		final TransactionMutationWithLocation lastProcessedTransactionWithLocation = (TransactionMutationWithLocation) lastProcessedTransaction;
		return new WalFileReference(
			this.catalogName,
			lastProcessedTransactionWithLocation.getWalFileIndex(),
			lastProcessedTransactionWithLocation.getTransactionSpan()
		);
	}

	/**
	 * This private static class represents a Supplier of Mutation objects and implements the Supplier and AutoCloseable
	 * interfaces.
	 * It is used to supply Mutation objects from a Write-Ahead Log (WAL) file.
	 */
	private static class MutationSupplier implements Supplier<Mutation>, AutoCloseable {
		/**
		 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
		 */
		private final Pool<Kryo> catalogKryoPool;
		/**
		 * The Kryo object for serializing {@link TransactionMutation} obtained in constructor from the Kryo pool.
		 */
		private final Kryo kryo;
		/**
		 * The Write-Ahead Log (WAL) file reference
		 */
		private final File walFile;
		/**
		 * The index of the WAL file incremented each time the WAL file is rotated.
		 */
		private final int walFileIndex;
		/**
		 * The ObservableInput for reading {@link TransactionMutation} from the WAL file.
		 */
		private final ObservableInput<RandomAccessFileInputStream> observableInput;
		/**
		 * The current {@link TransactionMutation} being read from the WAL file.
		 */
		private TransactionMutationWithLocation transactionMutation;
		/**
		 * The size of the leading transaction mutation in the WAL file.
		 */
		private int leadTransactionMutationSize;
		/**
		 * The expected total length of current transaction (leading mutation plus all other mutations).
		 */
		private int contentLength;
		/**
		 * The current position in the WAL file.
		 */
		private long filePosition;
		/**
		 * The number of mutations read from the current transaction.
		 */
		private int transactionMutationRead;

		public MutationSupplier(long catalogVersion, @Nonnull File walFile, int walFileIndex, @Nonnull Pool<Kryo> catalogKryoPool) {
			this.walFile = walFile;
			this.walFileIndex = walFileIndex;
			if (walFile.length() == 0) {
				this.catalogKryoPool = catalogKryoPool;
				this.kryo = null;
				this.observableInput = null;
				this.transactionMutation = null;
			} else {
				this.catalogKryoPool = catalogKryoPool;
				this.kryo = catalogKryoPool.obtain();
				ObservableInput<RandomAccessFileInputStream> theObservableInput;
				try {
					final RandomAccessFile randomWalFile = new RandomAccessFile(walFile, "r");
					theObservableInput = new ObservableInput<>(
						new RandomAccessFileInputStream(
							randomWalFile
						)
					);
					this.filePosition = 0;
					// read content length and leading transaction mutation
					final long totalBefore = theObservableInput.total();
					this.contentLength = theObservableInput.readInt();
					TransactionMutation transactionMutation = (TransactionMutation) StorageRecord.read(
						theObservableInput, (stream, length) -> kryo.readClassAndObject(stream)
					).payload();
					// measure the lead mutation size + verify the content length
					this.leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBefore);
					Assert.isPremiseValid(
						this.contentLength + 4 == this.leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
						"Invalid WAL file on position `" + this.filePosition + "`!"
					);
					FileLocation transactionSpan = new FileLocation(this.filePosition, 4 + this.contentLength);
					// move cursor to the end of the lead mutation
					this.filePosition += this.leadTransactionMutationSize;
					while (transactionMutation.getCatalogVersion() < catalogVersion) {
						// move cursor to the next transaction mutation
						this.filePosition += transactionMutation.getWalSizeInBytes();
						theObservableInput.seekWithUnknownLength(this.filePosition);
						// read content length and leading transaction mutation
						final long totalBeforeRead = theObservableInput.total();
						this.contentLength = theObservableInput.readInt();
						transactionMutation = (TransactionMutation) StorageRecord.read(
							theObservableInput, (stream, length) -> kryo.readClassAndObject(stream)
						).payload();
						// measure the lead mutation size + verify the content length
						this.leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBeforeRead);
						Assert.isPremiseValid(
							this.contentLength + 4 == this.leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
							"Invalid WAL file on position `" + this.filePosition + "`!"
						);
						transactionSpan = new FileLocation(this.filePosition, 4 + this.contentLength);
						// move cursor to the end of the lead mutation
						this.filePosition += this.leadTransactionMutationSize;
						// if the file is shorter than the expected size of the transaction mutation, we've reached EOF
						if (this.walFile.length() < this.filePosition + transactionMutation.getWalSizeInBytes()) {
							transactionMutation = null;
							break;
						}
					}
					// we've reached the first transaction mutation with catalog version >= requested catalog version
					this.transactionMutation = new TransactionMutationWithLocation(
						transactionMutation, transactionSpan, this.walFileIndex
					);
				} catch (BufferUnderflowException e) {
					// we've reached EOF or the tx mutation hasn't been yet completely written
					this.transactionMutation = null;
					theObservableInput = null;
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to read WAL file `" + walFile.getName() + "`!",
						"Failed to read WAL file!",
						e
					);
				}
				this.observableInput = theObservableInput;
			}
		}

		@Override
		public Mutation get() {
			if (this.transactionMutationRead == 0) {
				this.transactionMutationRead++;
				return this.transactionMutation;
			} else {
				if (this.transactionMutationRead <= this.transactionMutation.getMutationCount()) {
					this.transactionMutationRead++;
					return (Mutation) StorageRecord.read(
						this.observableInput, (stream, length) -> kryo.readClassAndObject(stream)
					).payload();
				} else {
					// advance position to the end of the last transaction
					this.filePosition += this.transactionMutation.getWalSizeInBytes();
					try {
						// check the entire transaction was written
						final long currentFileLength = this.walFile.length();
						if (currentFileLength <= this.filePosition) {
							// we've reached EOF
							return null;
						}
						// read content length and next leading transaction mutation
						final long totalBeforeRead = this.observableInput.total();
						this.contentLength = this.observableInput.readInt();
						final TransactionMutation txMutation = (TransactionMutation) StorageRecord.read(
							this.observableInput, (stream, length) -> kryo.readClassAndObject(stream)
						).payload();
						this.transactionMutation = new TransactionMutationWithLocation(
							txMutation, new FileLocation(this.filePosition, this.contentLength + 4), this.walFileIndex
						);
						// measure the lead mutation size + verify the content length
						this.leadTransactionMutationSize = Math.toIntExact(this.observableInput.total() - totalBeforeRead);
						Assert.isPremiseValid(
							this.contentLength + 4 == this.leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
							"Invalid WAL file on position `" + this.filePosition + "`!"
						);
						// move cursor to the end of the lead mutation
						this.filePosition += this.leadTransactionMutationSize;
						// check the entire transaction was written
						if (currentFileLength > this.filePosition + this.transactionMutation.getWalSizeInBytes()) {
							this.transactionMutationRead = 1;
							// return the transaction mutation
							return this.transactionMutation;
						} else {
							// we've reached EOF or the tx mutation hasn't been yet completely written
							return null;
						}
					} catch (BufferUnderflowException | KryoException ex) {
						// we've reached EOF or the tx mutation hasn't been yet completely written
						return null;
					}
				}
			}
		}

		@Override
		public void close() {
			if (this.kryo != null) {
				this.catalogKryoPool.free(this.kryo);
			}
			if (observableInput != null) {
				this.observableInput.close();
			}
		}
	}

	/**
	 * Represents a TransactionMutation with additional location information.
	 */
	private static class TransactionMutationWithLocation extends TransactionMutation {
		@Serial private static final long serialVersionUID = -5873907941292188132L;
		@Nonnull @Getter
		private final FileLocation transactionSpan;
		@Getter
		private final int walFileIndex;

		public TransactionMutationWithLocation(@Nonnull TransactionMutation delegate, @Nonnull FileLocation transactionSpan, int walFileIndex) {
			super(delegate.getTransactionId(), delegate.getCatalogVersion(), delegate.getMutationCount(), delegate.getWalSizeInBytes());
			this.transactionSpan = transactionSpan;
			this.walFileIndex = walFileIndex;
		}

	}

}
