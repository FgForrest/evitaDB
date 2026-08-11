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

import io.evitadb.api.exception.ConcurrentCatalogMaterializationException;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

		final CatalogFolderReservation reservation = context.allocateFolderFor("products");

		assertEquals(reservation.folderId(), context.folderIdForBinding("products"));
	}

	@Test
	@DisplayName("Prefers the folder a restore allocated even when the bound one has reappeared")
	void shouldPreferTheReservationWhenTheBoundFolderReappears() throws IOException {
		final Path storageDirectory = Files.createTempDirectory("catalogFolderContextTest");
		try {
			// The same disaster-recovery path as above, one step further along: the restore has already unpacked
			// the backup into the folder it allocated, and only then does the vanished folder come back — an
			// operator restoring the directory, a mount returning. Answering with the binding because its folder
			// is present again would register the catalog against the *stale* contents, release the reservation
			// and leave the freshly restored folder to be reclaimed at the next boot: success reported to the
			// client, backup silently discarded.
			//
			// Folder existence is not what distinguishes recovery from restore — a reservation is. Recovery
			// reads a binding and allocates nothing, so it never reaches this branch at all.
			Files.createDirectory(storageDirectory.resolve("products_1"));
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, boundTo("products", "products_1")
			);

			final CatalogFolderReservation reservation = context.allocateFolderFor("products");

			assertEquals(reservation.folderId(), context.folderIdForBinding("products"));
		} finally {
			FileUtils.deleteDirectory(storageDirectory);
		}
	}

	@Test
	@DisplayName("Uses the binding when the folder is present and nothing is being materialised")
	void shouldUseTheBindingWhenTheFolderIsPresentAndNothingIsReserved() throws IOException {
		final Path storageDirectory = Files.createTempDirectory("catalogFolderContextTest");
		try {
			// Recovery proper, and the case the reservation branch must not swallow: the catalog's folder is
			// there and no operation is materialising the name, so the catalog lands back where it already
			// lives rather than anywhere new.
			Files.createDirectory(storageDirectory.resolve("products_1"));
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, boundTo("products", "products_1")
			);

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

	@Nested
	@DisplayName("Exclusive folder claims")
	class ExclusiveClaims {

		@Test
		@DisplayName("Refuses a second allocation while the first is still outstanding")
		void shouldRefuseASecondAllocationWhileTheFirstIsOutstanding(@TempDir Path storageDirectory) {
			// Two operations materialising one name is what used to corrupt silently: the second `put` displaced
			// the first's claim, and the first then read the second's token back out of the map and bound its
			// catalog to a folder somebody else was still writing into, leaving its own data unreferenced.
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, catalogName -> null
			);

			final CatalogFolderReservation first = context.allocateFolderFor("products");

			assertThrows(
				ConcurrentCatalogMaterializationException.class,
				() -> context.allocateFolderFor("products")
			);
			// and the first claim is untouched - the refusal must not have disturbed what it holds. Asserted
			// against the returned token rather than a literal `products_1`, because the generation counter in
			// the fixture is shared across the class and therefore depends on method execution order
			assertEquals(first.folderId(), context.folderIdForBinding("products"));
		}

		@Test
		@DisplayName("Allows the name to be materialised again once the claim is released")
		void shouldAllowAllocationAgainAfterTheClaimIsReleased(@TempDir Path storageDirectory) {
			// The calibration that matters most. Refusing a second allocation is only safe because every claim
			// is released - recovery from a failed create or restore used to work purely by overwrite, so a
			// refusal with no matching release would make this name un-materialisable for the life of the
			// process. This test is what proves the fix did not trade silent corruption for a permanent wedge.
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, catalogName -> null
			);

			final CatalogFolderReservation failed = context.allocateFolderFor("products");
			failed.close();

			final CatalogFolderReservation retry = context.allocateFolderFor("products");
			assertNotEquals(
				failed.folderId(), retry.folderId(),
				"A retry must draw a fresh folder rather than reusing the one the failed attempt left behind!"
			);
			assertEquals(retry.folderId(), context.folderIdForBinding("products"));
		}

		@Test
		@DisplayName("Releasing twice does not give away a claim a later operation holds")
		void shouldNotReleaseALaterClaimWhenClosedTwice(@TempDir Path storageDirectory) {
			// A second `close()` must be inert. Were it not, an operation that released late would evict the
			// claim of whichever operation had since taken the name - reopening the very defect this closes.
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, catalogName -> null
			);

			final CatalogFolderReservation first = context.allocateFolderFor("products");
			first.close();
			final CatalogFolderReservation second = context.allocateFolderFor("products");

			first.close();

			assertTrue(first.isReleased());
			assertEquals(
				second.folderId(), context.folderIdForBinding("products"),
				"The second claim must survive a late double-release of the first!"
			);
		}

		@Test
		@DisplayName("Claims are per name, so unrelated catalogs never block one another")
		void shouldNotRefuseAllocationForADifferentCatalog(@TempDir Path storageDirectory) {
			final CatalogFolderContext context = TestCatalogFolderContexts.onDirectory(
				storageDirectory, catalogName -> null
			);

			final CatalogFolderReservation products = context.allocateFolderFor("products");
			final CatalogFolderReservation orders = context.allocateFolderFor("orders");

			assertNotEquals(products.folderId(), orders.folderId());
			assertEquals(products.folderId(), context.folderIdForBinding("products"));
			assertEquals(orders.folderId(), context.folderIdForBinding("orders"));
		}

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
