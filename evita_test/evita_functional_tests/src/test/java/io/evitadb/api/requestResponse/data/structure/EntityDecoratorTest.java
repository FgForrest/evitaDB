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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.EntityPrimaryKeyAwareComparator;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering the static helpers on `EntityDecorator`. Lives in the same package so
 * package-protected members can be exercised directly without reflection.
 */
@DisplayName("EntityDecorator")
@Tag(CONTRACT)
class EntityDecoratorTest {

	/**
	 * Builds a minimal `ReferenceDecorator` stub whose only behavioural surface is `exists()` and
	 * `getReferenceName()` — the only methods `ReferenceContractSerializablePredicate` calls during
	 * `sortAndFilterSubList`'s in-place filtering phase. The comparator fakes used below never touch the
	 * references themselves (they return 0 / record metadata), so no further stubbing is required.
	 */
	@Nonnull
	private static ReferenceDecorator stubReference(@Nonnull String name) {
		final ReferenceDecorator mock = Mockito.mock(ReferenceDecorator.class);
		when(mock.exists()).thenReturn(true);
		when(mock.getReferenceName()).thenReturn(name);
		return mock;
	}

	/**
	 * Comparator fake that is NOT `EntityPrimaryKeyAwareComparator` and whose only job is to:
	 * - return 0 for every pair (no actual reordering — `Arrays.sort` becomes a no-op);
	 * - simulate a real comparator that accumulates `nonSortedCount` additional unsorted
	 *   references during the just-finished sort pass, so the chain-advance loop in
	 *   `sortAndFilterSubList` sees a positive delta and moves on to the next link.
	 *
	 * The cumulative semantic mirrors concrete comparators such as
	 * `EntityNestedQueryComparator` whose `nonSortedReferences` set only grows. The first
	 * call (the "before sort" snapshot) returns 0; subsequent calls return `nonSortedCount`,
	 * so the per-pass delta equals `nonSortedCount`.
	 */
	private static final class HeadFakeComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -1761005013461764433L;

		@Nullable private final ReferenceComparator next;
		private final int nonSortedCount;
		private int invocationIndex;

