/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the containment matching performed by {@link IncomingConflictScope}: a conflict key produced
 * by an already committed transaction conflicts with the incoming transaction when the two write scopes
 * overlap along the {@link ConflictKey#parentConflictKey()} ancestry chain, in either direction. Special
 * attention is paid to the commutative (range-constrained delta) probe, which must catch delete/set
 * -vs-delta while letting two deltas of the same key commute.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(TRANSACTION)
class IncomingConflictScopeTest {
	private static final String ENTITY = "Product";
	private static final String CATALOG = "testCatalog";

	@Nonnull
	private static IncomingConflictScope scopeOf(@Nonnull ConflictKey... keys) {
		return IncomingConflictScope.of(Set.of(keys));
	}

	@Nested
	@DisplayName("Absolute (non-commutative) containment")
	class AbsoluteContainment {

		@Test
		@DisplayName("Identical entity keys conflict (equality preserved)")
		void shouldConflictOnIdenticalEntityKeys() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertTrue(scope.conflictsWithAbsolute(new EntityConflictKey(ENTITY, 1)));
		}

		@Test
		@DisplayName("Different entities do not conflict")
		void shouldNotConflictOnSiblingEntities() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertFalse(scope.conflictsWithAbsolute(new EntityConflictKey(ENTITY, 2)));
		}

		@Test
		@DisplayName("Incoming entity contains a committed attribute of that entity")
		void shouldConflictWhenIncomingCoarserContainsCommitted() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertTrue(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 1, "name")));
		}

		@Test
		@DisplayName("Committed entity contains an incoming attribute of that entity")
		void shouldConflictWhenCommittedCoarserContainsIncoming() {
			final IncomingConflictScope scope = scopeOf(new AttributeConflictKey(ENTITY, 1, "name"));
			assertTrue(scope.conflictsWithAbsolute(new EntityConflictKey(ENTITY, 1)));
		}

		@Test
		@DisplayName("Sibling attributes of the same entity do not conflict")
		void shouldNotConflictOnSiblingAttributes() {
			final IncomingConflictScope scope = scopeOf(new AttributeConflictKey(ENTITY, 1, "foo"));
			assertFalse(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 1, "bar")));
		}

		@Test
		@DisplayName("Same attribute on sibling entities does not conflict")
		void shouldNotConflictOnSameAttributeDifferentEntity() {
			final IncomingConflictScope scope = scopeOf(new AttributeConflictKey(ENTITY, 1, "foo"));
			assertFalse(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 2, "foo")));
		}

		@Test
		@DisplayName("Committed absolute on one attribute vs incoming delta on a sibling attribute does not conflict")
		void shouldNotConflictAbsoluteVsIncomingSiblingDelta() {
			// the incoming scope is a delta on `stock`; its ancestor closure contains the absolute
			// `stock` attribute key and the entity key, but NOT the `price` attribute key — so a
			// committed absolute write on `price` must not match the delta's coveredAncestors (guards
			// against a future collapse of the dir-1 probe onto coveredAncestors, which would over-detect)
			final IncomingConflictScope scope = scopeOf(
				new AttributeDeltaConflictKey(ENTITY, 1, new AttributeKey("stock"), 5, IntegerNumberRange.between(0, 100))
			);
			assertFalse(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 1, "price")));
		}

		@Test
		@DisplayName("Committed collection key contains an incoming entity of that collection")
		void shouldConflictCollectionContainsEntity() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertTrue(scope.conflictsWithAbsolute(new CollectionConflictKey(ENTITY)));
		}

		@Test
		@DisplayName("Incoming collection key contains a committed entity of that collection")
		void shouldConflictEntityContainedByIncomingCollection() {
			final IncomingConflictScope scope = scopeOf(new CollectionConflictKey(ENTITY));
			assertTrue(scope.conflictsWithAbsolute(new EntityConflictKey(ENTITY, 1)));
		}
	}

	@Nested
	@DisplayName("Catalog-wide containment")
	class CatalogContainment {

		@Test
		@DisplayName("Incoming catalog key contains every committed key")
		void shouldConflictWhenIncomingSpansCatalog() {
			final IncomingConflictScope scope = scopeOf(new CatalogConflictKey(CATALOG));
			assertTrue(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 7, "x")));
			assertTrue(scope.conflictsWithAbsolute(new EntityConflictKey(ENTITY, 9)));
			assertTrue(scope.conflictsWithCommutative(
				new AttributeDeltaConflictKey(ENTITY, 1, new AttributeKey("stock"), 5, IntegerNumberRange.between(0, 100))
			));
		}

		@Test
		@DisplayName("Committed catalog key contains every incoming key")
		void shouldConflictWhenCommittedSpansCatalog() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertTrue(scope.conflictsWithAbsolute(new CatalogConflictKey(CATALOG)));
		}

		@Test
		@DisplayName("Committed catalog key does not conflict with an empty incoming scope")
		void shouldNotConflictWhenIncomingEmpty() {
			final IncomingConflictScope scope = IncomingConflictScope.of(Set.of());
			assertFalse(scope.conflictsWithAbsolute(new CatalogConflictKey(CATALOG)));
		}
	}

	@Nested
	@DisplayName("Commutative delta containment")
	class CommutativeContainment {

		@Nonnull
		private AttributeDeltaConflictKey delta(int deltaValue) {
			return new AttributeDeltaConflictKey(
				ENTITY, 1, new AttributeKey("stock"), deltaValue, IntegerNumberRange.between(0, 100)
			);
		}

		@Test
		@DisplayName("Committed delta vs incoming absolute set of the same attribute conflicts")
		void shouldConflictCommittedDeltaVsIncomingAbsolute() {
			final IncomingConflictScope scope = scopeOf(new AttributeConflictKey(ENTITY, 1, "stock"));
			assertTrue(scope.conflictsWithCommutative(delta(5)));
		}

		@Test
		@DisplayName("Committed absolute set vs incoming delta of the same attribute conflicts")
		void shouldConflictCommittedAbsoluteVsIncomingDelta() {
			final IncomingConflictScope scope = scopeOf(delta(5));
			assertTrue(scope.conflictsWithAbsolute(new AttributeConflictKey(ENTITY, 1, "stock")));
		}

		@Test
		@DisplayName("Two deltas of the same attribute commute (no conflict)")
		void shouldNotConflictDeltaVsDeltaSameKey() {
			final IncomingConflictScope scope = scopeOf(delta(5));
			assertFalse(scope.conflictsWithCommutative(delta(3)));
		}

		@Test
		@DisplayName("Committed delta vs incoming entity-wide write conflicts")
		void shouldConflictCommittedDeltaVsIncomingEntity() {
			final IncomingConflictScope scope = scopeOf(new EntityConflictKey(ENTITY, 1));
			assertTrue(scope.conflictsWithCommutative(delta(5)));
		}

		@Test
		@DisplayName("Committed delta vs an absolute write on a sibling attribute does not conflict")
		void shouldNotConflictCommittedDeltaVsSiblingAbsolute() {
			final IncomingConflictScope scope = scopeOf(new AttributeConflictKey(ENTITY, 1, "price"));
			assertFalse(scope.conflictsWithCommutative(delta(5)));
		}
	}
}
