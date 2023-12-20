/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * ReadOnlyFileHandle protects access to the {@link #readInput}. No locking is required here - enveloping logic
 * is responsible for maintaining single threading access to the read only handle. Using {@link com.esotericsoftware.kryo.util.Pool}
 * is expected - this effectively excludes the possibility to use the resource in parallel. Locking in this class would
 * only add to latency.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReadOnlyFileHandle implements ReadOnlyHandle {
	/**
	 * Represents the target file for reading.
	 */
	private final Path targetFile;
	/**
	 * Specialized form of input stream that could be read from.
	 */
	private final ObservableInput<?> readInput;

	public ReadOnlyFileHandle(@Nonnull Path targetFile, boolean computeCRC32C) {
		try {
			this.targetFile = targetFile;
			final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(
					new RandomAccessFile(targetFile.toFile(), "r"),
					true
				)
			);
			this.readInput = computeCRC32C ? input.computeCRC32() : input;
		} catch (FileNotFoundException ex) {
			throw new StorageException("Target file " + targetFile + " cannot be opened!", ex);
		}
	}

	@Override
	public <T> T execute(@Nonnull Function<ObservableInput<?>, T> logic) {
		return logic.apply(this.readInput);
	}

	@Override
	public long getLastWrittenPosition() {
		return targetFile.toFile().length();
	}

	/**
	 * This method closes the read handle ignoring the current lock.
	 */
	public void forceClose() {
		readInput.close();
	}

	@Override
	public String toString() {
		return "read handle: " + targetFile;
	}
}