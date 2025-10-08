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
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies {@link OffHeapMemoryOutputStream} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class WriteOnlyOffHeapWithFileBackupHandleTest implements EvitaTestSupport {
	private final Path targetDirectory = getPathInTargetDirectory("WriteOnlyOffHeapWithFileBackupHandle");
	private final Path targetExportDirectory = getPathInTargetDirectory("WriteOnlyOffHeapWithFileBackupHandle_export");
	private final StorageOptions storageOptions = StorageOptions.builder()
		.storageDirectory(this.targetDirectory)
		.exportDirectory(this.targetExportDirectory)
		.computeCRC32(true)
		.build();
	private final ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		this.storageOptions,
		Mockito.mock(Scheduler.class)
	);

	@AfterEach
	void tearDown() {
		this.outputKeeper.close();
	}

	@Test
	void shouldWriteDataToOffHeapChunk() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(TEST_CATALOG, 32, 1);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"), this.storageOptions, this.outputKeeper, memoryManager
			)
		) {
			writeHandle.checkAndExecuteAndSync(
				"write some data",
				() -> {
				},
				output -> output.writeString("Small data content")
			);

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(18, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						assertEquals("Small data content", input.readString());
						return null;
					}
				);
			}
		}
	}

	@Test
	void shouldWriteLargeDataFirstToOffHeapChunkThatAutomaticallySwitchesToTemporaryFileWithSync() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(TEST_CATALOG, 32, 1);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"), this.storageOptions, this.outputKeeper, memoryManager
			)
		) {
			for (int i = 0; i < 5; i++) {
				int index = i;
				writeHandle.checkAndExecuteAndSync(
					"write some data",
					() -> {
					},
					output -> output.writeString("Data " + index + ".")
				);
			}

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(35, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						for (int i = 0; i < 5; i++) {
							assertEquals("Data " + i + ".", input.readString());
						}
						return null;
					}
				);
			}
		}
	}

	@Test
	void shouldWriteLargeDataFirstToOffHeapChunkThatAutomaticallySwitchesToTemporaryFileWithoutSync() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(TEST_CATALOG, 32, 1);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"), this.storageOptions, this.outputKeeper, memoryManager
			)
		) {
			for (int i = 0; i < 5; i++) {
				int index = i;
				writeHandle.checkAndExecute(
					"write some data",
					() -> {
					},
					output -> {
						output.writeString("Data " + index + ".");
						return null;
					}
				);
			}

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(35, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						for (int i = 0; i < 5; i++) {
							assertEquals("Data " + i + ".", input.readString());
						}
						return null;
					}
				);
			}
		}
	}

	@Test
	void shouldStartDirectlyWithFileBackupIfThereIsNoFreeMemoryRegionAvailable() {

		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(TEST_CATALOG, 32, 1);
			final WriteOnlyOffHeapWithFileBackupHandle realMemoryHandle = new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"), this.storageOptions, this.outputKeeper, memoryManager
			)
		) {
			// we need to write at least one byte to the real memory handle to force the memory manager
			// to allocate the memory region
			realMemoryHandle.checkAndExecuteAndSync(
				"write some data",
				() -> {
				},
				output -> output.writeByte((byte) 0)
			);
			// because there is only one region available - this will force the handle to use the file backup immediately
			try (
				final WriteOnlyOffHeapWithFileBackupHandle forcedFileHandle = new WriteOnlyOffHeapWithFileBackupHandle(
					this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"), this.storageOptions, this.outputKeeper, memoryManager
				)
			) {
				for (int i = 0; i < 5; i++) {
					int index = i;
					forcedFileHandle.checkAndExecuteAndSync(
						"write some data",
						() -> {
						},
						output -> output.writeString("Data " + index + ".")
					);
				}

				try (final ReadOnlyHandle readOnlyHandle = forcedFileHandle.toReadOnlyHandle()) {
					assertEquals(35, readOnlyHandle.getLastWrittenPosition());
					readOnlyHandle.execute(
						input -> {
							for (int i = 0; i < 5; i++) {
								assertEquals("Data " + i + ".", input.readString());
							}
							return null;
						}
					);
				}
			}
		}
	}
}
