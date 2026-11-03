/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.kryo;

import java.util.concurrent.ConcurrentHashMap;
import com.esotericsoftware.kryo.KryoException;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the keeper caches and expires file streams while frame buffers belong to the shared pool.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ObservableOutputKeeper file stream cache")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class ObservableOutputKeeperTest {
	@TempDir private Path directory;
	private final ObservableOutputKeeper keeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));

	/** Closes any remaining cached streams. */
	@AfterEach
	void tearDown() {
		this.keeper.close();
	}

	/** File streams remain cached independently of borrowed arrays. */
	@Nested
	@DisplayName("File stream lifecycle")
	class FileStreams {
		/** Reuses the stream after returning its frame buffer. */
		@Test
		@DisplayName("Should cache a detached output between leases")
		void shouldCacheDetachedOutputBetweenLeases() {
			final Path path = directory.resolve("data.bin");
			final ObservableOutput<?> first = keeper.executeWithOutput(path, ObservableOutputKeeperTest.this::createOutput,
				output -> {
					output.writeInt(123);
					output.flush();
					return output;
				});
			assertEquals(0, first.getBuffer().length);
			keeper.executeWithOutput(path, ObservableOutputKeeperTest.this::createOutput, output -> {
				assertSame(first, output);
			});
		}

		/** Closing one path releases its stream and the next lease opens a fresh one. */
		@Test
		@DisplayName("Should close a cached stream explicitly")
		void shouldCloseCachedStreamExplicitly() {
			final Path path = directory.resolve("data.bin");
			final ObservableOutput<FileOutputStream> first = keeper.executeWithOutput(
				path, ObservableOutputKeeperTest.this::createOutput, output -> output
			);
			keeper.close(path);
			assertFalse(first.getOutputStream().getChannel().isOpen());
			keeper.executeWithOutput(path, ObservableOutputKeeperTest.this::createOutput, output -> {
				assertNotSame(first, output);
			});
		}

		/** An expired stream is closed by the scheduled cache-cut operation. */
		@Test
		@DisplayName("Should close inactive streams")
		void shouldCloseInactiveStreams() throws ReflectiveOperationException {
			final Path path = directory.resolve("data.bin");
			final ObservableOutput<FileOutputStream> output = keeper.executeWithOutput(
				path, ObservableOutputKeeperTest.this::createOutput, stream -> stream
			);
			final Field cache = ObservableOutputKeeper.class.getDeclaredField("cachedOutputToFiles");
			cache.setAccessible(true);
			final Object entry = ((Map<?, ?>) cache.get(keeper)).get(path);
			final Field touched = entry.getClass().getDeclaredField("lastReadTime");
			touched.setAccessible(true);
			touched.setLong(entry, System.currentTimeMillis() - 400_000L);
			final Method cut = ObservableOutputKeeper.class.getDeclaredMethod("cutOutputCache");
			cut.setAccessible(true);
			assertEquals(-1L, cut.invoke(keeper));
			assertFalse(output.getOutputStream().getChannel().isOpen());
		}

		/**
		 * A cutter may retire the entry after lookup but before the writer acquires its lease.
		 *
		 * @throws ReflectiveOperationException if the cache cannot be inspected
		 */
		@Test
		@DisplayName("Should retry leasing when the cutter closed the entry after lookup")
		void shouldRetryLeaseWhenCutClosesEntryAfterLookup() throws ReflectiveOperationException {
			final Path path = directory.resolve("lease-race.bin");
			final ObservableOutput<FileOutputStream> original = keeper.executeWithOutput(
				path, ObservableOutputKeeperTest.this::createOutput, output -> output);
			final Field cache = ObservableOutputKeeper.class.getDeclaredField("cachedOutputToFiles");
			cache.setAccessible(true);
			final ConcurrentHashMap<Path, Object> interleavingCache =
				new ConcurrentHashMap<>() {
					/**
					 * Runs the cutter at the precise boundary between lookup and lease.
					 *
					 * @param key target file whose cached output is retrieved
					 * @param factory creates an output when the key is absent
					 * @return the entry retrieved before the cutter had a chance to close it
					 */
					@Override
					public Object computeIfAbsent(Path key, Function<? super Path, ?> factory) {
						final Object entry = super.computeIfAbsent(key, factory);
						if (original.getOutputStream().getChannel().isOpen()) {
							try {
								expireAndCut(entry);
							} catch (ReflectiveOperationException exception) {
								throw new IllegalStateException(exception);
							}
						}
						return entry;
					}
				};
			((Map<?, ?>) cache.get(keeper)).forEach((key, entry) -> interleavingCache.put((Path) key, entry));
			cache.set(keeper, interleavingCache);
			keeper.executeWithOutput(path, ObservableOutputKeeperTest.this::createOutput, output -> {
				assertNotSame(original, output);
				new StorageRecord<>(output, 1L, false, out -> {
					out.writeInt(42);
					return 42;
				});
			});
			assertFalse(original.getOutputStream().getChannel().isOpen());
		}

		/**
		 * A lease keeps its stream and borrowed frame array alive while the cutter runs.
		 *
		 * @throws ReflectiveOperationException if the cache cannot be inspected
		 */
		@Test
		@DisplayName("Should preserve a leased output and its frame during a cut")
		void shouldPreserveLeasedOutputDuringCut() throws ReflectiveOperationException {
			final Path path = directory.resolve("held-lease.bin");
			keeper.executeWithOutput(path, ObservableOutputKeeperTest.this::createOutput, output -> {
				output.markStart();
				output.writeInt(42);
				final byte[] buffer = output.getBuffer();
				try {
					final Field cache = ObservableOutputKeeper.class.getDeclaredField("cachedOutputToFiles");
					cache.setAccessible(true);
					expireAndCut(((Map<?, ?>) cache.get(keeper)).get(path));
				} catch (ReflectiveOperationException exception) {
					throw new IllegalStateException(exception);
				}
				assertTrue(output.getOutputStream().getChannel().isOpen());
				assertSame(buffer, output.getBuffer());
				output.reset();
			});
		}

		/** The next lease after a partially appended frame appends at the file's physical end. */
		@Test
		@DisplayName("Should reopen the output at the file length after a frame was partially appended")
		void shouldReopenOutputAfterPartiallyAppendedFrame() {
			final Path path = directory.resolve("data.bin");
			final AtomicInteger openedOutputs = new AtomicInteger();
			final Function<Path, ObservableOutput<FileOutputStream>> createFct =
				target -> createOutputAtFileLength(target, openedOutputs.getAndIncrement() == 0);
			final AtomicReference<ObservableOutput<?>> failed = new AtomicReference<>();
			assertThrows(KryoException.class, () -> keeper.executeWithOutput(path, createFct, output -> {
				failed.set(output);
				new StorageRecord<>(output, 1L, false, out -> {
					out.writeBytes(new byte[32]);
					return 1;
				});
			}));
			final long partialLength = path.toFile().length();
			assertEquals(StorageRecord.CRC_NOT_COVERED_HEAD, partialLength);

			final FileLocation location = keeper.executeWithOutput(path, createFct, output -> {
				assertNotSame(failed.get(), output);
				return new StorageRecord<>(output, 2L, false, out -> {
					out.writeBytes(new byte[8]);
					return 1;
				}).fileLocation();
			});
			assertEquals(partialLength, location.startingPosition());
			assertEquals(partialLength + location.recordLength(), path.toFile().length());
		}
	}

	/**
	 * Ages an entry and invokes the scheduled cutter without waiting for wall-clock time.
	 *
	 * @param entry cached output entry to expire
	 * @throws ReflectiveOperationException if the entry or cutter cannot be accessed
	 */
	private void expireAndCut(@Nonnull Object entry) throws ReflectiveOperationException {
		final Field touched = entry.getClass().getDeclaredField("lastReadTime");
		touched.setAccessible(true);
		touched.setLong(entry, System.currentTimeMillis() - 400_000L);
		final Method cut = ObservableOutputKeeper.class.getDeclaredMethod("cutOutputCache");
		cut.setAccessible(true);
		cut.invoke(this.keeper);
	}

	/**
	 * Opens a pooled file output at the file's physical length, as the write handles do.
	 *
	 * @param path file to append to
	 * @param failSecondArrayWrite whether the stream fails its second array write, as a full disk would
	 * @return an output appending to the file
	 */
	@Nonnull
	private static ObservableOutput<FileOutputStream> createOutputAtFileLength(
		@Nonnull Path path,
		boolean failSecondArrayWrite
	) {
		try {
			final File file = path.toFile();
			return new ObservableOutput<>(
				new FileOutputStream(file, true) {
					private int arrayWrites;

					@Override
					public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
						if (failSecondArrayWrite && ++this.arrayWrites == 2) {
							throw new IOException("No space left on device");
						}
						super.write(bytes, offset, length);
					}
				},
				1024, file.length(), Checksum.NO_OP, null, ObservableOutputBufferPool.INSTANCE
			);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/** Opens a pooled file output for the cache's production factory contract. */
	@Nonnull
	private ObservableOutput<FileOutputStream> createOutput(@Nonnull Path path) {
		try {
			return new ObservableOutput<>(
				new FileOutputStream(path.toFile(), true), 1024, 0, Checksum.NO_OP, null,
				ObservableOutputBufferPool.INSTANCE
			);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}
}
