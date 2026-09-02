/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.engine;

import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;
import io.evitadb.store.catalog.CatalogStorageFootprintMeasurer;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.engine.CatalogFolderAllocator;
import io.evitadb.utils.FileUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Builds {@link CatalogFolderContext} instances for tests that exercise engine components in isolation, without
 * standing up a storage layer.
 *
 * The folder operations are backed by the real filesystem under the passed storage directory, reproducing the
 * legacy `folder name == catalog name` binding. That matters: tests such as
 * `RestoreCatalogSchemaMutationOperatorTest` assert on the operator's folder-presence precondition, so a stubbed
 * implementation that always answered `true` would make those assertions vacuous.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TestCatalogFolderContexts {

	/**
	 * Per-catalog folder generation counters, standing in for the engine-scoped sequence service.
	 */
	private static final Map<String, AtomicInteger> GENERATIONS = new ConcurrentHashMap<>(8);

	private TestCatalogFolderContexts() {
	}

	/**
	 * Creates a context binding every catalog to a same-named folder directly under the passed directory.
	 *
	 * @param storageDirectory root directory holding the catalog folders
	 * @return context usable by engine components under test
	 */
	@Nonnull
	public static CatalogFolderContext onDirectory(@Nonnull Path storageDirectory) {
		return onDirectory(storageDirectory, CatalogFolderResolver.identity());
	}

	/**
	 * Creates a context over the passed directory whose bindings come from the supplied resolver.
	 *
	 * Needed wherever a test has to reproduce a state in which a catalog's binding and the folder its data is
	 * actually in disagree — a catalog whose folder vanished, or one that outlived a rename. The identity
	 * resolver cannot express either.
	 *
	 * @param storageDirectory root directory holding the catalog folders
	 * @param folderResolver   answers which folder each catalog name is bound to
	 * @return context usable by engine components under test
	 */
	@Nonnull
	public static CatalogFolderContext onDirectory(
		@Nonnull Path storageDirectory,
		@Nonnull CatalogFolderResolver folderResolver
	) {
		return new CatalogFolderContext(
			folderResolver,
			new FileSystemFolderOperations(storageDirectory),
			storageDirectory,
			// a real ascending counter rather than a constant, so a test that allocates twice for one name gets
			// two distinct folders exactly as production does
			catalogName -> GENERATIONS.computeIfAbsent(catalogName, __ -> new AtomicInteger()).incrementAndGet()
		);
	}

	/**
	 * Folder operations performed directly against the filesystem, standing in for the storage layer.
	 */
	@RequiredArgsConstructor
	private static class FileSystemFolderOperations implements CatalogFolderOperations {
		private final Path storageDirectory;

		@Override
		public boolean catalogFolderExists(@Nonnull CatalogFolderId folderId) {
			return this.storageDirectory.resolve(folderId.id()).toFile().exists();
		}

		@Override
		public void dropCatalogFolder(@Nonnull CatalogFolderId folderId) {
			final Path folder = this.storageDirectory.resolve(folderId.id());
			if (folder.toFile().exists()) {
				FileUtils.deleteDirectory(folder);
			}
		}

		@Override
		public long catalogFolderSize(@Nonnull CatalogFolderId folderId) {
			final Path folder = this.storageDirectory.resolve(folderId.id());
			return folder.toFile().exists() ? FileUtils.getDirectorySize(folder) : 0L;
		}

		@Nonnull
		@Override
		public CatalogStorageFootprint catalogFolderFootprint(
			@Nonnull CatalogFolderId folderId,
			@Nonnull String catalogName
		) {
			return CatalogStorageFootprintMeasurer.measure(
				catalogName, this.storageDirectory.resolve(folderId.id()), null
			);
		}

		@Nonnull
		@Override
		public CatalogFolderId allocateCatalogFolder(
			@Nonnull String catalogName,
			@Nonnull IntSupplier generationSupplier
		) {
			return CatalogFolderAllocator.allocate(this.storageDirectory, catalogName, generationSupplier);
		}

		@Nonnull
		@Override
		public CatalogFolderId adoptCatalogFolder(
			@Nonnull CatalogFolderId folderId,
			@Nonnull String catalogName,
			@Nonnull IntSupplier generationSupplier
		) {
			return CatalogFolderAllocator.adopt(
				this.storageDirectory, folderId, catalogName, generationSupplier
			);
		}

		@Override
		public void recordCatalogNameInFolder(@Nonnull CatalogFolderId folderId, @Nonnull String catalogName) {
			CatalogFolderAllocator.writeCatalogNameMarker(
				this.storageDirectory.resolve(folderId.id()), catalogName
			);
		}

		@Override
		public void clearProvisionalCatalogFolderMarker(@Nonnull CatalogFolderId folderId) {
			CatalogFolderAllocator.clearProvisionalMarker(this.storageDirectory.resolve(folderId.id()));
		}

		@Nonnull
		@Override
		public Map<String, Integer> observedFolderGenerationPeaks() {
			return CatalogFolderAllocator.observedPeaks(this.storageDirectory);
		}

	}

}
