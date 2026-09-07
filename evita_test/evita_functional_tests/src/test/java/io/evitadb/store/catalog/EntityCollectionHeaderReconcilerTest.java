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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the classification {@link EntityCollectionHeaderReconciler} makes between the two recorded answers to
 * "which data file does this entity collection live in".
 *
 * The distinction matters because only one of the four shapes is repaired: the one a historical flush could actually
 * leave behind, where a compaction moved the file index while the header record stayed at the same offset. Everything
 * else either needs no repair or is a shape no known write path produces, and answering the latter with a header
 * assembled from two disagreeing sources would be a guess.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@DisplayName("EntityCollectionHeaderReconciler — which copy of the file index wins")
class EntityCollectionHeaderReconcilerTest {
	private static final String CATALOG = "testCatalog";
	private static final String ENTITY_TYPE = "PRODUCT";
	private static final FileLocation LOCATION = new FileLocation(86_725L, 1_780);
	private static final FileLocation OTHER_LOCATION = new FileLocation(90_112L, 1_780);

	@Test
	@DisplayName("passes the stored header through untouched when both copies agree")
	void shouldPassThroughWhenBothCopiesAgree() {
		final EntityCollectionFileHeader storedHeader = header(3, LOCATION);
		assertSame(
			storedHeader,
			EntityCollectionHeaderReconciler.reconcile(CATALOG, reference(3, LOCATION), storedHeader),
			"An agreeing pair is the ordinary case and must not allocate a repaired copy."
		);
	}

	@Test
	@DisplayName("passes the stored header through when the catalog header does not know the collection")
	void shouldPassThroughWhenCatalogHeaderDoesNotKnowTheCollection() {
		final EntityCollectionFileHeader storedHeader = header(3, LOCATION);
		assertSame(
			storedHeader,
			EntityCollectionHeaderReconciler.reconcile(CATALOG, null, storedHeader),
			"With nothing to reconcile against there is no decision to make - a collection the catalog header has " +
				"never heard of is the caller's business, not this one's."
		);
	}

	@Test
	@DisplayName("passes the stored header through when the catalog header recorded no location to compare")
	void shouldPassThroughWhenTheCatalogHeaderRecordedNoLocation() {
		final EntityCollectionFileHeader storedHeader = header(3, LOCATION);
		assertSame(
			storedHeader,
			EntityCollectionHeaderReconciler.reconcile(
				CATALOG, new CollectionFileReference(ENTITY_TYPE, 1, 3, null), storedHeader
			),
			"A reference carrying no location cannot contradict one - absence of evidence is not a disagreement, " +
				"and CollectionFileReference is routinely built without a location."
		);
	}

	@Test
	@DisplayName("takes the file index from the catalog header when only the index diverged")
	void shouldRepairTheFileIndexWhenOnlyTheIndexDiverged() {
		final EntityCollectionFileHeader storedHeader = header(2, LOCATION);
		final EntityCollectionFileHeader reconciled = EntityCollectionHeaderReconciler.reconcile(
			CATALOG, reference(3, LOCATION), storedHeader
		);
		assertEquals(
			3, reconciled.entityTypeFileIndex(),
			"The catalog header is the copy written unconditionally, so it is the one that cannot lag."
		);
		assertEquals(
			LOCATION, reconciled.fileLocation(),
			"The location is left alone: the historical write was skipped precisely because it had not moved."
		);
		assertEquals(
			storedHeader.withEntityTypeFileIndex(3), reconciled,
			"Nothing but the index may change - the catalog header records nothing else to reconcile against."
		);
	}

	@Test
	@DisplayName("refuses when the indexes agree but the locations do not")
	void shouldThrowWhenTheLocationsDivergeUnderTheSameIndex() {
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			() -> EntityCollectionHeaderReconciler.reconcile(
				CATALOG, reference(3, OTHER_LOCATION), header(3, LOCATION)
			),
			"Trusting the stored header here would silently open an older snapshot of the same file. No write path " +
				"produces this pair, so it is an unexpected state and must surface."
		);
		assertTrue(
			error.getPrivateMessage().contains(String.valueOf(OTHER_LOCATION.startingPosition())) &&
				error.getPrivateMessage().contains(String.valueOf(LOCATION.startingPosition())),
			"The message has to name both pairs, or it cannot be acted on: " + error.getPrivateMessage()
		);
	}

	@Test
	@DisplayName("refuses when the indexes and the locations both diverged")
	void shouldThrowWhenTheIndexAndTheLocationBothDiverged() {
		assertThrows(
			GenericEvitaInternalError.class,
			() -> EntityCollectionHeaderReconciler.reconcile(
				CATALOG, reference(3, OTHER_LOCATION), header(2, LOCATION)
			),
			"A pair that disagrees on both belongs to no shape known to be recoverable - repairing it would be a guess."
		);
	}

	/**
	 * Builds the catalog header's copy of where the collection lives.
	 */
	@Nonnull
	private static CollectionFileReference reference(int fileIndex, @Nonnull FileLocation fileLocation) {
		return new CollectionFileReference(ENTITY_TYPE, 1, fileIndex, fileLocation);
	}

	/**
	 * Builds the collection's own copy, carrying enough non-default metadata that a reconciliation which rebuilt more
	 * than the file index would be visible in the equality assertion above.
	 */
	@Nonnull
	private static EntityCollectionFileHeader header(int fileIndex, @Nonnull FileLocation fileLocation) {
		return new EntityCollectionFileHeader(
			7L, fileLocation, Collections.emptyMap(), ENTITY_TYPE, 1, fileIndex,
			40, 40, 12, 5, null, 11, List.of(11, 12), 3, 0.75, 1_700_000_000_000L
		);
	}

}