		HeadFakeComparator(@Nullable ReferenceComparator next, int nonSortedCount) {
			this.next = next;
			this.nonSortedCount = nonSortedCount;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			// first call mimics the "before sort" snapshot (no accumulator entries yet),
			// subsequent calls mimic the cumulative reading after the sort pass populated
			// the comparator's `nonSortedReferences` set
			final int value = this.invocationIndex == 0 ? 0 : this.nonSortedCount;
			this.invocationIndex++;
			return value;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake whose `getNonSortedReferenceCount()` grows monotonically across calls,
	 * mimicking a real comparator (e.g. `EntityNestedQueryComparator`) that maintains a
	 * lazy-init `nonSortedReferences` set and never resets it between sort passes. Used to
	 * exercise the case where `sortAndFilterSubList` reads the comparator's counter as if it
	 * were a per-pass reading but the implementation keeps an absolute, cumulative value.
	 *
	 * The comparator chains to `next` so the chain-advance loop will iterate at least twice
	 * — that's where the absolute-vs-delta confusion shows up as out-of-range sort indices.
	 */
	private static final class GrowingNonSortedCountComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -8316411802194620181L;

		@Nullable private final ReferenceComparator next;
		private final int[] reportedCountsPerInvocation;
		private int invocationIndex;

		GrowingNonSortedCountComparator(
			@Nullable ReferenceComparator next,
			@Nonnull int[] reportedCountsPerInvocation
		) {
			this.next = next;
			this.reportedCountsPerInvocation = reportedCountsPerInvocation;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			final int index = Math.min(this.invocationIndex, this.reportedCountsPerInvocation.length - 1);
			final int value = this.reportedCountsPerInvocation[index];
			this.invocationIndex++;
			return value;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake whose `getNonSortedReferenceCount()` returns the same value on every call,
	 * representing a well-behaved comparator that does NOT accumulate state across sort passes.
	 * Used as the control case to confirm correctly-behaving comparators are not regressed.
	 */
	private static final class StableNonSortedCountComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -7193620345109248123L;

		@Nullable private final ReferenceComparator next;
		private final int stableCount;

		StableNonSortedCountComparator(@Nullable ReferenceComparator next, int stableCount) {
			this.next = next;
			this.stableCount = stableCount;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return this.stableCount;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator fake that IS `EntityPrimaryKeyAwareComparator` and records every
	 * `setEntityPrimaryKey(int)` invocation so the test can assert whether the caller honored its
	 * EPK-aware contract.
	 */
	private static final class RecordingEpkAwareComparator
		implements ReferenceComparator, EntityPrimaryKeyAwareComparator, Serializable {

		@Serial private static final long serialVersionUID = 5914405464059833314L;

		final List<Integer> setEntityPrimaryKeyCalls = new ArrayList<>();

		@Override
		public void setEntityPrimaryKey(int entityPrimaryKey) {
			this.setEntityPrimaryKeyCalls.add(entityPrimaryKey);
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator other) {
			throw new UnsupportedOperationException("Test fake — chain is hand-wired");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}
	}

	@Nested
	@DisplayName("sortAndFilterSubList")
	@Tag(REFERENCE)
	@Tag(COMPARATOR)
	class SortAndFilterSubListTest {

		/**
		 * Pins the contract that `sortAndFilterSubList` must invoke `setEntityPrimaryKey` on
		 * EVERY `EntityPrimaryKeyAwareComparator` it visits while walking the comparator chain
		 * via `getNextComparator()` — not only the chain head.
		 *
		 * A query like
		 * `orderBy(attributeNatural("plain", ASC), attributeNatural("predecessor_attr", ASC))`
		 * produces exactly the chain shape under test: the plain-attribute comparator lands at
		 * the head and the EPK-aware `ReferencePredecessorComparator` becomes link #1.
		 *
		 * Without this guarantee, a non-head EPK-aware link is left without an entity scope and
		 * either produces an NPE (auto-unboxed `null` primary key) or a silently wrong sort.
		 */
		@Test
		@DisplayName("Should propagate entity primary key to EPK-aware comparator located deeper in the chain")
		void shouldSetEntityPrimaryKeyOnEpkAwareLinkWhenItIsNotHeadOfChain() {
			final RecordingEpkAwareComparator epkAwareLink = new RecordingEpkAwareComparator();
			// head reports two unsorted references so the chain-advance loop hands off to the next link
			final HeadFakeComparator nonEpkAwareHead = new HeadFakeComparator(epkAwareLink, 2);

			final ReferenceDecorator[] references = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			final int entityPrimaryKey = 42;

			EntityDecorator.sortAndFilterSubList(
				entityPrimaryKey,
				references,
				new ReferenceContractSerializablePredicate(),
				null,
				nonEpkAwareHead,
				0,
				references.length
			);

			assertEquals(
				List.of(entityPrimaryKey),
				epkAwareLink.setEntityPrimaryKeyCalls,
				"EPK-aware comparator located as a non-head link in the chain must still receive " +
					"setEntityPrimaryKey(entityPrimaryKey)."
			);
		}

		/**
		 * Companion control: when the EPK-aware comparator IS at the head, `setEntityPrimaryKey`
		 * does get called — confirming the test fixture is wired correctly and isolating the bug to
		 * the non-head case.
		 */
		@Test
		@DisplayName("Should propagate entity primary key to EPK-aware comparator at the chain head")
		void shouldSetEntityPrimaryKeyOnEpkAwareHeadOfChain() {
			final RecordingEpkAwareComparator epkAwareHead = new RecordingEpkAwareComparator();

			final ReferenceDecorator[] references = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any")
			};

			final int entityPrimaryKey = 7;

			EntityDecorator.sortAndFilterSubList(
				entityPrimaryKey,
				references,
				new ReferenceContractSerializablePredicate(),
				null,
				epkAwareHead,
				0,
				references.length
			);

			assertEquals(
				List.of(entityPrimaryKey),
				epkAwareHead.setEntityPrimaryKeyCalls,
				"setEntityPrimaryKey must be invoked exactly once for the source entity when the " +
					"EPK-aware comparator is the chain head."
			);
		}

		/**
		 * `sortAndFilterSubList` reads `referenceComparator.getNonSortedReferenceCount()` against
		 * a per-pass snapshot taken before `Arrays.sort`. Concrete comparators (e.g.
		 * `EntityNestedQueryComparator`) maintain a lazy-init `nonSortedReferences` set that ONLY
		 * grows — it is never reset between sort passes. The chain-advance arithmetic must use
		 * the per-pass delta (and clamp it to the current window) so that reusing the same
		 * comparator instance across multiple `sortAndFilterSubList` invocations — the normal
		 * case when an earlier pre-sort step has already mutated the comparator — cannot drive
		 * `start` out of the valid `[0, sortEnd]` range and trip the next `Arrays.sort` with a
		 * backwards range.
		 */
		@Test
		@DisplayName("Should keep sort window within bounds when comparator non-sorted count accumulates across invocations")
		void shouldKeepSortWindowWithinBoundsWhenComparatorNonSortedCountAccumulatesAcrossInvocations() {
			// growing counts simulate a comparator whose getNonSortedReferenceCount() value keeps
			// climbing across sort passes; the second `sortAndFilterSubList` call sees a snapshot
			// (200) that is far larger than the sort window (3), so the implementation must clamp
			// the per-pass delta to keep `start` inside [0, sortEnd]
			final GrowingNonSortedCountComparator growingHead = new GrowingNonSortedCountComparator(
				new RecordingEpkAwareComparator(),
				new int[]{1, 200}
			);

			final ReferenceDecorator[] firstWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};
			final ReferenceDecorator[] secondWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			// First invocation establishes the comparator's accumulated counter; this call itself
			// must not throw — the per-pass delta is still small (1) so the window stays in range.
			EntityDecorator.sortAndFilterSubList(
				1,
				firstWindow,
				new ReferenceContractSerializablePredicate(),
				null,
				growingHead,
				0,
				firstWindow.length
			);

			// Second invocation reuses the same comparator instance — the absolute counter (200)
			// is now far larger than the sort window (3). The delta-snapshot pattern must clamp
			// the per-pass delta to `sortEnd - start` so the subsequent chain-link sort does not
			// receive a backwards range.
			assertDoesNotThrow(
				() -> EntityDecorator.sortAndFilterSubList(
					1,
					secondWindow,
					new ReferenceContractSerializablePredicate(),
					null,
					growingHead,
					0,
					secondWindow.length
				),
				"Per-pass delta of getNonSortedReferenceCount() must be clamped to the current " +
					"sort window so cumulative comparator counters cannot drive `start` out of " +
					"the [0, sortEnd] range."
			);
		}

		/**
		 * Control: a comparator whose `getNonSortedReferenceCount()` returns the same value on
		 * every call (i.e., it does NOT accumulate across sort passes) must let
		 * `sortAndFilterSubList` complete successfully across multiple invocations. Pinned so a
		 * future tightening of the delta-snapshot math cannot regress the common, well-behaved case.
		 */
		@Test
		@DisplayName("Should complete successfully when comparator non-sorted count is stable across invocations")
		void shouldCompleteSuccessfullyWhenComparatorNonSortedCountIsStableAcrossInvocations() {
			// stable counter (1) is well below the sort window (3) on every call; the math always
			// produces a valid `start` in [0, sortEnd] regardless of how many times the comparator
			// is reused.
			final StableNonSortedCountComparator stableHead = new StableNonSortedCountComparator(
				new RecordingEpkAwareComparator(),
				1
			);

			final ReferenceDecorator[] firstWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};
			final ReferenceDecorator[] secondWindow = new ReferenceDecorator[] {
				stubReference("any"),
				stubReference("any"),
				stubReference("any")
			};

			assertDoesNotThrow(
				() -> {
					EntityDecorator.sortAndFilterSubList(
						1,
						firstWindow,
						new ReferenceContractSerializablePredicate(),
						null,
						stableHead,
						0,
						firstWindow.length
					);
					EntityDecorator.sortAndFilterSubList(
						1,
						secondWindow,
						new ReferenceContractSerializablePredicate(),
						null,
						stableHead,
						0,
						secondWindow.length
					);
				},
				"A comparator that does not accumulate non-sorted count across calls must let " +
					"sortAndFilterSubList complete in the same way on every invocation."
			);
		}
	}

