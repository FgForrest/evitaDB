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

package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.attribute.comparator.PickFirstReferenceAttributeComparator;
import io.evitadb.core.query.sort.attribute.comparator.PickFirstReferenceCompoundAttributeComparator;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_ORDER;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.COMPOUND;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.OWNERS;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_NAME;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.REFERENCE_SCHEMA;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.SCHEMA;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises prefetch pickers directly for scoped hierarchy bounds, including owners without any row inside the
 * bound. Scoped filter queries currently cannot prefetch, so these checks supply real reference rows to the same
 * comparators that the query planner uses, with the resolved bound of the live scope only. The resolver uses the
 * real planning context of {@link PickFirstReducedIndexFixture}; its existing catalog stub supplies no data to
 * these checks, which read only the real owners, reference rows and hierarchy bound.
 */
@DisplayName("Hierarchy-bounded pick-first prefetch row selection")
@Tag(ENGINE)
@Tag(ORDER)
class PickFirstHierarchyBoundPrefetchTest {
	/**
	 * Creates the scalar or compound comparator with a live hierarchy bound admitting target 2 only.
	 *
	 * @param compound whether to order by the compound
	 * @return the comparator
	 */
	@Nonnull
	private static EntityComparator comparator(boolean compound) {
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		final PickFirstReducedIndexResolver resolver = new PickFirstReducedIndexResolver(
			fixture.queryContext, REFERENCE_SCHEMA, Scope.values(), null, false,
			Map.of(Scope.LIVE, new BaseBitmap(2)), () -> new ReferencePartition[0]
		);
		return compound ? new PickFirstReferenceCompoundAttributeComparator(
			REFERENCE_SCHEMA.getSortableAttributeCompound(COMPOUND).orElseThrow(), REFERENCE_SCHEMA,
			null, attributeName -> REFERENCE_SCHEMA.getAttribute(attributeName).orElseThrow(),
			OrderDirection.ASC, resolver
		) : new PickFirstReferenceAttributeComparator(
			ATTRIBUTE_ORDER, Integer.class, 0, REFERENCE_SCHEMA, null, OrderDirection.ASC, resolver
		);
	}

	/**
	 * Builds a real owner from the fixture rows in the requested scope without changing the shared fixture.
	 * Applies the builder's mutation because its direct instance conversion does not retain the requested scope.
	 *
	 * @param primaryKey the fixture owner
	 * @param scope      the owner's scope
	 * @return the scoped owner
	 */
	@Nonnull
	private static EntityContract scopedOwner(int primaryKey, @Nonnull Scope scope) {
		final InitialEntityBuilder builder = new InitialEntityBuilder(
			SCHEMA, primaryKey, null, List.of(), List.of(), OWNERS.get(primaryKey).getReferences(REFERENCE_NAME),
			null, List.of()
		);
		builder.setScope(scope);
		return builder.toMutation().orElseThrow().mutate(SCHEMA, null);
	}

	/**
	 * Verifies that rows outside the live bound do not represent live owners and remain available to archived owners.
	 */
	@ParameterizedTest(name = "compound: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should apply a live hierarchy bound only to live prefetch rows")
	void shouldApplyHierarchyBoundOnlyToLiveRows(boolean compound) {
		final EntityComparator comparator = comparator(compound);
		comparator.prepareFor(3);
		comparator.prepareForSelection(new BaseBitmap(11, 12, 13));
		final EntityContract outside = scopedOwner(11, Scope.LIVE);
		final EntityContract inside = scopedOwner(12, Scope.LIVE);
		final EntityContract archived = scopedOwner(13, Scope.ARCHIVED);
		assertAll(
			() -> assertFalse(comparator.canSort(outside)),
			() -> assertTrue(comparator.canSort(inside)),
			() -> assertTrue(comparator.canSort(archived)),
			() -> assertTrue(comparator.compare(inside, archived) < 0)
		);
	}

	/**
	 * Verifies actual sorter handoff when an owner has no row to any bound target, for scalar and compound values.
	 */
	@ParameterizedTest(name = "compound: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should pass prefetch owners without an inside row to the next sorter")
	void shouldPassOwnersWithoutInsideRowToNextSorter(boolean compound) {
		final PrefetchedOwnersFixture.SortResult result = PrefetchedOwnersFixture.sort(
			comparator(compound), null, 0, 3, 11, 12, 14
		);
		assertAll(
			() -> assertArrayEquals(new int[]{14, 12}, result.written()),
			() -> assertArrayEquals(new int[]{11}, result.passedOn())
		);
	}
}
