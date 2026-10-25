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

import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.checksum.Checksum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

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