	/**
	 * Covers the four states the decorator's parent slot may hold - unresolved, a body, a bodyless pointer and the
	 * chain terminator - together with the contract of the terminator itself, including the deprecated constant it
	 * replaced.
	 */
	@Nested
	@DisplayName("parent slot")
	@Tag(HIERARCHY)
	class ParentSlotTest {
		private static final String CATEGORY = "category";
		private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

		/**
		 * Builds a hierarchical schema with no attributes, associated data, references or prices - the parent slot is
		 * the only surface these tests touch.
		 *
		 * @return the schema every entity in this nested class is built against
		 */
		@Nonnull
		private static EntitySchema hierarchicalSchema() {
			return EntitySchema._internalBuild(
				1, CATEGORY, null, null, null,
				false,
				true, new Scope[]{Scope.LIVE},
				false, null,
				2,
				Collections.emptySet(), Collections.emptySet(),
				Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
				Collections.emptySet(), Collections.emptyMap()
			);
		}

		/**
		 * Builds a bare {@link Entity} of the hierarchical schema. The `parent` argument is what the delegate fallback
		 * of {@link EntityDecorator#getParentEntity()} would surface when nobody resolved the parent.
		 *
		 * @param schema     the schema the entity belongs to
		 * @param primaryKey the primary key of the built entity
		 * @param parent     the raw parent primary key the delegate carries, or NULL for a hierarchy root
		 * @return the entity to be wrapped by
		 *         {@link #decorate(EntitySchema, Entity, EntityClassifierWithParent, boolean)}
		 */
		@Nonnull
		private static Entity entity(@Nonnull EntitySchema schema, int primaryKey, @Nullable Integer parent) {
			return Entity._internalBuild(
				primaryKey, 1, schema, parent,
				new References(schema),
				new EntityAttributes(schema),
				new AssociatedData(schema),
				new Prices(schema, PriceInnerRecordHandling.NONE),
				Collections.emptySet(),
				Scope.DEFAULT_SCOPE,
				false
			);
		}

