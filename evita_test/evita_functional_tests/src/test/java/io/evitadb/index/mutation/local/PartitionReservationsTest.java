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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract of {@link PartitionReservations} on its own: a reserved primary key is taken once and peeked
 * any number of times, reservations of different partitions never meet, a partition vacated twice without an owner
 * entering it in between is a broken ordering, and the reservations of an executor writing as another owner refuse
 * every operation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reservations of vacated partition primary keys")
@Tag(INDEXING)
@Tag(REFERENCE)
class PartitionReservationsTest {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final RepresentativeReferenceKey KEY = categoryKey(1);

	@Test
	@DisplayName("nothing is reserved before a partition is vacated")
	void shouldReserveNothingInitially() {
		final PartitionReservations reservations = new PartitionReservations();

		assertEquals(PartitionReservations.NO_RESERVATION, reservations.peek(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(PartitionReservations.NO_RESERVATION, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertFalse(reservations.isFinishing());
	}

	@Test
	@DisplayName("a reserved primary key is peeked without being consumed and taken exactly once")
	void shouldHandOutReservedPrimaryKeyOnce() {
		final PartitionReservations reservations = new PartitionReservations();

		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 42);

		assertEquals(42, reservations.peek(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(42, reservations.peek(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(42, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(PartitionReservations.NO_RESERVATION, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(PartitionReservations.NO_RESERVATION, reservations.peek(ENTITY_PRODUCT, Scope.LIVE, KEY));
	}

	@Test
	@DisplayName("a partition differing in entity type, scope or representative key has a reservation of its own")
	void shouldKeepReservationsOfDifferentPartitionsApart() {
		final PartitionReservations reservations = new PartitionReservations();
		final RepresentativeReferenceKey otherTarget = categoryKey(2);
		final RepresentativeReferenceKey otherValues = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_CATEGORIES, 1), new Serializable[]{"blue"}
		);

		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 10);
		reservations.reserve(ENTITY_CATEGORY, Scope.LIVE, KEY, 11);
		reservations.reserve(ENTITY_PRODUCT, Scope.ARCHIVED, KEY, 12);
		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, otherTarget, 13);
		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, otherValues, 14);

		assertEquals(10, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertEquals(11, reservations.take(ENTITY_CATEGORY, Scope.LIVE, KEY));
		assertEquals(12, reservations.take(ENTITY_PRODUCT, Scope.ARCHIVED, KEY));
		assertEquals(13, reservations.take(ENTITY_PRODUCT, Scope.LIVE, otherTarget));
		assertEquals(14, reservations.take(ENTITY_PRODUCT, Scope.LIVE, otherValues));
	}

	@Test
	@DisplayName("a partition vacated again while its primary key is still reserved is a broken ordering")
	void shouldRefuseSecondReservationOfSamePartition() {
		final PartitionReservations reservations = new PartitionReservations();
		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 42);

		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class, () -> reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 43)
		);

		assertTrue(error.getPrivateMessage().contains("primary key 42 is still reserved"), error.getPrivateMessage());
		assertEquals(42, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY), "the first reservation must stand");
	}

	@Test
	@DisplayName("clearing drops every reservation and ends the finishing")
	void shouldDropReservationsAndFinishingOnClear() {
		final PartitionReservations reservations = new PartitionReservations();
		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 42);
		reservations.setFinishing(true);
		assertTrue(reservations.isFinishing());

		reservations.clear();

		assertFalse(reservations.isFinishing());
		assertEquals(PartitionReservations.NO_RESERVATION, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 43);
		assertEquals(43, reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
	}

	@Test
	@DisplayName("the reservations of an executor writing as another owner refuse every operation but clearing")
	void shouldRefuseEveryOperationOfRefusingReservations() {
		final PartitionReservations reservations = PartitionReservations.REFUSING;

		assertRefused(() -> reservations.reserve(ENTITY_PRODUCT, Scope.LIVE, KEY, 42));
		assertRefused(() -> reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertRefused(() -> reservations.peek(ENTITY_PRODUCT, Scope.LIVE, KEY));
		assertRefused(reservations::isFinishing);
		assertRefused(() -> reservations.setFinishing(true));
		assertDoesNotThrow(reservations::clear);
		assertRefused(() -> reservations.take(ENTITY_PRODUCT, Scope.LIVE, KEY));
	}

	/**
	 * Asserts that the operation is refused as an operation an executor writing as another owner must not perform.
	 *
	 * @param operation the operation
	 */
	private static void assertRefused(@Nonnull Executable operation) {
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, operation);
		assertTrue(error.getPrivateMessage().contains("must not"), error.getPrivateMessage());
	}

	/**
	 * Creates the representative reference key of a category partition without representative values.
	 *
	 * @param category the referenced category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey categoryKey(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

}
