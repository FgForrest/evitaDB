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

import io.evitadb.api.configuration.MissingHistogramValueBehavior;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.expression.trigger.DefaultHistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart.MissingReferenceBehavior;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breaks the histogram run protocol directly, bypassing the public API's validation and execution ordering.
 * Real storage parts, triggers and indexes isolate the executor's checks without reflection or Mockito.
 * The reference schema is unindexed so scheduling can be tested independently of component-index writes; the cases
 * that need a histogram to read or write build an Evita-backed fixture with real histogram indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference histogram run protocol assertions")
@Tag(INDEXING)
@Tag(HISTOGRAM)
class EntityIndexLocalMutationExecutorHistogramProtocolTest implements EvitaTestSupport {
	/**
	 * The reference used to break the protocol, initially without an internal primary key.
	 */
	private static final ReferenceKey REFERENCE_KEY = new ReferenceKey("brand", 10);
	/**
	 * A real schema with no component-index writes in these protocol-only tests.
	 */
	private static final ReferenceSchema REFERENCE_SCHEMA = ReferenceSchema._internalBuild(
		"brand", "brand", false, Cardinality.ZERO_OR_MORE, null, false, null, null
	);
	/**
	 * The owning entity schema.
	 */
	private static final EntitySchema SCHEMA = EntitySchema._internalBuild(
		1, "product", null, null, null, false, false, null, false, null, 2,
		Set.of(), Set.of(), Map.of(), Map.of(), Map.of("brand", REFERENCE_SCHEMA),
		EnumSet.allOf(EvolutionMode.class), Map.of()
	);
	/**
	 * Real local histogram trigger registry, deliberately independent of component-index configuration.
	 */
	private static final CatalogExpressionTriggerRegistry REGISTRY = CatalogExpressionTriggerRegistry.EMPTY
		.rebuildForEntityType("product", List.of(new DefaultHistogramExpressionTrigger(
			"product", "brand", Scope.LIVE, "shareHistogram",
			new HistogramValueDescriptor(
				HistogramValueSource.REFERENCE_ATTRIBUTE, null, "share", Integer.class, false, false, null, null, 0
			)
		)));
	/**
	 * The share histogram with a default, so a reference contributes the value 1 without a stored `share`.
	 */
	private static final HistogramValueDescriptor DEFAULTED_SHARE = shareDescriptor(Integer.class, false, 1, null);
	/**
	 * In-memory storage parts, which the test deliberately leaves inconsistent with the queued index operations.
	 */
	private final MockStorageContainerAccessor storage = new MockStorageContainerAccessor();
	/**
	 * The global index passed to reference handlers.
	 */
	private final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
		1, "product", new EntityIndexKey(EntityIndexType.GLOBAL)
	);
	/**
	 * The executor whose production entry points the tests drive.
	 */
	private final EntityIndexLocalMutationExecutor executor = createExecutor();

	@Nested
	@DisplayName("Withdrawal before a storage change")
	class WithdrawalTest {

		/**
		 * Withdrawal must not silently accept a reference absent from storage.
		 *
		 * @param internalPrimaryKey the requested reference instance
		 */
		@ParameterizedTest
		@ValueSource(ints = {0, -1, 7})
		@DisplayName("Fail when withdrawal cannot find the reference")
		void shouldThrowExceptionWhenWithdrawalReferenceIsMissing(int internalPrimaryKey) {
			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor
					.withdrawHistogramValuesAndScheduleFinalAdd(
						new ReferenceKey("brand", 10, internalPrimaryKey), Scope.LIVE
					));

			assertProtocolMessage(error, "withdraw", internalPrimaryKey);
		}

		/**
		 * Removes a qualifying reference's contribution through the executor's public index API, then mutates it.
		 * The default value makes the real unconditional trigger contribute even without a stored source attribute.
		 *
		 * @param grouped whether the contribution belongs to a reduced group index
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Fail when withdrawal cannot find a qualifying reference's histogram value")
		void shouldThrowExceptionWhenWithdrawalValueIsMissing(boolean grouped) {
			final TestPaths paths = createTestPaths("withdrawalValueMissing_" + grouped);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final HistogramFixture fixture = createHistogramFixture(evita, grouped, true, DEFAULTED_SHARE);
				final ReferenceKey key = new ReferenceKey("brand", 10, 7);
				fixture.storeReference(key, null, false);
				fixture.histogramIndex().insertHistogramValue("shareHistogram", null, 1, 1, Integer.class, 0);
				fixture.histogramIndex().removeHistogramValue("shareHistogram", null, 1, 1, 0);

				final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
					() -> fixture.executor().updateReferences(new ReferenceAttributeMutation(
						key, new UpsertAttributeMutation("share", 5)
					), EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex));

				assertProtocolMessage(error, "withdraw histogram value", 7);
				final String message = error.getMessage();
				assertAll(
					() -> assertTrue(message.contains("group " + (grouped ? "100" : "null")), message),
					() -> assertTrue(message.contains("shareHistogram"), message),
					() -> assertTrue(message.contains("locale null"), message),
					() -> assertTrue(message.contains("value 1"), message),
					() -> assertTrue(!grouped || message.contains("reduced index primary key 22"), message)
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Removing a reference whose stored value contradicts its histogram descriptor must fail at the withdrawal.
		 * A removal schedules no final add, so no later check could report the value the withdrawal skipped.
		 */
		@Test
		@DisplayName("Fail when a removed reference holds a value of an undeclared kind")
		void shouldThrowExceptionWhenRemovedReferenceValueContradictsDescriptor() {
			final TestPaths paths = createTestPaths("removedReferenceValueShape");
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final HistogramFixture fixture = createHistogramFixture(
					evita, false, true, shareDescriptor(IntegerNumberRange.class, false, null, Integer.class)
				);
				final ReferenceKey key = new ReferenceKey("brand", 10, 7);
				fixture.storeReference(key, 5, false);

				final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
					() -> fixture.executor().updateReferences(
						new RemoveReferenceMutation(key),
						EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex
					));

				final String message = error.getMessage();
				assertAll(
					() -> assertTrue(message.contains("source attribute `share`"), message),
					() -> assertTrue(message.contains(Integer.class.getName()), message)
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	@Nested
	@DisplayName("Shape of the values a histogram withdraws")
	class ValueShapeTest {

		/**
		 * Supplies values whose shape contradicts the descriptor: an array for a single value and vice versa, and a
		 * value or an array element that is not of the declared number or range kind.
		 *
		 * @return the shape description, the descriptor and the contradicting value
		 */
		@Nonnull
		static Stream<Arguments> valuesContradictingDescriptor() {
			final HistogramValueDescriptor number = shareDescriptor(Integer.class, false, null, null);
			final HistogramValueDescriptor numberArray = shareDescriptor(Integer.class, true, null, null);
			final HistogramValueDescriptor range =
				shareDescriptor(IntegerNumberRange.class, false, null, Integer.class);
			final HistogramValueDescriptor rangeArray =
				shareDescriptor(IntegerNumberRange.class, true, null, Integer.class);
			final IntegerNumberRange rangeValue = IntegerNumberRange.between(1, 5);
			return Stream.of(
				Arguments.of("array for a single number", number, new Integer[]{5}),
				Arguments.of("single number for an array", numberArray, 5),
				Arguments.of("text for a number", number, "5"),
				Arguments.of("range for a number", number, rangeValue),
				Arguments.of("number for a range", range, 5),
				Arguments.of("text element of a number array", numberArray, new Serializable[]{5, "6"}),
				Arguments.of("number element of a range array", rangeArray, new Serializable[]{rangeValue, 5})
			);
		}

		/**
		 * A value whose shape contradicts its histogram descriptor must fail rather than resolve to fewer values,
		 * which would let the withdrawal skip the presence assertion for what it dropped.
		 *
		 * @param shape      the contradiction, naming the case
		 * @param descriptor the histogram's value descriptor
		 * @param rawValue   the stored value
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("valuesContradictingDescriptor")
		@DisplayName("Fail when a value contradicts the shape its histogram declares")
		void shouldThrowExceptionWhenValueContradictsDescriptor(
			@Nonnull String shape, @Nonnull HistogramValueDescriptor descriptor, @Nonnull Serializable rawValue
		) {
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceIndexMutator.resolveHistogramValues(rawValue, descriptor)
			);

			assertTrue(error.getMessage().contains("source attribute `share`"), error.getMessage());
		}

		/**
		 * A missing value without a default, a missing value with one and the null elements of an array are
		 * legitimate shapes: they contribute nothing, the default, and the remaining elements.
		 */
		@Test
		@DisplayName("Resolve missing values and null array elements without failing")
		void shouldResolveMissingValuesAndNullArrayElements() {
			final HistogramValueDescriptor number = shareDescriptor(Integer.class, false, null, null);
			final HistogramValueDescriptor numberArray = shareDescriptor(Integer.class, true, null, null);

			assertAll(
				() -> assertArrayEquals(
					new Serializable[0], ReferenceIndexMutator.resolveHistogramValues(null, number)
				),
				() -> assertArrayEquals(
					new Serializable[]{1}, ReferenceIndexMutator.resolveHistogramValues(null, DEFAULTED_SHARE)
				),
				() -> assertArrayEquals(
					new Serializable[]{5, 6},
					ReferenceIndexMutator.resolveHistogramValues(new Integer[]{5, null, 6}, numberArray)
				)
			);
		}
	}

	@Nested
	@DisplayName("Final add at the end of the deferred run")
	class FinalAddTest {

		/**
		 * Scheduling an insertion without writing it to storage must fail at the final add.
		 */
		@Test
		@DisplayName("Fail when an awaiting reference is absent")
		void shouldThrowExceptionWhenAwaitingReferenceIsMissing() {
			scheduleInsert();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor::executeDeferredReEvaluations
			);

			assertProtocolMessage(error, "final histogram add", 0);
		}

		/**
		 * Dropping storage without executing index removal must fail rather than skip the final add.
		 */
		@Test
		@DisplayName("Fail when an awaiting reference was dropped without cancellation")
		void shouldThrowExceptionWhenAwaitingReferenceIsDropped() {
			scheduleInsert();
			storeReference(0, true);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor::executeDeferredReEvaluations
			);

			assertProtocolMessage(error, "final histogram add", 0);
		}

		/**
		 * Renumbering before the drain is a protocol error, never a reason to resolve a different identity.
		 */
		@Test
		@DisplayName("Fail when an unknown insertion is renumbered before its final add")
		void shouldThrowExceptionWhenAwaitingUnknownReferenceWasRenumbered() {
			scheduleInsert();
			storeReference(7, false);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor::executeDeferredReEvaluations
			);

			assertProtocolMessage(error, "final histogram add", 0);
		}

		/**
		 * The final add of a grouped reference must find a reduced index listed for the group, as the withdrawal
		 * must. The group-type index exists but lists nothing for the group, so an add that looped over the listing
		 * would write nothing and report nothing.
		 */
		@Test
		@DisplayName("Fail when the final add finds no reduced index listed for the group")
		void shouldThrowExceptionWhenFinalGroupedAddFindsNoListedReducedIndex() {
			final TestPaths paths = createTestPaths("finalAddWithoutListedGroupIndex");
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final HistogramFixture fixture = createHistogramFixture(evita, true, false, DEFAULTED_SHARE);
				final ReferenceKey key = new ReferenceKey("brand", 10, 7);
				fixture.executor().updateReferences(
					new InsertReferenceMutation(key),
					EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex
				);
				fixture.storeReference(key, null, false);

				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, fixture.executor()::executeDeferredReEvaluations
				);

				final String message = error.getMessage();
				assertAll(
					() -> assertTrue(message.contains("lists no reduced index for group `100`"), message),
					() -> assertTrue(message.contains("reference `brand`"), message),
					() -> assertTrue(message.contains("histogram `shareHistogram`"), message),
					() -> assertTrue(message.contains("owner primary key 1,"), message)
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	@Nested
	@DisplayName("Scheduling and cancelling the final add")
	class SchedulingTest {

		/**
		 * Inserting twice without storage validation must not silently deduplicate the scheduled add.
		 */
		@Test
		@DisplayName("Fail when insertion schedules an already awaiting reference")
		void shouldThrowExceptionWhenInsertionIsAlreadyAwaiting() {
			scheduleInsert();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				EntityIndexLocalMutationExecutorHistogramProtocolTest.this::scheduleInsert
			);

			assertProtocolMessage(error, "already awaiting", 0);
		}

		/**
		 * The control for the cancellation: an inserted reference that stays adds its value at the end of the run,
		 * which proves the fixture observes a final add.
		 *
		 * @param internalPrimaryKey the inserted reference instance
		 */
		@ParameterizedTest
		@ValueSource(ints = {0, -1, 7})
		@DisplayName("Add the value of an inserted reference that stays")
		void shouldAddFinalValueWhenInsertedReferenceStays(int internalPrimaryKey) {
			final TestPaths paths = createTestPaths("keptInsertedReference_" + internalPrimaryKey);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final HistogramFixture fixture = createHistogramFixture(evita, false, true, DEFAULTED_SHARE);
				final ReferenceKey inserted = new ReferenceKey("brand", 10, internalPrimaryKey);
				insertAndMutate(fixture, inserted);

				fixture.executor().executeDeferredReEvaluations();

				assertTrue(fixture.holdsShareOfProduct(1));
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Removal cancels the final add of the inserted identity even when the removal identifies it by an unknown
		 * key, so the run ends without the inserted reference's value. The storage executor rejects removing
		 * unassigned inserts, so this isolates the index protocol directly.
		 *
		 * @param internalPrimaryKey the inserted reference instance
		 */
		@ParameterizedTest
		@ValueSource(ints = {0, -1, 7})
		@DisplayName("Cancel final add of an inserted reference removed through either key form")
		void shouldCancelFinalAddWhenInsertedReferenceIsRemoved(int internalPrimaryKey) {
			final TestPaths paths = createTestPaths("cancelledInsertedReference_" + internalPrimaryKey);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final HistogramFixture fixture = createHistogramFixture(evita, false, true, DEFAULTED_SHARE);
				final ReferenceKey inserted = new ReferenceKey("brand", 10, internalPrimaryKey);
				insertAndMutate(fixture, inserted);
				fixture.executor().updateReferences(
					new RemoveReferenceMutation(REFERENCE_KEY),
					EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex
				);
				fixture.storeReference(inserted, null, true);

				fixture.executor().executeDeferredReEvaluations();

				assertFalse(fixture.holdsShareOfProduct(1));
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Inserts the reference, writes it to storage and mutates its histogram source attribute, which leaves its
		 * scheduled final add in place.
		 *
		 * @param fixture  the Evita-backed fixture
		 * @param inserted the inserted reference instance
		 */
		private void insertAndMutate(@Nonnull HistogramFixture fixture, @Nonnull ReferenceKey inserted) {
			final GlobalEntityIndex theGlobalIndex =
				EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex;
			fixture.executor().updateReferences(new InsertReferenceMutation(inserted), theGlobalIndex);
			fixture.storeReference(inserted, null, false);
			fixture.executor().updateReferences(new ReferenceAttributeMutation(
				inserted, new UpsertAttributeMutation("share", 5)
			), theGlobalIndex);
		}
	}

	@Nested
	@DisplayName("Whole-entity re-indexing and unindexing")
	class WholeEntityTest {

		/**
		 * A synchronous re-index cannot start while another histogram add is scheduled.
		 */
		@Test
		@DisplayName("Fail when scope re-indexing starts with an awaiting reference")
		void shouldThrowExceptionWhenScopeReindexStartsBeforeDrain() {
			scheduleInsert();

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor
					.addEntityToIndexes(new Entity(SCHEMA, 1), Scope.LIVE));

			assertProtocolMessage(error, "indexAllReferences", 0);
		}

		/**
		 * Whole-entity unindexing must follow the deferred drain.
		 */
		@Test
		@DisplayName("Fail when whole-entity unindexing starts with an awaiting reference")
		void shouldThrowExceptionWhenEntityUnindexStartsBeforeDrain() {
			scheduleInsert();

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor
					.removeEntityFromIndexes(new Entity(SCHEMA, 1), Scope.LIVE));

			assertProtocolMessage(error, "unindexReferences", 0);
		}

		/**
		 * Entity removal schedules no final add, so a removed entity's reference that meets one awaiting must fail
		 * rather than cancel it. The stored reference matches the awaiting identity, which is the state a silent
		 * cancellation would accept.
		 */
		@Test
		@DisplayName("Fail when entity removal meets an awaiting reference")
		void shouldThrowExceptionWhenEntityRemovalMeetsAwaitingReference() {
			scheduleInsert();
			storeReference(0, false);
			EntityIndexLocalMutationExecutorHistogramProtocolTest.this.storage
				.getEntityStoragePart("product", 1, EntityExistence.MUST_EXIST).markForRemoval();

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorHistogramProtocolTest.this.executor.updateReferences(
					new RemoveReferenceMutation(REFERENCE_KEY),
					EntityIndexLocalMutationExecutorHistogramProtocolTest.this.globalIndex
				));

			assertProtocolMessage(error, "entity removal", 0);
		}
	}

	/**
	 * Sends insertion to the index executor without the normal following storage write.
	 */
	private void scheduleInsert() {
		this.executor.updateReferences(new InsertReferenceMutation(REFERENCE_KEY), this.globalIndex);
	}

	/**
	 * Writes a reference with the specified identity and existence independently of index execution.
	 *
	 * @param internalPrimaryKey the stored reference instance
	 * @param dropped whether the reference no longer exists
	 */
	private void storeReference(int internalPrimaryKey, boolean dropped) {
		final ReferenceKey key = new ReferenceKey("brand", 10, internalPrimaryKey);
		this.storage.getReferencesStoragePart("product", 1).replaceOrAddReference(
			key, ignored -> new Reference(SCHEMA, REFERENCE_SCHEMA, 1, key, null, Map.of(), dropped),
			() -> MissingReferenceBehavior.ACCEPT_INTERNAL_KEY
		);
	}

	/**
	 * Checks the failed rule and all identifiers required to locate the broken entity and reference, using the
	 * fragments every protocol message renders the same way.
	 *
	 * @param error the assertion failure
	 * @param rule the failed operation
	 * @param internalPrimaryKey the recorded reference instance
	 */
	private static void assertProtocolMessage(
		@Nonnull GenericEvitaInternalError error, @Nonnull String rule, int internalPrimaryKey
	) {
		final String message = error.getMessage();
		final String referencePrefix = "owner primary key 1, reference ";
		final int referenceStart = message.indexOf(referencePrefix);
		final int referenceEnd = message.indexOf(" (internal primary key ", Math.max(referenceStart, 0));
		final String reference = referenceStart < 0 || referenceEnd < 0 ?
			"" : message.substring(referenceStart + referencePrefix.length(), referenceEnd);
		assertAll(
			() -> assertTrue(message.contains(rule), message),
			() -> assertTrue(message.contains("entity type `product`"), message),
			() -> assertTrue(referenceStart >= 0, message),
			() -> assertTrue(reference.contains("brand") && reference.contains("10"), message),
			() -> assertTrue(message.contains("(internal primary key " + internalPrimaryKey + ")"), message),
			() -> assertTrue(message.contains("scope LIVE"), message)
		);
	}

	/**
	 * Creates the share descriptor of the trigger the Evita-backed fixtures register.
	 *
	 * @param plainType        the declared value type
	 * @param arrayType        TRUE when the source attribute is an array
	 * @param defaultValue     the value used when the attribute is missing, or `null`
	 * @param innerNumericType the number type of a range-typed source, or `null` for a number source
	 * @return the descriptor
	 */
	@Nonnull
	private static HistogramValueDescriptor shareDescriptor(
		@Nonnull Class<? extends Serializable> plainType,
		boolean arrayType,
		@Nullable Integer defaultValue,
		@Nullable Class<? extends Number> innerNumericType
	) {
		return new HistogramValueDescriptor(
			HistogramValueSource.REFERENCE_ATTRIBUTE, null, "share", plainType, arrayType, false, defaultValue,
			innerNumericType, 0
		);
	}

	/**
	 * Builds an executor over a real storage executor of a stored product 1 and real histogram indexes: the ungrouped
	 * referenced-type index, or the group-type index and the reduced index of group 100. The single trigger has no
	 * condition, so every stored reference qualifies.
	 *
	 * @param evita       the instance to create the catalog in
	 * @param grouped     TRUE when the histogram values belong to the reduced index of group 100
	 * @param groupListed TRUE when the group-type index lists that reduced index for group 100
	 * @param descriptor  the value descriptor of the trigger
	 * @return the fixture
	 */
	@Nonnull
	private HistogramFixture createHistogramFixture(
		@Nonnull Evita evita,
		boolean grouped,
		boolean groupListed,
		@Nonnull HistogramValueDescriptor descriptor
	) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(session);
			session.createNewEntity("product", 1).upsertVia(session);
		});
		final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final EntityCollection collection = catalog.getCollectionForEntityInternal("product").orElseThrow();
		final EntitySchema valueSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalog.getInternalSchema(), SCHEMA
		).withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE,
			ref -> ref.withAttribute("share", Integer.class)).toInstance();
		final WarmUpDataStoreMemoryBuffer buffer = new WarmUpDataStoreMemoryBuffer(
			collection.getStoragePartPersistenceService()
		);
		final ContainerizedLocalMutationExecutor storageExecutor = new ContainerizedLocalMutationExecutor(
			buffer, collection.getDataStoreReader(), catalog.getVersion(), 1, EntityExistence.MUST_EXIST,
			catalog::getInternalSchema, () -> valueSchema, entityType -> null,
			(entityType, primaryKey) -> null, () -> 1, false
		);
		final EntityIndexKey typeKey = new EntityIndexKey(
			grouped ? EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE : EntityIndexType.REFERENCED_ENTITY_TYPE,
			Scope.LIVE, "brand"
		);
		final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(21, "product", typeKey);
		final EntityIndex target = grouped ? new ReducedGroupEntityIndex(
			22, "product", new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey("brand", 100))
			)
		) : typeIndex;
		final MockEntityIndexCreator<EntityIndexKey, EntityIndex> indexes = new MockEntityIndexCreator<>(target);
		indexes.register(typeKey, typeIndex);
		if (grouped && groupListed) {
			typeIndex.insertPrimaryKeyIfMissing(22, 100);
		}
		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY
			.rebuildForEntityType("product", List.of(new DefaultHistogramExpressionTrigger(
				"product", "brand", Scope.LIVE, "shareHistogram", descriptor
			)));
		return new HistogramFixture(
			createExecutor(indexes, registry, storageExecutor, valueSchema), storageExecutor, valueSchema,
			grouped, (HistogramCapableEntityIndex) target
		);
	}

	/**
	 * Builds the executor with in-memory collaborators and real histogram triggers.
	 *
	 * @return the executor for this test's storage and global index
	 */
	@Nonnull
	private EntityIndexLocalMutationExecutor createExecutor() {
		return createExecutor(new MockEntityIndexCreator<>(this.globalIndex), REGISTRY, this.storage, SCHEMA);
	}

	/**
	 * Builds an executor over the supplied real histogram indexes and trigger registry.
	 *
	 * @param indexes the index lookup used by the executor
	 * @param registry the real histogram triggers
	 * @param storageAccessor the storage executor or protocol fixture's accessor
	 * @param schema the owning schema including the value source attribute
	 * @return the executor
	 */
	@Nonnull
	private EntityIndexLocalMutationExecutor createExecutor(
		@Nonnull MockEntityIndexCreator<EntityIndexKey, EntityIndex> indexes,
		@Nonnull CatalogExpressionTriggerRegistry registry,
		@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
		@Nonnull EntitySchema schema
	) {
		final AtomicInteger sequencer = new AtomicInteger(1);
		return new EntityIndexLocalMutationExecutor(
			storageAccessor, 1, indexes,
			new MockEntityIndexCreator<>(new CatalogIndex(Scope.LIVE)), () -> schema, sequencer::getAndIncrement,
			() -> new Entity(schema, 1), () -> registry, null, null,
			new EntityTypeClassifierResolver() {
				/** Resolves the sole entity type in the fixture. */
				@Override
				public int toEntityTypePrimaryKey(@Nonnull String entityType) {
					return 1;
				}

				/** Resolves the sole entity type in the fixture. */
				@Nonnull
				@Override
				public String toEntityTypeName(int entityTypePrimaryKey) {
					return "product";
				}
			},
			new SchemaCapabilityUsageRegistry(), new SchemaCapabilityUsageRegistry(), true,
			MissingHistogramValueBehavior.REFUSE, new PartitionReservations()
		);
	}

	/**
	 * The executor of an Evita-backed fixture together with the storage it reads and the index its histogram lives in.
	 *
	 * @param executor       the index executor under test
	 * @param storage        the real storage executor of product 1
	 * @param schema         the product schema whose reference carries the `share` attribute
	 * @param grouped        TRUE when the references are stored in group 100
	 * @param histogramIndex the index holding the share histogram values
	 */
	private record HistogramFixture(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ContainerizedLocalMutationExecutor storage,
		@Nonnull EntitySchema schema,
		boolean grouped,
		@Nonnull HistogramCapableEntityIndex histogramIndex
	) {

		/**
		 * Writes the reference to storage independently of index execution, in group 100 when the fixture is grouped.
		 *
		 * @param key     the stored reference, including its internal primary key
		 * @param share   the stored `share` attribute, or `null` for none
		 * @param dropped whether the reference no longer exists
		 */
		void storeReference(@Nonnull ReferenceKey key, @Nullable Integer share, boolean dropped) {
			final Map<AttributeKey, AttributeValue> attributes = share == null ?
				Map.of() : Map.of(new AttributeKey("share"), new AttributeValue(new AttributeKey("share"), share));
			this.storage.getReferencesStoragePart("product", 1).replaceOrAddReference(
				key, ignored -> new Reference(
					this.schema, this.schema.getReferenceOrThrowException("brand"), 1, key,
					this.grouped ? new GroupEntityReference("brandGroup", 100, 1, false) : null, attributes, dropped
				), () -> MissingReferenceBehavior.ACCEPT_INTERNAL_KEY
			);
		}

		/**
		 * Returns whether the share histogram holds the value for product 1, reading the bucket the way the
		 * withdrawal's presence check does.
		 *
		 * @param value the share value
		 * @return TRUE when the histogram holds the value for product 1
		 */
		boolean holdsShareOfProduct(int value) {
			final HistogramIndex histogram = this.histogramIndex.getHistogramIndex("shareHistogram");
			if (histogram == null) {
				return false;
			}
			final FilterIndex filterIndex = histogram.getFilterIndex(null);
			return filterIndex != null &&
				filterIndex.getInvertedIndex().getRecordsEqualTo(histogram.normalizeValue(value)).contains(1);
		}
	}
}
