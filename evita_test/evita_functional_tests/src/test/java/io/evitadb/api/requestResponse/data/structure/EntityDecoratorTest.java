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
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.Link;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
	 * Builds a reference stub with the fields used by the filtering predicate.
	 *
	 * @param name reference name
	 * @return the reference stub
	 */
	@Nonnull
	private static ReferenceDecorator stubReference(@Nonnull String name) {
		final ReferenceDecorator mock = Mockito.mock(ReferenceDecorator.class);
		when(mock.exists()).thenReturn(true);
		when(mock.getReferenceName()).thenReturn(name);
		return mock;
	}

	/**
	 * Ranks a predefined set of rows, preserving source order among them.
	 */
	private static final class RankedRowsComparator implements ReferenceComparator {
		private final Set<ReferenceContract> rankedRows;

		/**
		 * Sets the rows that can be ranked.
		 *
		 * @param rankedRows rows to rank
		 */
		RankedRowsComparator(@Nonnull Set<ReferenceContract> rankedRows) {
			this.rankedRows = rankedRows;
		}

		/**
		 * Checks whether the row is in the ranked set.
		 *
		 * @param reference row to classify
		 * @return whether the row can be ranked
		 */
		@Override
		public boolean canRank(@Nonnull ReferenceContract reference) {
			return this.rankedRows.contains(reference);
		}

		/**
		 * Preserves the source order of ranked rows.
		 *
		 * @param first first ranked row
		 * @param second second ranked row
		 * @return zero
		 */
		@Override
		public int compare(@Nonnull ReferenceContract first, @Nonnull ReferenceContract second) {
			return 0;
		}
	}

	/**
	 * Records owner preparation while preserving the order of the rows it ranks.
	 */
	private static final class RecordingOwnerComparator implements ReferenceComparator {
		final List<Integer> ownerKeys = new ArrayList<>();
		final List<Scope> ownerScopes = new ArrayList<>();

		/**
		 * Records the owner passed to this comparator.
		 *
		 * @param ownerPrimaryKey primary key of the owner
		 * @param ownerScope scope of the owner
		 */
		@Override
		public void prepareOwner(int ownerPrimaryKey, @Nonnull Scope ownerScope) {
			this.ownerKeys.add(ownerPrimaryKey);
			this.ownerScopes.add(ownerScope);
		}

		/**
		 * Preserves the source order.
		 *
		 * @param first first row
		 * @param second second row
		 * @return zero
		 */
		@Override
		public int compare(@Nonnull ReferenceContract first, @Nonnull ReferenceContract second) {
			return 0;
		}
	}

	/**
	 * Exercises reference row admission, owner preparation, and fall-through between ordering links.
	 */
	@Nested
	@DisplayName("sortAndFilterSubList")
	@Tag(REFERENCE)
	@Tag(COMPARATOR)
	class SortAndFilterSubListTest {

		/**
		 * Verifies that a fallback comparator is prepared when the head leaves rows unranked.
		 */
		@Test
		@DisplayName("Should prepare a fallback comparator with the owner's key and scope")
		void shouldPrepareFallbackComparatorForOwner() {
			final RecordingOwnerComparator fallback = new RecordingOwnerComparator();
			final ReferenceDecorator ranked = stubReference("any");
			final ReferenceDecorator[] references = {stubReference("any"), ranked, stubReference("any")};
			final Link head = new Link(null, new RankedRowsComparator(Set.of(ranked)));
			head.append(new Link(Scope.ARCHIVED, fallback));

			EntityDecorator.sortAndFilterSubList(
				42, Scope.ARCHIVED, references, new ReferenceContractSerializablePredicate(),
				null, head, 0, references.length, references, 0, references.length
			);

			assertEquals(List.of(42), fallback.ownerKeys);
			assertEquals(List.of(Scope.ARCHIVED), fallback.ownerScopes);
			assertSame(ranked, references[0]);
		}

		/**
		 * Verifies that the head prepares once for an applicable owner and is skipped for another scope.
		 */
		@Test
		@DisplayName("Should prepare the head only for owners in its scope")
		void shouldPrepareHeadOnlyForOwnersInItsScope() {
			final RecordingOwnerComparator headComparator = new RecordingOwnerComparator();
			final RecordingOwnerComparator fallback = new RecordingOwnerComparator();
			final Link head = new Link(Scope.LIVE, headComparator);
			head.append(new Link(null, fallback));
			final ReferenceDecorator[] references = {stubReference("any"), stubReference("any")};

			EntityDecorator.sortAndFilterSubList(
				7, Scope.LIVE, references, new ReferenceContractSerializablePredicate(),
				null, head, 0, references.length, references, 0, references.length
			);
			EntityDecorator.sortAndFilterSubList(
				8, Scope.ARCHIVED, references, new ReferenceContractSerializablePredicate(),
				null, head, 0, references.length, references, 0, references.length
			);

			assertEquals(List.of(7), headComparator.ownerKeys);
			assertEquals(List.of(Scope.LIVE), headComparator.ownerScopes);
			assertEquals(List.of(8), fallback.ownerKeys);
			assertEquals(List.of(Scope.ARCHIVED), fallback.ownerScopes);
		}

		/**
		 * Verifies that reusing the chain recomputes the unranked window for each owner.
		 */
		@Test
		@DisplayName("Should classify each owner's window when the comparator is reused")
		void shouldClassifyEachOwnersWindowWhenComparatorIsReused() {
			final ReferenceDecorator ranked = stubReference("any");
			final ReferenceDecorator unranked = stubReference("any");
			final RecordingOwnerComparator fallback = new RecordingOwnerComparator();
			final Link head = new Link(null, new RankedRowsComparator(Set.of(ranked)));
			head.append(new Link(null, fallback));

			final ReferenceDecorator[] firstWindow = {unranked, stubReference("any"), ranked};
			final ReferenceDecorator[] secondWindow = {unranked, ranked};

			EntityDecorator.sortAndFilterSubList(
				1, Scope.LIVE, firstWindow, new ReferenceContractSerializablePredicate(),
				null, head, 0, firstWindow.length, firstWindow, 0, firstWindow.length
			);
			EntityDecorator.sortAndFilterSubList(
				2, Scope.LIVE, secondWindow, new ReferenceContractSerializablePredicate(),
				null, head, 0, secondWindow.length, secondWindow, 0, secondWindow.length
			);

			assertSame(ranked, firstWindow[0]);
			assertArrayEquals(new ReferenceDecorator[]{ranked, unranked}, secondWindow);
			assertEquals(List.of(1, 2), fallback.ownerKeys);
		}

		/**
		 * Verifies that partitioning preserves row order within both parts of the window.
		 */
		@Test
		@DisplayName("Should preserve source order within each stable partition")
		void shouldPreserveSourceOrderWithinEachStablePartition() {
			final ReferenceDecorator firstRanked = stubReference("any");
			final ReferenceDecorator secondRanked = stubReference("any");
			final ReferenceDecorator firstUnranked = stubReference("any");
			final ReferenceDecorator secondUnranked = stubReference("any");
			final Link head = new Link(null, new RankedRowsComparator(Set.of(firstRanked, secondRanked)));
			head.append(new Link(null, new RecordingOwnerComparator()));

			final ReferenceDecorator[] window = {firstUnranked, firstRanked, secondUnranked, secondRanked};
			EntityDecorator.sortAndFilterSubList(
				1, Scope.LIVE, window, new ReferenceContractSerializablePredicate(),
				null, head, 0, window.length, window, 0, window.length
			);

			assertArrayEquals(
				new ReferenceDecorator[]{firstRanked, secondRanked, firstUnranked, secondUnranked}, window
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
