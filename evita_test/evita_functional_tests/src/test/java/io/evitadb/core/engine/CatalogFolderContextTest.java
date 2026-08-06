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

import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers which folder a catalog is bound to when the answer is not simply "the one it is already bound to".
 *
 * `folderIdForBinding` arbitrates between a binding the engine state already holds and a folder an in-flight
 * operation has allocated but not yet committed. The order matters and is not obvious, because the two disagree
 * exactly in the situations that matter most — a catalog whose folder vanished and is being restored from a
 * backup has both a stale binding and a fresh reservation, and picking the wrong one silently leaves the
 * restored data unreferenced.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Resolving the folder a catalog is to be bound to")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogFolderContextTest {

	@Test
	@DisplayName("Prefers the folder a restore allocated when the bound one has vanished")
	void shouldPreferTheReservationWhenTheBoundFolderIsGone(@TempDir Path storageDirectory) {
		// The disaster-recovery path: `products` is registered but its folder is gone, so the engine has it in
		// the missing bucket — with its binding deliberately kept, because that is what a later reappearance is
		// matched against. Restoring a backup over that name allocates a new folder and writes the backup into
		// it. Answering with the binding here would register the catalog against the folder that vanished and
		// leave the restored data unreferenced, which is failure at the exact moment restore matters.
		final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
			storageDirectory, boundTo("products", "products_1")
		);

		final CatalogFolderId allocated = context.allocateFolderFor("products");

		assertEquals(allocated, context.folderIdForBinding("products"));
	}

	@Test
	@DisplayName("Prefers a live binding over any reservation")
	void shouldPreferTheLiveBindingOverAReservation() throws IOException {
		final Path storageDirectory = Files.createTempDirectory("catalogFolderContextTest");
		try {
			// the mirror image: the catalog's folder is present, so it is a recovery rather than a restore, and
			// the catalog must land back where it already lives rather than in a freshly allocated folder
			Files.createDirectory(storageDirectory.resolve("products_1"));
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, boundTo("products", "products_1")
			);

			context.allocateFolderFor("products");

			assertEquals(new CatalogFolderId("products_1"), context.folderIdForBinding("products"));
		} finally {
			FileUtils.deleteDirectory(storageDirectory);
		}
	}

	@Test
	@DisplayName("Falls back to the binding when the folder is gone and nothing was allocated")
	void shouldFallBackToTheBindingWhenNothingIsReserved(@TempDir Path storageDirectory) {
		// Nothing better to answer with, and the caller's own existence check then reports the absence in the
		// terms an operator needs. Answering with the identity token instead would send the load at a directory
		// that has nothing to do with this catalog.
		final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
			storageDirectory, boundTo("products", "products_1")
		);

		assertEquals(new CatalogFolderId("products_1"), context.folderIdForBinding("products"));
	}

	@Test
	@DisplayName("Falls back to the catalog's own name when it is neither bound nor allocated")
	void shouldFallBackToIdentityWhenUnboundAndUnreserved(@TempDir Path storageDirectory) {
		final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
			storageDirectory, catalogName -> null
		);

		assertEquals(new CatalogFolderId("products"), context.folderIdForBinding("products"));
	}

	/**
	 * Builds a resolver binding exactly one catalog name to one folder token, and nothing else.
	 *
	 * @param catalogName name that carries a binding
	 * @param folderName  folder token that name is bound to
	 * @return resolver answering only for that one name
	 */
	@Nonnull
	private static CatalogFolderResolver boundTo(@Nonnull String catalogName, @Nonnull String folderName) {
		return requestedCatalogName ->
			catalogName.equals(requestedCatalogName) ? new CatalogFolderId(folderName) : null;
	}

}
