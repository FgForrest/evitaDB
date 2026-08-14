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

package io.evitadb.store.catalog;

import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the storage footprint is measured against the prefix the folder's files actually carry, rather than
 * against the catalog's current name.
 *
 * A rename or a `replaceCatalog` relabels a catalog without touching a single file name - that is what makes it a
 * pointer swap rather than a filesystem walk - so after either one the two diverge permanently. Every name the
 * decomposition matches on is built from the prefix, which is why getting it wrong does not mis-size one class but
 * empties several at once into the unaccounted remainder.
 *
 * This is pinned as a unit test because a functional one cannot reach it: the divergence only appears after an
 * operation ordinary fixtures never perform, and the reading that exposes it is the one taken of a catalog that will
 * not open, which has no persistence service to ask for the prefix.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog storage footprint (storage prefix vs catalog name)")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class CatalogStorageFootprintPrefixTest {

	@Test
	@DisplayName("A renamed catalog's bootstrap file is still attributed, not left unaccounted")
	void shouldAttributeBootstrapBytesOfARenamedCatalog(@TempDir Path folder) throws IOException {
		// the folder of a catalog created as `old` and since renamed to `new`: the files keep the prefix they were
		// written with, and nothing rewrites them
		final long bootstrapBytes = write(folder, "old.boot", 128);
		final long walBytes = write(folder, "old_0.wal", 64);

		// measured under the name the catalog carries *now*, which is what the engine knows about an unopenable
		// catalog - it has no persistence service left to ask for the prefix
		final CatalogStorageFootprint footprint = CatalogStorageFootprintMeasurer.measure(
			"new", listing(folder), null
		);

		assertEquals(bootstrapBytes + walBytes, footprint.totalBytes());
		// read `0` before the prefix was discovered from the listing, with the bytes falling into the remainder
		assertEquals(bootstrapBytes, footprint.bootstrapBytes());
		// the log was always attributed, because it is matched by suffix rather than by prefix - which is what made
		// the defect look like a partial answer rather than an obviously broken one
		assertEquals(walBytes, footprint.walBytes());
		assertEquals(0L, footprint.unaccountedBytes());
	}

	@Test
	@DisplayName("A folder with no bootstrap file falls back to the catalog name instead of failing")
	void shouldFallBackToTheCatalogNameWhenNoBootstrapFileIsPresent(@TempDir Path folder) throws IOException {
		// a folder holding files but no bootstrap file is the corruption an operator is calling this to size up.
		// Throwing here - as the open path deliberately does - would withhold the measurement at exactly the moment
		// it is the only reading available
		final long strayBytes = write(folder, "something-unexpected.tmp", 32);

		final CatalogStorageFootprint footprint = CatalogStorageFootprintMeasurer.measure(
			"whateverItIsCalledNow", listing(folder), null
		);

		assertEquals(strayBytes, footprint.totalBytes());
		assertEquals(0L, footprint.bootstrapBytes());
		// visible in the remainder rather than silently dropped, which is the honest answer here
		assertEquals(strayBytes, footprint.unaccountedBytes());
	}

	/**
	 * Writes a file of the requested length into the folder.
	 *
	 * @param folder folder to write into
	 * @param name   name of the file
	 * @param length how many bytes it should hold
	 * @return the number of bytes written, so the assertion and the fixture cannot disagree
	 */
	private static long write(@Nonnull Path folder, @Nonnull String name, int length) throws IOException {
		final byte[] content = "x".repeat(length).getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve(name), content);
		return content.length;
	}

	/**
	 * Lists the folder the way the measurement's caller does.
	 *
	 * @param folder folder to list
	 * @return the flat listing
	 */
	@Nonnull
	private static File[] listing(@Nonnull Path folder) {
		final File[] files = folder.toFile().listFiles();
		return files == null ? new File[0] : files;
	}

}
