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

package io.evitadb.index.attribute;

import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level contract tests for the folded {@link UniqueIndexView} flyweight that answers every read from the shared
 * {@link FilterIndexView} over the {@link AttributeIndex}-owned tree.
 *
 * The view owns no value map / record-id bitmap of its own: it never registers values (the shared filter tree owns the
 * data and {@link AttributeIndex} enforces uniqueness on the filter insert), and its commit hooks are
 * inert. These tests pin both the throw guards and the read delegation, including the null filter-view degenerate path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("UniqueIndex view contracts")
class UniqueIndexViewTest {

	private static final String ENTITY_TYPE = "product";
	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "code", null);
	private static final Class<? extends Serializable> TYPE = String.class;

	/**
	 * Builds a populated shared {@link InvertedIndex} (value→record buckets) using the same normalizer/comparator the
	 * production {@link AttributeIndex} wires in for an owned tree, seeded with three unique values.
	 *
	 * @return a shared tree holding `alpha→1`, `beta→2`, `gamma→3`
	 */
	@Nonnull
	private static InvertedIndex newPopulatedSharedTree() {
		final InvertedIndex shared = new InvertedIndex(
			FilterIndex.getNormalizer(TYPE),
			FilterIndex.getComparator(KEY, TYPE)
		);
		shared.addRecord("alpha", 1);
		shared.addRecord("beta", 2);
		shared.addRecord("gamma", 3);
		return shared;
	}

	/**
	 * Wraps the passed shared tree in a fresh {@link FilterIndexView} over the unique key.
	 *
	 * @param shared the shared tree to wrap
	 * @return a fresh filter view over the shared tree
	 */
	@Nonnull
	private static FilterIndexView newFilterView(@Nonnull InvertedIndex shared) {
		return new FilterIndexView(KEY, shared, null, TYPE);
	}

	/**
	 * Builds a folded {@link UniqueIndexView} referencing the passed filter view directly.
	 *
	 * @param filterView the shared filter view the unique view reads from (may be `null` to exercise the degenerate path)
	 * @return a fresh folded unique view
	 */
	@Nonnull
	private static UniqueIndex newUniqueView(@Nullable FilterIndex filterView) {
		return UniqueIndex.createView(ENTITY_TYPE, KEY, TYPE, filterView);
	}

	@Nested
	@DisplayName("Mutation guards")
	class MutationGuardTest {

		@Test
		@DisplayName("registerUniqueKey throws and does not mutate the shared tree")
		void shouldThrowOnRegisterUniqueKey() {
			final InvertedIndex shared = newPopulatedSharedTree();
			final UniqueIndex view = newUniqueView(newFilterView(shared));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> view.registerUniqueKey("delta", 4),
				"a folded view must never register a value itself!"
			);
			// the throw must be a clean rejection - the shared tree must stay exactly as seeded
			assertTrue(shared.getRecordsEqualTo(getNormalized("delta")).isEmpty(), "register must not touch the shared tree!");
			assertEquals(3, shared.getLength(), "the shared tree bucket count must be unchanged!");
		}

		@Test
		@DisplayName("unregisterUniqueKey throws and does not mutate the shared tree")
		void shouldThrowOnUnregisterUniqueKey() {
			final InvertedIndex shared = newPopulatedSharedTree();
			final UniqueIndex view = newUniqueView(newFilterView(shared));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> view.unregisterUniqueKey("alpha", 1),
				"a folded view must never unregister a value itself!"
			);
			// the value targeted by the rejected call must still be present and the tree untouched
			assertFalse(shared.getRecordsEqualTo(getNormalized("alpha")).isEmpty(), "unregister must not touch the shared tree!");
			assertEquals(3, shared.getLength(), "the shared tree bucket count must be unchanged!");
		}
	}

	@Nested
	@DisplayName("Read delegation")
	class ReadDelegationTest {

		@Test
		@DisplayName("getRecordIdByUniqueValue resolves present values and returns null for absent ones")
		void shouldResolveRecordIdByUniqueValue() {
			final UniqueIndex view = newUniqueView(newFilterView(newPopulatedSharedTree()));

			assertEquals(1, view.getRecordIdByUniqueValue("alpha"), "the owning record of a present value must resolve!");
			assertEquals(2, view.getRecordIdByUniqueValue("beta"));
			assertEquals(3, view.getRecordIdByUniqueValue("gamma"));
			assertNull(view.getRecordIdByUniqueValue("absent"), "an absent value must resolve to null!");
		}

		@Test
		@DisplayName("getRecordIds, size and isEmpty reflect the backing filter view")
		void shouldReflectBackingFilterView() {
			final UniqueIndex view = newUniqueView(newFilterView(newPopulatedSharedTree()));

			assertArrayEquals(new int[]{1, 2, 3}, view.getRecordIds().getArray(), "all owning records must be visible!");
			assertEquals(3, view.size(), "size() must reflect the backing filter view!");
			assertFalse(view.isEmpty(), "a populated view must not be empty!");
		}
	}

	@Nested
	@DisplayName("Null filter-view degenerate path")
	class NullFilterViewTest {

		@Test
		@DisplayName("a view with a null filter view answers reads with empty defaults")
		void shouldAnswerWithEmptyDefaultsWhenFilterViewIsNull() {
			final UniqueIndex view = newUniqueView(null);

			assertNull(view.getRecordIdByUniqueValue("alpha"), "an unresolved filter view must yield no record!");
			assertSame(EmptyBitmap.INSTANCE, view.getRecordIds(), "an unresolved filter view must yield the empty bitmap!");
			assertSame(
				EmptyFormula.INSTANCE, view.getRecordIdsFormula(),
				"an unresolved filter view must yield the empty formula!"
			);
			assertEquals(0, view.size(), "an unresolved filter view must report size 0!");
			assertTrue(view.isEmpty(), "an unresolved filter view must be empty!");
		}
	}

	@Nested
	@DisplayName("Inert producer hooks")
	class InertProducerTest {

		@Test
		@DisplayName("createLayer yields no layer and the backing map is empty")
		void shouldExposeInertCommitHooks() {
			final UniqueIndexView view = (UniqueIndexView) newUniqueView(newFilterView(newPopulatedSharedTree()));

			assertNull(view.createLayer(), "a view must never produce a transactional layer!");
			assertTrue(view.getUniqueValueToRecordId().isEmpty(), "a folded view owns no value map!");
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy typing")
	class SealedTypingTest {

		@Test
		@DisplayName("createView returns a UniqueIndexView that is a UniqueIndex but not an OwnerUniqueIndex")
		void shouldBeUniqueIndexViewButNotOwner() {
			final UniqueIndex view = newUniqueView(newFilterView(newPopulatedSharedTree()));

			assertInstanceOf(UniqueIndexView.class, view, "createView must return a UniqueIndexView!");
			assertInstanceOf(UniqueIndex.class, view, "a view must be a UniqueIndex!");
			assertFalse(
				view instanceof OwnerUniqueIndex,
				"a view must NOT be an OwnerUniqueIndex - it owns no value map!"
			);
		}
	}

	@Nested
	@DisplayName("Filter-view binding (O(Δ) carry-forward)")
	class BindFilterViewTest {

		@Test
		@DisplayName("binding to the SAME filter view carries the view forward by reference")
		void shouldCarryForwardWhenFilterViewUnchanged() {
			final FilterIndex filterView = newFilterView(newPopulatedSharedTree());
			final UniqueIndex view = newUniqueView(filterView);

			assertSame(
				view, view.bindFilterView(filterView),
				"an identity-unchanged filter view must carry the unique view forward by reference!"
			);
		}

		@Test
		@DisplayName("binding to a DIFFERENT filter view yields a fresh view over the new reference")
		void shouldRebuildWhenFilterViewReplaced() {
			final UniqueIndex view = newUniqueView(newFilterView(newPopulatedSharedTree()));
			final FilterIndex replacement = newFilterView(newPopulatedSharedTree());

			final UniqueIndex rebound = view.bindFilterView(replacement);
			assertNotSame(view, rebound, "a replaced filter view must yield a fresh unique view!");
			assertInstanceOf(UniqueIndexView.class, rebound, "the rebound index must still be a folded view!");
			// the fresh view reads from the replacement, proving it rebound rather than kept the old reference
			assertEquals(3, rebound.size(), "the rebound view must read from the replacement filter view!");
		}

		@Test
		@DisplayName("binding a not-yet-bound (null) marker to a real filter view yields a fresh bound view")
		void shouldBindNullMarkerToLiveFilterView() {
			final UniqueIndex marker = newUniqueView(null);
			assertEquals(0, marker.size(), "an unbound marker must read as empty!");

			final UniqueIndex bound = marker.bindFilterView(newFilterView(newPopulatedSharedTree()));
			assertNotSame(marker, bound, "binding a null marker must yield a fresh view!");
			assertEquals(3, bound.size(), "the bound view must read from the live filter view!");
		}
	}

	/**
	 * Normalizes a raw attribute value through the shared filter normalizer so the resulting key matches the bytes the
	 * shared tree stores (the tree is queried by already-normalized keys).
	 *
	 * @param value the raw attribute value
	 * @return the normalized key as stored in the shared tree
	 */
	@Nonnull
	private static Serializable getNormalized(@Nonnull Serializable value) {
		return FilterIndex.getNormalizer(TYPE).apply(value);
	}
}