		/**
		 * Produces a request stub that asks for nothing but still satisfies the predicates' constructors - only the
		 * array-valued price accessors are dereferenced eagerly and therefore need stubbing.
		 *
		 * @return the request stub every predicate in this nested class is constructed from
		 */
		@Nonnull
		private static EvitaRequest emptyRequest() {
			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			when(evitaRequest.getRequiresPriceLists()).thenReturn(new String[0]);
			when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[0]);
			when(evitaRequest.getAccompanyingPrices()).thenReturn(new AccompanyingPrice[0]);
			return evitaRequest;
		}

		/**
		 * Wraps `delegate` in a decorator whose parent slot holds `parentEntity` and whose hierarchy predicate is
		 * driven by `hierarchyFetched`. Every other predicate is empty, so the decorator exposes identity and
		 * hierarchy and nothing else.
		 *
		 * @param schema           the schema of the decorated entity
		 * @param delegate         the entity to decorate, whose own parent pointer is the fallback under test
		 * @param parentEntity     the raw parent slot - NULL for "nobody resolved it", a {@link SealedEntity} for
		 *                         a resolved body, an {@link EntityReferenceWithParent} for a bodyless pointer, or
		 *                         {@link ParentChainEnd#INSTANCE} for a chain that ends here
		 * @param hierarchyFetched whether the query asked for the hierarchy at all, which is what
		 *                         {@link EntityDecorator#parentAvailable()} reports
		 * @return the decorator under test
		 */
		@Nonnull
		private static EntityDecorator decorate(
			@Nonnull EntitySchema schema,
			@Nonnull Entity delegate,
			@Nullable EntityClassifierWithParent parentEntity,
			boolean hierarchyFetched
		) {
			final EvitaRequest evitaRequest = emptyRequest();
			return new EntityDecorator(
				delegate,
				schema,
				parentEntity,
				new LocaleSerializablePredicate(evitaRequest),
				new HierarchySerializablePredicate(hierarchyFetched),
				new AttributeValueSerializablePredicate(evitaRequest),
				new AssociatedDataValueSerializablePredicate(evitaRequest),
				new ReferenceContractSerializablePredicate(evitaRequest),
				new PriceContractSerializablePredicate(evitaRequest, Boolean.FALSE),
				NOW
			);
		}

		@Test
		@DisplayName("Should fall back to the delegate pointer when nobody resolved the parent")
		void shouldFallBackToDelegateWhenParentWasNotResolved() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(schema, entity(schema, 11, 10), null, true);

