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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.IndexWriteTarget;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a write directed at a {@link VirtualReferencePartition} leaves the registry of single-owner partitions
 * exactly as the same write directed at the partition's {@link ReducedEntityIndex} leaves it through the mirror, and
 * that it reaches no index object while doing so.
 *
 * Two arms replay one history of a single-owner partition step by step - the population of every attribute and
 * compound suite, upserts, a delta, removals and the tear-down of the suites - through the entry points of
 * {@link AttributeIndexMutator}. The object arm hands them the partition's index object, whose writes the registry
 * mirrors; the virtual arm hands them the partition's virtual handle, whose writes the registry alone receives. After
 * every step the row of the partition in both registries must be identical. The history covers every sort type the
 * registry keeps - plain values with and without decimal places, a localized value, a range, a localized compound,
 * a compound of a range and a decimal, a `Predecessor` chain and a `ReferencedEntityPredecessor` chain - and runs in
 * both scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Index write target - virtual partition mirror equivalence")
@Tag(INDEXING)
@Tag(REFERENCE)
class IndexWriteTargetMirrorEquivalenceTest extends AbstractMutatorTestBase {
	private static final String REFERENCE_NAME = "virtualTargetRef";
	/**
	 * A reference indexed for partitioning - its partitions are never virtual.
	 */
	private static final String PARTITIONING_REFERENCE_NAME = "partitioningTargetRef";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_PRICE = "price";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_SPAN = "span";
	private static final String ATTRIBUTE_PRODUCT_ORDER = "productOrder";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String ATTRIBUTE_FILTERED = "filtered";
	private static final String COMPOUND_LABEL_AND_PRICE = "labelAndPrice";
	private static final String COMPOUND_SPAN_PRICE_ORDER = "spanPriceOrder";
	private static final Locale CZECH = DataGenerator.CZECH_LOCALE;
	/**
	 * The primary key of the owner every executor writes as.
	 */
	private static final int OWNER_PK = 1;
	/**
	 * The primary key of the entity the partition references.
	 */
	private static final int TARGET_PK = 7;
	/**
	 * The primary key of the partition, the same in both arms.
	 */
	private static final int PARTITION_PK = 21;

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schemaBuilder) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schemaBuilder) {
		final Scope[] bothScopes = {Scope.LIVE, Scope.ARCHIVED};
		schemaBuilder
			.withLocale(Locale.ENGLISH, CZECH)
			.withReferenceTo(
				PARTITIONING_REFERENCE_NAME, PARTITIONING_REFERENCE_NAME, Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs.indexedForFilteringAndPartitioning()
			)
			.withReferenceTo(
				REFERENCE_NAME, REFERENCE_NAME, Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs
					.indexedForFilteringInScope(bothScopes)
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class,
						attr -> attr.filterableInScope(bothScopes).sortableInScope(bothScopes).nullable()
					)
					.withAttribute(
						ATTRIBUTE_PRICE, BigDecimal.class,
						attr -> attr.sortableInScope(bothScopes).indexDecimalPlaces(2).nullable()
					)
					.withAttribute(
						ATTRIBUTE_LABEL, String.class, attr -> attr.localized().sortableInScope(bothScopes).nullable()
					)
					.withAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.class,
						attr -> attr.sortableInScope(bothScopes).indexDecimalPlaces(2).nullable()
					)
					.withAttribute(
						ATTRIBUTE_PRODUCT_ORDER, Predecessor.class, attr -> attr.sortableInScope(bothScopes).nullable()
					)
					.withAttribute(
						ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class,
						attr -> attr.sortableInScope(bothScopes).nullable()
					)
					.withAttribute(
						ATTRIBUTE_FILTERED, Integer.class, attr -> attr.filterableInScope(bothScopes).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_PRICE,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						},
						whichIs -> whichIs.indexedInScope(bothScopes)
					)
					.withSortableAttributeCompound(
						COMPOUND_SPAN_PRICE_ORDER,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_SPAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						},
						whichIs -> whichIs.indexedInScope(bothScopes)
					)
			);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = Scope.class, names = {"LIVE", "ARCHIVED"})
	@DisplayName("the virtual handle leaves the registry as the mirror of the index object does, after every step")
	void shouldLeaveRegistryAsMirrorOfIndexObjectAfterEveryStep(@Nonnull Scope scope) {
		final Arm objectArm = new Arm(scope, true, MutationHandle.MAINTAINED);
		final Arm virtualArm = new Arm(scope, false, MutationHandle.MAINTAINED);
		final int objectsBefore = virtualArm.maintainer.getObjectCount();

		final List<Map<String, String>> objectSnapshots = new ArrayList<>(32);
		boolean objectWritten = false;
		for (final Step step : history()) {
			step.apply(objectArm);
			step.apply(virtualArm);
			final Map<String, String> objectSnapshot = objectArm.snapshot();
			final Map<String, String> virtualSnapshot = virtualArm.snapshot();
			assertEquals(objectSnapshot, virtualSnapshot, "The registries differ after `" + step.name() + "`");
			objectSnapshots.add(objectSnapshot);
			objectWritten |= !objectArm.partitionObject().getSortIndexes().isEmpty();
		}

		// the history must have exercised every kind of key the registry keeps, or the equality above proves little
		final Set<String> everyKey = objectSnapshots.stream()
			.flatMap(it -> it.keySet().stream())
			.collect(Collectors.toSet());
		for (final String expected : List.of(
			"sort " + ATTRIBUTE_ORDER, "sort " + ATTRIBUTE_PRICE, "sort " + ATTRIBUTE_LABEL + "/" + Locale.ENGLISH,
			"sort " + ATTRIBUTE_LABEL + "/" + CZECH, "sort " + ATTRIBUTE_SPAN,
			"sort " + COMPOUND_LABEL_AND_PRICE + "/" + Locale.ENGLISH, "sort " + COMPOUND_LABEL_AND_PRICE + "/" + CZECH,
			"sort " + COMPOUND_SPAN_PRICE_ORDER, "chain " + ATTRIBUTE_PRODUCT_ORDER, "chain " + ATTRIBUTE_CATEGORY_ORDER
		)) {
			assertTrue(everyKey.contains(expected), "The history never mirrored `" + expected + "`: " + everyKey);
		}
		assertFalse(
			everyKey.stream().anyMatch(it -> it.contains(ATTRIBUTE_FILTERED)),
			"An attribute that is not sortable must not be mirrored: " + everyKey
		);
		// and every step must have changed something, so that no step compared two untouched registries
		for (int i = 1; i < objectSnapshots.size(); i++) {
			assertNotEquals(
				objectSnapshots.get(i - 1), objectSnapshots.get(i), "Step " + i + " left the registry unchanged"
			);
		}

		// the object arm did write the index object, the virtual arm reached no partition object at all
		assertTrue(objectWritten, "The object arm must have written its index object");
		assertEquals(objectsBefore, virtualArm.maintainer.getObjectCount(), "The virtual arm created an index object");
		assertTrue(
			virtualArm.maintainer.getTouchedKeys().stream()
				.noneMatch(it -> it.type() == EntityIndexType.REFERENCED_ENTITY),
			"The virtual arm reached a partition object: " + virtualArm.maintainer.getTouchedKeys()
		);
		assertEquals(0, virtualArm.maintainer.getPrimaryKeyAcquisitions(), "The virtual arm resolved an index by pk");
		assertTrue(
			virtualArm.maintainer.getTouchedKeys().contains(virtualArm.typeIndex.getIndexKey()),
			"The virtual arm must have written into the registry of the referenced-type entity index"
		);
	}

	@Nested
	@DisplayName("the locale dispatch of the executor")
	class LocaleDispatch {

		@Test
		@DisplayName("hands a virtual partition to the suite of its reference's compounds, reaching no index object")
		void shouldHandVirtualPartitionToCompoundSuiteOfItsReference() {
			final Arm arm = new Arm(Scope.LIVE, false, MutationHandle.MAINTAINED);
			// a partition of a reference indexed for partitioning is never virtual - the suite refuses it before it
			// reads anything, which shows the dispatch handed the virtual handle to the suite rather than elsewhere
			final RepresentativeReferenceKey representativeReferenceKey =
				new RepresentativeReferenceKey(new ReferenceKey(PARTITIONING_REFERENCE_NAME, TARGET_PK));
			final VirtualReferencePartition partition = new VirtualReferencePartition(
				PARTITION_PK + 1, ReferencePartitions.createKey(Scope.LIVE, representativeReferenceKey),
				representativeReferenceKey, OWNER_PK, arm.typeIndex
			);
			final int objectsBefore = arm.maintainer.getObjectCount();
			for (final boolean insert : new boolean[]{true, false}) {
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class,
					() -> {
						if (insert) {
							arm.executor.upsertEntityAttributeLocaleInTargetIndex(
								Locale.ENGLISH, IndexWriteTargetMirrorEquivalenceTest.this.productSchema, partition,
								arm.executor.getStoragePartExistingDataFactory()
							);
						} else {
							arm.executor.removeEntityAttributeLocaleInTargetIndex(
								Locale.ENGLISH, IndexWriteTargetMirrorEquivalenceTest.this.productSchema, partition,
								arm.executor.getStoragePartExistingDataFactory()
							);
						}
					}
				);
				assertTrue(error.getPrivateMessage().contains("that may be virtual"), error.getPrivateMessage());
			}
			assertEquals(objectsBefore, arm.maintainer.getObjectCount(), "The dispatch created an index object");
			assertTrue(
				arm.maintainer.getTouchedKeys().isEmpty(),
				"The dispatch reached an index: " + arm.maintainer.getTouchedKeys()
			);
		}
	}

	@Nested
	@DisplayName("a write the registry would not take")
	class LostWrite {

		@Test
		@DisplayName("fails for a virtual partition without a row")
		void shouldFailWhenVirtualPartitionHasNoRow() {
			final Arm arm = new Arm(Scope.LIVE, false, MutationHandle.MAINTAINED);
			arm.registry().removePartition(PARTITION_PK);
			assertLost(arm, "holds no row");
		}

		@Test
		@DisplayName("fails for a virtual partition whose row names another owner")
		void shouldFailWhenVirtualPartitionRowNamesAnotherOwner() {
			final Arm arm = new Arm(Scope.LIVE, false, MutationHandle.MAINTAINED);
			arm.registry().removePartition(PARTITION_PK);
			VirtualReferencePartitionWriter.partitionEntered(
				arm.registry(), PARTITION_PK, OWNER_PK + 1, arm.representativeReferenceKey
			);
			assertLost(arm, "holds no row");
		}

		@Test
		@DisplayName("fails for a virtual partition written by a mutation that does not maintain the registry")
		void shouldFailWhenMutationDoesNotMaintainRegistry() {
			assertLost(new Arm(Scope.LIVE, false, MutationHandle.OFF), "does not maintain");
		}

		@Test
		@DisplayName("fails for an owner entity attribute written into a virtual partition")
		void shouldFailWhenEntityAttributeIsWrittenIntoVirtualPartition() {
			final Arm arm = new Arm(Scope.LIVE, false, MutationHandle.MAINTAINED);
			final ReferenceSchemaContract referenceSchema = arm.referenceSchema();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> AttributeIndexMutator.executeAttributeUpsert(
					arm.executor, referenceSchema,
					new EntitySchemaAttributeAndCompoundSchemaProvider(
						IndexWriteTargetMirrorEquivalenceTest.this.productSchema
					),
					NO_EXISTING_VALUE_SUPPLIER, arm.target, arm.target,
					new AttributeKey(DataGenerator.ATTRIBUTE_PRIORITY), 5L, false, false
				)
			);
			assertTrue(error.getPrivateMessage().contains("belongs to the owner entity"), error.getPrivateMessage());
		}

		@Test
		@DisplayName("is skipped for an index object without a row, as the mirror always skipped it")
		void shouldSkipIndexObjectWithoutRow() {
			final Arm arm = new Arm(Scope.LIVE, true, MutationHandle.MAINTAINED);
			arm.registry().removePartition(PARTITION_PK);
			arm.upsert(ATTRIBUTE_ORDER, null, 3, false);
			assertFalse(arm.partitionObject().getSortIndexes().isEmpty(), "The index object must have been written");
			assertEquals(Map.of(), arm.snapshot(), "Nothing may have been mirrored for a partition without a row");
		}

		/**
		 * Asserts that a sortable reference attribute written into the arm's partition fails as a lost write.
		 *
		 * @param arm             the arm whose target is a virtual partition
		 * @param expectedMessage a fragment of the expected message
		 */
		private void assertLost(@Nonnull Arm arm, @Nonnull String expectedMessage) {
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> arm.upsert(ATTRIBUTE_ORDER, null, 3, false)
			);
			assertTrue(error.getPrivateMessage().contains("would be lost"), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains(expectedMessage), error.getPrivateMessage());
		}
	}

	/**
	 * Returns the history both arms replay: the population of a partition the owner has just entered, the updates
	 * of its values and the tear-down when the owner leaves it.
	 *
	 * @return the steps of the history, in order
	 */
	@Nonnull
	private static List<Step> history() {
		return List.of(
			// population, as the partition-local write set populates a partition: values without compounds first
			new Step("populate order", arm -> arm.upsert(ATTRIBUTE_ORDER, null, 3, false)),
			new Step("populate price", arm -> arm.upsert(ATTRIBUTE_PRICE, null, new BigDecimal("12.345"), false)),
			new Step("populate English label", arm -> arm.upsert(ATTRIBUTE_LABEL, Locale.ENGLISH, "Alpha", false)),
			new Step("populate Czech label", arm -> arm.upsert(ATTRIBUTE_LABEL, CZECH, "Beta", false)),
			new Step(
				"populate span",
				arm -> arm.upsert(
					ATTRIBUTE_SPAN, null,
					BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("7.255")), false
				)
			),
			new Step(
				"populate product chain", arm -> arm.upsert(ATTRIBUTE_PRODUCT_ORDER, null, new Predecessor(5), false)
			),
			new Step(
				"populate category chain",
				arm -> arm.upsert(ATTRIBUTE_CATEGORY_ORDER, null, new ReferencedEntityPredecessor(4), false)
			),
			new Step("populate the language-agnostic compounds", arm -> arm.compoundSuite(null, true)),
			new Step("populate the English compounds", arm -> arm.compoundSuite(Locale.ENGLISH, true)),
			new Step("populate the Czech compounds", arm -> arm.compoundSuite(CZECH, true)),
			// updates with their compounds
			new Step("update order", arm -> arm.upsert(ATTRIBUTE_ORDER, null, 8, true)),
			new Step("apply a delta to order", arm -> arm.delta(ATTRIBUTE_ORDER, 4)),
			new Step("update price", arm -> arm.upsert(ATTRIBUTE_PRICE, null, new BigDecimal("3.1"), true)),
			new Step("update English label", arm -> arm.upsert(ATTRIBUTE_LABEL, Locale.ENGLISH, "Gamma", true)),
			new Step(
				"move product chain to head", arm -> arm.upsert(ATTRIBUTE_PRODUCT_ORDER, null, Predecessor.HEAD, true)
			),
			new Step("remove Czech label", arm -> arm.remove(ATTRIBUTE_LABEL, CZECH, true)),
			new Step("remove the Czech compounds", arm -> arm.compoundSuite(CZECH, false)),
			new Step("remove span", arm -> arm.remove(ATTRIBUTE_SPAN, null, true)),
			new Step("remove category chain", arm -> arm.remove(ATTRIBUTE_CATEGORY_ORDER, null, true)),
			// tear-down, as the owner leaving the partition tears it down
			new Step("remove the English compounds", arm -> arm.compoundSuite(Locale.ENGLISH, false)),
			new Step("remove the language-agnostic compounds", arm -> arm.compoundSuite(null, false)),
			new Step("remove order", arm -> arm.remove(ATTRIBUTE_ORDER, null, false)),
			new Step("remove price", arm -> arm.remove(ATTRIBUTE_PRICE, null, false)),
			new Step("remove English label", arm -> arm.remove(ATTRIBUTE_LABEL, Locale.ENGLISH, false)),
			new Step("remove product chain", arm -> arm.remove(ATTRIBUTE_PRODUCT_ORDER, null, false))
		);
	}

	/**
	 * One step of the history.
	 *
	 * @param name   the name of the step, for the failure message
	 * @param action the writes of the step
	 */
	private record Step(@Nonnull String name, @Nonnull StepAction action) {

		/**
		 * Applies the step to the arm.
		 *
		 * @param arm the arm
		 */
		void apply(@Nonnull Arm arm) {
			this.action.apply(arm);
		}
	}

	/**
	 * The writes of one step.
	 */
	@FunctionalInterface
	private interface StepAction {

		/**
		 * Applies the writes to the arm.
		 *
		 * @param arm the arm
		 */
		void apply(@Nonnull Arm arm);
	}

	/**
	 * One arm of the comparison: an executor writing as {@link #OWNER_PK} into a single-owner partition registered in
	 * the registry of the reference's referenced-type entity index, and the values the owner has.
	 */
	private final class Arm {
		/**
		 * The index maintainer the executor writes through.
		 */
		@Nonnull private final RecordingIndexMaintainer maintainer = new RecordingIndexMaintainer();
		/**
		 * The executor writing as the owner.
		 */
		@Nonnull private final EntityIndexLocalMutationExecutor executor;
		/**
		 * The referenced-type entity index carrying the registry.
		 */
		@Nonnull private final ReferencedTypeEntityIndex typeIndex;
		/**
		 * The target the writes are directed at - the partition's index object or its virtual handle.
		 */
		@Nonnull private final IndexWriteTarget target;
		/**
		 * The values of the owner's reference, as the storage of the owner holds them before each write.
		 */
		@Nonnull private final ValueStore values = new ValueStore();
		/**
		 * The representative reference key of the partition.
		 */
		@Nonnull private final RepresentativeReferenceKey representativeReferenceKey =
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, TARGET_PK));

		/**
		 * Creates the arm with the partition's row registered for the owner.
		 *
		 * @param scope          the scope of the partition
		 * @param withObject     TRUE to direct the writes at the partition's index object, FALSE at its virtual
		 *                       handle
		 * @param mutationHandle the handle telling the executor whether it maintains the registry
		 */
		Arm(@Nonnull Scope scope, boolean withObject, @Nonnull MutationHandle mutationHandle) {
			final String entityType = IndexWriteTargetMirrorEquivalenceTest.this.productSchema.getName();
			this.typeIndex = new ReferencedTypeEntityIndex(
				100, entityType, ReferenceIndexMutator.getReferencedTypeIndexKey(REFERENCE_NAME, scope)
			);
			this.maintainer.register(this.typeIndex);
			final EntityIndexKey partitionKey = ReferencePartitions.createKey(scope, this.representativeReferenceKey);
			VirtualReferencePartitionWriter.partitionEntered(
				this.typeIndex.getVirtualReferencePartitionIndex(), PARTITION_PK, OWNER_PK,
				this.representativeReferenceKey
			);
			if (withObject) {
				final ReducedEntityIndex partition = new ReducedEntityIndex(PARTITION_PK, entityType, partitionKey);
				partition.insertPrimaryKeyIfMissing(OWNER_PK);
				this.maintainer.register(partition);
				this.target = partition;
			} else {
				this.target = new VirtualReferencePartition(
					PARTITION_PK, partitionKey, this.representativeReferenceKey, OWNER_PK, this.typeIndex
				);
			}
			this.executor = new EntityIndexLocalMutationExecutor(
				IndexWriteTargetMirrorEquivalenceTest.this.containerAccessor,
				OWNER_PK,
				this.maintainer,
				new MockEntityIndexCreator<>(IndexWriteTargetMirrorEquivalenceTest.this.catalogIndex),
				() -> IndexWriteTargetMirrorEquivalenceTest.this.productSchema,
				() -> {
					throw new UnsupportedOperationException("Not supported in the test.");
				},
				() -> {
					throw new UnsupportedOperationException("Not supported in the test.");
				},
				null,
				null,
				null,
				IndexWriteTargetMirrorEquivalenceTest.this.classifierResolver,
				IndexWriteTargetMirrorEquivalenceTest.this.usageRegistry,
				IndexWriteTargetMirrorEquivalenceTest.this.catalogUsageRegistry,
				true,
				mutationHandle
			);
		}

		/**
		 * Returns the schema of the reference the partition belongs to.
		 *
		 * @return the reference schema
		 */
		@Nonnull
		ReferenceSchemaContract referenceSchema() {
			return IndexWriteTargetMirrorEquivalenceTest.this.productSchema
				.getReferenceOrThrowException(REFERENCE_NAME);
		}

		/**
		 * Returns the registry of single-owner partitions of the reference.
		 *
		 * @return the registry
		 */
		@Nonnull
		VirtualReferencePartitionIndex registry() {
			return this.typeIndex.getVirtualReferencePartitionIndex();
		}

		/**
		 * Returns the partition's index object of the object arm.
		 *
		 * @return the index object
		 */
		@Nonnull
		ReducedEntityIndex partitionObject() {
			return (ReducedEntityIndex) this.target;
		}

		/**
		 * Upserts a value of a reference attribute, as the partition-local write set writes it: through the
		 * attribute index mutator, keyed by the referenced entity for a `ReferencedEntityPredecessor`.
		 *
		 * @param attributeName  the attribute
		 * @param locale         the locale of the value, `null` for a language-agnostic attribute
		 * @param value          the new value
		 * @param withCompounds  TRUE to update the compounds the attribute is part of
		 */
		void upsert(
			@Nonnull String attributeName,
			@Nullable Locale locale,
			@Nonnull Serializable value,
			boolean withCompounds
		) {
			final AttributeKey key = attributeKey(attributeName, locale);
			writeKeyedProperly(
				attributeName,
				() -> AttributeIndexMutator.executeAttributeUpsert(
					this.executor, referenceSchema(), attributeProvider(),
					withCompounds ?
						ExistingAttributeValueSupplier.normalizing(this.values) : NO_EXISTING_VALUE_SUPPLIER,
					this.target, this.target, key, value, false, withCompounds
				)
			);
			this.values.put(key, value);
		}

		/**
		 * Removes a value of a reference attribute.
		 *
		 * @param attributeName the attribute
		 * @param locale        the locale of the value, `null` for a language-agnostic attribute
		 * @param withCompounds TRUE to update the compounds the attribute is part of
		 */
		void remove(@Nonnull String attributeName, @Nullable Locale locale, boolean withCompounds) {
			final AttributeKey key = attributeKey(attributeName, locale);
			writeKeyedProperly(
				attributeName,
				() -> AttributeIndexMutator.executeAttributeRemoval(
					this.executor, referenceSchema(), attributeProvider(),
					ExistingAttributeValueSupplier.normalizing(this.values),
					this.target, this.target, key, false, withCompounds
				)
			);
			this.values.remove(key);
		}

		/**
		 * Applies a delta to a numeric value of a reference attribute.
		 *
		 * @param attributeName the attribute
		 * @param delta         the delta
		 */
		void delta(@Nonnull String attributeName, int delta) {
			final AttributeKey key = attributeKey(attributeName, null);
			AttributeIndexMutator.executeAttributeDelta(
				this.executor, referenceSchema(), attributeProvider(),
				ExistingAttributeValueSupplier.normalizing(this.values),
				this.target, this.target, key, delta, false
			);
			this.values.put(key, (Integer) this.values.get(key) + delta);
		}

		/**
		 * Inserts or removes the suite of the reference's sortable attribute compounds of one locale.
		 *
		 * @param locale the locale, `null` for the language-agnostic compounds
		 * @param insert TRUE to insert the suite, FALSE to remove it
		 */
		void compoundSuite(@Nullable Locale locale, boolean insert) {
			final ReferenceSchemaContract referenceSchema = referenceSchema();
			if (insert) {
				AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					this.executor, referenceSchema, this.target, locale, attributeProvider(), referenceSchema,
					ExistingAttributeValueSupplier.normalizing(this.values)
				);
			} else {
				AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					this.executor, referenceSchema, this.target, locale, attributeProvider(), referenceSchema,
					ExistingAttributeValueSupplier.normalizing(this.values)
				);
			}
		}

		/**
		 * Returns what the registry holds of the partition: its owner and, for every declared key, its sort value or
		 * chain predecessor, rendered for comparison.
		 *
		 * @return the row of the partition by the name of each of its parts, empty when the partition has no row
		 */
		@Nonnull
		Map<String, String> snapshot() {
			final VirtualReferencePartitionIndex registry = registry();
			final Map<String, String> snapshot = new TreeMap<>();
			final OptionalLong owner = registry.getPartitionOwner(PARTITION_PK);
			if (owner.isEmpty()) {
				return snapshot;
			}
			snapshot.put("owner", String.valueOf(owner.getAsLong()));
			for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
				if (registry.hasSortValue(key, PARTITION_PK)) {
					final Serializable value = registry.getNormalizedSortValue(
						key, registry.getSortValueId(key, PARTITION_PK)
					);
					snapshot.put(
						"sort " + describe(key),
						value instanceof Serializable[] array ? Arrays.deepToString(array) : String.valueOf(value)
					);
				}
			}
			for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
				final OptionalInt predecessor = registry.getChainPredecessor(key, PARTITION_PK);
				if (predecessor.isPresent()) {
					snapshot.put("chain " + describe(key), String.valueOf(predecessor.getAsInt()));
				}
			}
			return snapshot;
		}

		/**
		 * Runs the write keyed by the referenced entity for a `ReferencedEntityPredecessor`, as the partition-local
		 * write set keys it, and keyed by the owner otherwise.
		 *
		 * @param attributeName the attribute written
		 * @param write         the write
		 */
		private void writeKeyedProperly(@Nonnull String attributeName, @Nonnull Runnable write) {
			if (ATTRIBUTE_CATEGORY_ORDER.equals(attributeName)) {
				this.executor.executeWithDifferentPrimaryKeyToIndex((indexType, target) -> TARGET_PK, write);
			} else {
				write.run();
			}
		}

		/**
		 * Returns the provider of the reference's attribute and compound schemas.
		 *
		 * @return the provider
		 */
		@Nonnull
		private ReferenceSchemaAttributeAndCompoundSchemaProvider attributeProvider() {
			return new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				IndexWriteTargetMirrorEquivalenceTest.this.productSchema, referenceSchema()
			);
		}
	}

	/**
	 * Creates the key of an attribute value.
	 *
	 * @param attributeName the attribute
	 * @param locale        the locale, `null` for a language-agnostic attribute
	 * @return the key
	 */
	@Nonnull
	private static AttributeKey attributeKey(@Nonnull String attributeName, @Nullable Locale locale) {
		return locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
	}

	/**
	 * Renders a registry key by its attribute name and locale.
	 *
	 * @param key the key
	 * @return the rendering
	 */
	@Nonnull
	private static String describe(@Nonnull AttributeIndexKey key) {
		return key.locale() == null ? key.attributeName() : key.attributeName() + "/" + key.locale();
	}

	/**
	 * The values of the owner's reference, served as the existing values of each write.
	 */
	private static final class ValueStore implements ExistingAttributeValueSupplier {
		/**
		 * The values by their key.
		 */
		@Nonnull private final Map<AttributeKey, Serializable> values = new LinkedHashMap<>(16);

		/**
		 * Stores a value.
		 *
		 * @param key   the key
		 * @param value the value
		 */
		void put(@Nonnull AttributeKey key, @Nonnull Serializable value) {
			this.values.put(key, value);
		}

		/**
		 * Removes a value.
		 *
		 * @param key the key
		 */
		void remove(@Nonnull AttributeKey key) {
			this.values.remove(key);
		}

		/**
		 * Returns a value.
		 *
		 * @param key the key
		 * @return the value, `null` when there is none
		 */
		@Nullable
		Serializable get(@Nonnull AttributeKey key) {
			return this.values.get(key);
		}

		@Nonnull
		@Override
		public Set<Locale> getEntityExistingAttributeLocales() {
			return this.values.keySet().stream()
				.map(AttributeKey::locale)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		}

		@Nonnull
		@Override
		public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
			final Serializable value = this.values.get(attributeKey);
			return value == null ? Optional.empty() : Optional.of(new AttributeValue(attributeKey, value));
		}

		@Nonnull
		@Override
		public Stream<AttributeValue> getAttributeValues() {
			return this.values.entrySet().stream().map(it -> new AttributeValue(it.getKey(), it.getValue()));
		}

		@Nonnull
		@Override
		public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
			return getAttributeValues().filter(it -> locale.equals(it.key().locale()));
		}
	}

	/**
	 * Index maintainer over real indexes that records every key and primary key it is asked for and creates an index
	 * object for a key it does not hold, so a write reaching for an index object shows up in its counts.
	 */
	private final class RecordingIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {
		/**
		 * Every index by key.
		 */
		@Nonnull private final Map<EntityIndexKey, EntityIndex> indexes = CollectionUtils.createHashMap(4);
		/**
		 * Every key the maintainer was asked for.
		 */
		@Nonnull private final List<EntityIndexKey> touchedKeys = new ArrayList<>(16);
		/**
		 * The number of resolutions by primary key.
		 */
		private int primaryKeyAcquisitions;
		/**
		 * The primary key of the next index object the maintainer creates.
		 */
		private int nextPrimaryKey = 1_000;

		/**
		 * Registers an index under its own key.
		 *
		 * @param index the index
		 */
		void register(@Nonnull EntityIndex index) {
			this.indexes.put(index.getIndexKey(), index);
		}

		/**
		 * Returns the number of index objects the maintainer holds.
		 *
		 * @return the number of index objects
		 */
		int getObjectCount() {
			return this.indexes.size();
		}

		/**
		 * Returns every key the maintainer was asked for.
		 *
		 * @return the keys, in the order they were asked for
		 */
		@Nonnull
		List<EntityIndexKey> getTouchedKeys() {
			return this.touchedKeys;
		}

		/**
		 * Returns the number of resolutions by primary key.
		 *
		 * @return the number of resolutions
		 */
		int getPrimaryKeyAcquisitions() {
			return this.primaryKeyAcquisitions;
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			this.touchedKeys.add(entityIndexKey);
			return this.indexes.computeIfAbsent(
				entityIndexKey,
				key -> key.type() == EntityIndexType.REFERENCED_ENTITY ?
					new ReducedEntityIndex(
						this.nextPrimaryKey++,
						IndexWriteTargetMirrorEquivalenceTest.this.productSchema.getName(),
						key
					) :
					new ReferencedTypeEntityIndex(
						this.nextPrimaryKey++,
						IndexWriteTargetMirrorEquivalenceTest.this.productSchema.getName(),
						key
					)
			);
		}

		@Nullable
		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			this.touchedKeys.add(entityIndexKey);
			return this.indexes.get(entityIndexKey);
		}

		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			this.primaryKeyAcquisitions++;
			return null;
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			this.primaryKeyAcquisitions++;
			throw new GenericEvitaInternalError("No index with primary key " + indexPrimaryKey + "!");
		}

		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			throw new UnsupportedOperationException("The test maintainer does not remove indexes");
		}

		@Nonnull
		@Override
		public EntityIndex installIndex(
			@Nonnull EntityIndexKey indexKey,
			int primaryKey,
			@Nonnull IntFunction<EntityIndex> factory
		) {
			throw new UnsupportedOperationException("The test maintainer does not install indexes");
		}

		@Override
		public int allocatePartitionPrimaryKey() {
			throw new UnsupportedOperationException("The test maintainer does not allocate primary keys");
		}
	}

}
