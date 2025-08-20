/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.storage.ReadOnlyHandleClosedEvent;
import io.evitadb.core.metric.event.storage.ReadOnlyHandleOpenedEvent;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	 * Name of the catalog the persistence service relates to - used for observability.
	 */
	private final String catalogName;
	/**
	 * Logical name of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	protected final String logicalName;
	/**
	 * Type of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	private final FileType fileType;
	/**
	 * Represents the target file for reading.
	 */
	private final Path targetFile;
	/**
	 * Specialized form of input stream that could be read from.
	 */
	private final ObservableInput<?> readInput;

	public ReadOnlyFileHandle(
		@Nonnull Path targetFile,
		@Nonnull StorageOptions storageOptions
		) {
		this(null, null, null, targetFile, storageOptions);
	}

	public ReadOnlyFileHandle(
		@Nullable String catalogName,
		@Nullable FileType fileType,
		@Nullable String logicalName,
		@Nonnull Path targetFile,
		@Nonnull StorageOptions storageOptions
	) {
		try {
			this.catalogName = catalogName;
			this.fileType = fileType;
			this.logicalName = logicalName;
			this.targetFile = targetFile;
			this.readInput = new ObservableInput<>(
				new RandomAccessFileInputStream(
					new RandomAccessFile(targetFile.toFile(), "r"),
					true
				)
			);
			if (storageOptions.computeCRC32C()) {
				this.readInput.computeCRC32();
			}
			if (storageOptions.compress()) {
				this.readInput.compress();
			}

			// emit event
			if (this.catalogName != null && this.fileType != null && this.logicalName != null) {
				new ReadOnlyHandleOpenedEvent(this.catalogName, this.fileType, this.logicalName).commit();
			}
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
		return this.targetFile.toFile().length();
	}

	/**
	 * This method closes the read handle ignoring the current lock.
	 */
	@Override
	public void close() {
		this.readInput.close();

		// emit event
		if (this.catalogName != null && this.fileType != null && this.logicalName != null) {
			new ReadOnlyHandleClosedEvent(this.catalogName, this.fileType, this.logicalName).commit();
		}
	}

	@Override
	public String toString() {
		return "read handle: " + this.targetFile;
	}
}