			assertTrue(decorator.parentAvailable());
			final EntityClassifierWithParent parent = decorator.getParentEntity().orElseThrow();
			assertEquals(new EntityReferenceWithParent(CATEGORY, 10, null), parent);
		}

		@Test
		@DisplayName("Should expose the resolved parent body")
		void shouldExposeResolvedParentBody() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator parentBody = decorate(
				schema, entity(schema, 10, null), ParentChainEnd.INSTANCE, true);
			final EntityDecorator decorator = decorate(schema, entity(schema, 11, 10), parentBody, true);

			final EntityClassifierWithParent parent = decorator.getParentEntity().orElseThrow();
			assertSame(parentBody, parent);
			assertInstanceOf(SealedEntity.class, parent);
			// the body sits at the top of a resolved chain, so it reports no ancestor of its own
			assertEquals(Optional.empty(), parentBody.getParentEntity());
		}

		@Test
		@DisplayName("Should expose a bodyless parent pointer and let the chain continue above it")
		void shouldExposeBodylessParentPointer() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityReferenceWithParent pointer = new EntityReferenceWithParent(
				CATEGORY, 10, new EntityReferenceWithParent(CATEGORY, 9, null));
			final EntityDecorator decorator = decorate(schema, entity(schema, 11, 10), pointer, true);

			final EntityClassifierWithParent parent = decorator.getParentEntity().orElseThrow();
			assertSame(pointer, parent);
			assertFalse(parent instanceof SealedEntity, "A bodyless pointer must never be reported as a body.");
			assertEquals(9, parent.getParentEntity().orElseThrow().getPrimaryKeyOrThrowException());
		}

		@Test
		@DisplayName("Should report no parent when the resolved chain ends, even though the delegate knows one")
		void shouldReportNoParentWhenChainEnds() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(
				schema, entity(schema, 11, 10), ParentChainEnd.INSTANCE, true);

			// the delegate still carries parent 10 - the terminator is what stops it from leaking out
			assertEquals(10, decorator.getDelegate().getParentEntity().orElseThrow().getPrimaryKeyOrThrowException());
			assertTrue(decorator.parentAvailable());
			assertEquals(Optional.empty(), decorator.getParentEntity());
		}

		@Test
		@DisplayName("Should keep honouring the deprecated concealed entity as a chain end")
		@SuppressWarnings("deprecation")
		void shouldKeepHonouringConcealedEntity() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(
				schema, entity(schema, 11, 10), EntityClassifierWithParent.CONCEALED_ENTITY, true);

			assertTrue(ParentChainEnd.isChainEnd(EntityClassifierWithParent.CONCEALED_ENTITY));
			assertEquals(Optional.empty(), decorator.getParentEntity());
		}

		@Test
		@DisplayName("Should refuse parent access when the hierarchy was not fetched at all")
		void shouldRefuseParentAccessWhenHierarchyWasNotFetched() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(schema, entity(schema, 11, 10), null, false);

			assertFalse(decorator.parentAvailable());
			assertThrows(ContextMissingException.class, decorator::getParentEntity);
		}

		@Test
		@DisplayName("Should recognise the chain end only for the terminator itself")
		void shouldRecogniseChainEndOnlyForTerminator() {
			assertTrue(ParentChainEnd.isChainEnd(ParentChainEnd.INSTANCE));
			assertFalse(ParentChainEnd.isChainEnd(null));
			assertFalse(ParentChainEnd.isChainEnd(new EntityReferenceWithParent(CATEGORY, 10, null)));
		}

		/**
		 * The testing conventions of this project forbid `ObjectOutputStream` round-trip tests, because the classes
		 * that declare a `serialVersionUID` here are persisted through Kryo and never through the Java object stream.
		 * This one is the deliberate exception: identity across a Java serialization round trip is the *only* reason
		 * {@link ParentChainEnd#readResolve()} exists, the deprecation notice on
		 * {@link EntityClassifierWithParent#CONCEALED_ENTITY} rests on it, and there is no Kryo path over the
		 * decorator's parent slot to test in its place.
		 */
		@Test
		@DisplayName("Should survive a serialization round trip as the very same instance")
		void shouldSurviveSerializationRoundTrip() throws IOException, ClassNotFoundException {
			final byte[] serialized;
			try (
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final ObjectOutputStream out = new ObjectOutputStream(bytes)
			) {
				out.writeObject(ParentChainEnd.INSTANCE);
				out.flush();
				serialized = bytes.toByteArray();
			}
			try (final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
				final Object deserialized = in.readObject();
				assertSame(ParentChainEnd.INSTANCE, deserialized);
				assertTrue(ParentChainEnd.isChainEnd((EntityClassifierWithParent) deserialized));
			}
		}

		/**
		 * The deprecated constant is recognised by identity, and it is an anonymous class - so unless it resolves
		 * back to itself, a de-serialized copy is a different object that
		 * {@link ParentChainEnd#isChainEnd(EntityClassifierWithParent)} no longer accepts. A decorator holding that
		 * copy would then expose it as a real parent and every read of it would raise
		 * {@link UnsupportedOperationException}, which is exactly what the compatibility path promises not to do.
		 * The exemption from the no-Java-serialization convention is the one recorded on
		 * {@link #shouldSurviveSerializationRoundTrip()}.
		 */
		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("Should still recognise the deprecated terminator after a serialization round trip")
		void shouldRecogniseTheDeprecatedTerminatorAfterSerializationRoundTrip()
			throws IOException, ClassNotFoundException {
			final byte[] serialized;
			try (
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final ObjectOutputStream out = new ObjectOutputStream(bytes)
			) {
				out.writeObject(EntityClassifierWithParent.CONCEALED_ENTITY);
				out.flush();
				serialized = bytes.toByteArray();
			}
			try (final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
				final Object deserialized = in.readObject();
				assertSame(EntityClassifierWithParent.CONCEALED_ENTITY, deserialized);
				assertTrue(ParentChainEnd.isChainEnd((EntityClassifierWithParent) deserialized));
			}
		}

		@Test
		@DisplayName("Should refuse to be read as an entity classifier")
		void shouldRefuseToBeReadAsEntityClassifier() {
			assertThrows(GenericEvitaInternalError.class, ParentChainEnd.INSTANCE::getType);
			assertThrows(GenericEvitaInternalError.class, ParentChainEnd.INSTANCE::getPrimaryKey);
			assertEquals(Optional.empty(), ParentChainEnd.INSTANCE.getParentEntity());
		}

		/**
		 * The raw accessor hands the slot back exactly as it was written, terminator included, which is what makes it
		 * the right way to carry a resolved chain from one decorator to the next. Reading the same slot through
		 * {@link EntityDecorator#getParentEntity()} interprets it and reports an absent parent, so the two accessors
		 * disagree on purpose and only the raw one can tell a resolved-and-empty chain apart from an unresolved slot.
		 */
		@Test
		@DisplayName("Should hand back the raw chain terminator without interpreting it")
		void shouldExposeTheRawChainTerminator() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(
				schema, entity(schema, 11, 10), ParentChainEnd.INSTANCE, true);

			assertSame(
				ParentChainEnd.INSTANCE,
				decorator.getParentEntityWithoutCheckingPredicate().orElseThrow()
			);
			assertEquals(Optional.empty(), decorator.getParentEntity());
		}

		/**
		 * Re-wrapping a decorator whose chain was already resolved and found empty must not resurrect the delegate's
		 * raw pointer. The re-wrapping constructor is handed a `null` parent whenever no fetcher ran for the new
		 * request, and `null` is also the value that means "nobody resolved the parent" - so the terminator has to
		 * reach the new decorator by being carried across rather than by being re-derived. The source of that carry
		 * is the re-wrapped decorator's own raw slot, never its delegate: the delegate is where the cut ancestor still
		 * lives, and reading it back is exactly the resurrection this guards against.
		 */
		@Test
		@DisplayName("Should keep a resolved chain end when the decorator is re-wrapped without a parent")
		void shouldKeepTheResolvedChainEndOnReWrap() {
			final EntitySchema schema = hierarchicalSchema();
			final EntityDecorator decorator = decorate(
				schema, entity(schema, 11, 10), ParentChainEnd.INSTANCE, true);

			final EntityDecorator reWrapped = new EntityDecorator(
				decorator,
				null,
				decorator.getLocalePredicate(),
				new HierarchySerializablePredicate(true),
				decorator.getAttributePredicate(),
				decorator.getAssociatedDataPredicate(),
				decorator.getReferencePredicate(),
				decorator.getPricePredicate(),
				NOW
			);

			assertSame(
				ParentChainEnd.INSTANCE,
				reWrapped.getParentEntityWithoutCheckingPredicate().orElseThrow(),
				"The terminator must be carried across the re-wrap verbatim."
			);
			assertEquals(Optional.empty(), reWrapped.getParentEntity());
		}
	}
}
