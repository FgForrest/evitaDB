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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.buffer.TrappedChanges;
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleScope;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.VirtualReferencePartitionIndexStoragePartSerializer;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
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
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Proves that the write arms of a mutation writing the single-owner partitions virtually leave the registry of
 * single-owner partitions exactly as the mirror of the partitions' index objects leaves it - byte for byte, as the
 * registry would be flushed - and that they create no index object while doing so.
 *
 * Two arms replay one history of an owner, mutation by mutation, through the production entry points of the entity
 * index executor: the object arm keeps an index object next to the registry row of every partition and writes through
 * a mutation that does not write virtually, as the registry is shadowed today; the virtual arm keeps the registry row
 * only and writes through a mutation that writes virtually, so its acquisitions hand out virtual partitions. Both arms
 * read the one storage of the owner, which the test moves forward between the index writes and the finalization of
 * each mutation, as the storage executor does. After every mutation the registries of both arms must serialize to the
 * same bytes.
 *
 * The history covers reference attribute upserts, removals and a delta on every sort type the registry keeps (plain
 * values with and without decimal places, a date-time, a localized value, a range, a `Predecessor` and a
 * `ReferencedEntityPredecessor` chain), the compounds they are part of (a localized one among them), locales the owner
 * gains and loses - both the language records a virtual partition does not keep and the localized compounds it does
 * - and the facet arms, which write the global index only. A second history moves a reference attribute between two
 * partitions of one target in all four combinations of their forms.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual partition write arms - registry equivalence with the mirror of index objects")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualPartitionWriteArmEquivalenceTest extends AbstractMutatorTestBase {
	/**
	 * A faceted reference indexed for filtering, without duplicates - the reference of the main history.
	 */
	private static final String REFERENCE_NAME = "armRef";
	/**
	 * A reference indexed for filtering that allows duplicates - the reference of the relocation history.
	 */
	private static final String DUPLICATE_REFERENCE_NAME = "armDuplicateRef";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_PRICE = "price";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_SPAN = "span";
	private static final String ATTRIBUTE_VALID_FROM = "validFrom";
	private static final String ATTRIBUTE_PRODUCT_ORDER = "productOrder";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String ATTRIBUTE_FILTERED = "filtered";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_WINDOW = "window";
	private static final String COMPOUND_LABEL_AND_PRICE = "labelAndPrice";
	private static final String COMPOUND_SPAN_PRICE_ORDER = "spanPriceOrder";
	private static final String COMPOUND_WINDOW_RANK = "windowRank";
	private static final Locale CZECH = DataGenerator.CZECH_LOCALE;
	/**
	 * The primary key of the owner every executor writes as.
	 */
	private static final int OWNER_PK = 1;
	/**
	 * The primary key of the entity every partition references.
	 */
	private static final int TARGET_PK = 7;
	/**
	 * The primary key of the group the facet arms move the facet to.
	 */
	private static final int GROUP_PK = 77;
	/**
	 * The primary key of the partition of {@link #REFERENCE_NAME}, the same in both arms.
	 */
	private static final int PARTITION_PK = 21;
	/**
	 * The primary key of the partition of {@link #DUPLICATE_REFERENCE_NAME} the relocated value leaves.
	 */
	private static final int GERMAN_PARTITION_PK = 31;
	/**
	 * The primary key of the partition of {@link #DUPLICATE_REFERENCE_NAME} the relocated value enters.
	 */
	private static final int AMERICAN_PARTITION_PK = 32;
	/**
	 * The key of the partition of {@link #REFERENCE_NAME}.
	 */
	private static final RepresentativeReferenceKey PARTITION_KEY =
		new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, TARGET_PK));
	/**
	 * The key of the partition of {@link #DUPLICATE_REFERENCE_NAME} the relocated value leaves.
	 */
	private static final RepresentativeReferenceKey GERMAN_KEY = new RepresentativeReferenceKey(
		new ReferenceKey(DUPLICATE_REFERENCE_NAME, TARGET_PK), new Serializable[]{"DE"}
	);
	/**
	 * The key of the partition of {@link #DUPLICATE_REFERENCE_NAME} the relocated value enters.
	 */
	private static final RepresentativeReferenceKey AMERICAN_KEY = new RepresentativeReferenceKey(
		new ReferenceKey(DUPLICATE_REFERENCE_NAME, TARGET_PK), new Serializable[]{"US"}
	);
	/**
	 * Names the write path of the acquisitions the test makes itself.
	 */
	private static final String WRITE_SITE = "writeArmTest";

	/**
	 * The storage of the owner both arms read.
	 */
	@Nonnull private final OwnerStorage storage = new OwnerStorage();

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schemaBuilder) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schemaBuilder) {
		schemaBuilder
			.withLocale(Locale.ENGLISH, CZECH)
			.withReferenceTo(
				REFERENCE_NAME, REFERENCE_NAME, Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs
					.withGroupType("armGroup")
					.indexedForFiltering()
					.faceted()
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, attr -> attr.filterable().sortable().nullable())
					.withAttribute(
						ATTRIBUTE_PRICE, BigDecimal.class, attr -> attr.sortable().indexDecimalPlaces(2).nullable()
					)
					.withAttribute(ATTRIBUTE_LABEL, String.class, attr -> attr.localized().sortable().nullable())
					.withAttribute(
						ATTRIBUTE_SPAN, BigDecimalNumberRange.class,
						attr -> attr.sortable().indexDecimalPlaces(2).nullable()
					)
					.withAttribute(ATTRIBUTE_VALID_FROM, OffsetDateTime.class, attr -> attr.sortable().nullable())
					.withAttribute(ATTRIBUTE_PRODUCT_ORDER, Predecessor.class, attr -> attr.sortable().nullable())
					.withAttribute(
						ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class, attr -> attr.sortable().nullable()
					)
					.withAttribute(ATTRIBUTE_FILTERED, Integer.class, attr -> attr.filterable().nullable())
					.withSortableAttributeCompound(
						COMPOUND_LABEL_AND_PRICE,
						new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
					.withSortableAttributeCompound(
						COMPOUND_SPAN_PRICE_ORDER,
						new AttributeElement(ATTRIBUTE_SPAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_PRICE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
					)
			)
			.withReferenceTo(
				DUPLICATE_REFERENCE_NAME, DUPLICATE_REFERENCE_NAME, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				thatIs -> thatIs
					.indexedForFiltering()
					.withAttribute(ATTRIBUTE_VARIANT, String.class, attr -> attr.filterable().representative())
					.withAttribute(ATTRIBUTE_RANK, Integer.class, attr -> attr.filterable().sortable().nullable())
					.withAttribute(ATTRIBUTE_WINDOW, IntegerNumberRange.class, attr -> attr.sortable().nullable())
					.withSortableAttributeCompound(
						COMPOUND_WINDOW_RANK,
						new AttributeElement(ATTRIBUTE_WINDOW, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_RANK, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
					)
			);
	}

	@Test
	@DisplayName("the references of the fixture may be virtual")
	void shouldShadowBothReferences() {
		assertTrue(VirtualReferencePartitionWriter.isShadowed(referenceSchema(REFERENCE_NAME), Scope.LIVE));
		assertTrue(VirtualReferencePartitionWriter.isShadowed(referenceSchema(DUPLICATE_REFERENCE_NAME), Scope.LIVE));
	}

	@Test
	@DisplayName("every mutation of the owner leaves both registries byte-identical, and no index object is created")
	void shouldLeaveRegistryAsMirrorOfIndexObjectAfterEveryMutation() {
		final Arm objectArm = new Arm(MutationHandle.MAINTAINED, REFERENCE_NAME);
		objectArm.seed(PARTITION_KEY, PARTITION_PK, true);
		final Arm virtualArm = new Arm(MutationHandle.WRITTEN_VIRTUALLY, REFERENCE_NAME);
		virtualArm.seed(PARTITION_KEY, PARTITION_PK, false);
		final ReferenceKey referenceKey = PARTITION_KEY.referenceKey();
		this.storage.setReference(
			new Reference(this.productSchema, referenceSchema(REFERENCE_NAME), referenceKey, null)
		);
		final int objectsBefore = virtualArm.maintainer.getPartitionObjectCount();
		final ReducedEntityIndex partitionObject = objectArm.partitionObject(PARTITION_KEY);

		final List<Map<String, String>> snapshots = new ArrayList<>(32);
		boolean objectWritten = false;
		for (final Mutation mutation : history()) {
			replay(mutation, objectArm, virtualArm);
			objectWritten |= !partitionObject.getSortIndexes().isEmpty();
			final Map<String, String> snapshot = objectArm.snapshot(PARTITION_PK);
			if (mutation.writesRegistry() && !snapshots.isEmpty()) {
				assertNotEquals(
					snapshots.get(snapshots.size() - 1), snapshot,
					"`" + mutation.name() + "` left the registry unchanged, so it compared two untouched registries"
				);
			}
			snapshots.add(snapshot);
		}

		// the history must have exercised every kind of key the registry keeps, or the equality proves little
		final Set<String> everyKey = snapshots.stream()
			.flatMap(it -> it.keySet().stream())
			.collect(Collectors.toSet());
		for (final String expected : List.of(
			"sort " + ATTRIBUTE_ORDER, "sort " + ATTRIBUTE_PRICE, "sort " + ATTRIBUTE_VALID_FROM,
			"sort " + ATTRIBUTE_LABEL + "/" + Locale.ENGLISH, "sort " + ATTRIBUTE_LABEL + "/" + CZECH,
			"sort " + ATTRIBUTE_SPAN, "sort " + COMPOUND_LABEL_AND_PRICE + "/" + Locale.ENGLISH,
			"sort " + COMPOUND_LABEL_AND_PRICE + "/" + CZECH, "sort " + COMPOUND_SPAN_PRICE_ORDER,
			"chain " + ATTRIBUTE_PRODUCT_ORDER, "chain " + ATTRIBUTE_CATEGORY_ORDER
		)) {
			assertTrue(everyKey.contains(expected), "The history never wrote `" + expected + "`: " + everyKey);
		}
		assertFalse(
			everyKey.stream().anyMatch(it -> it.contains(ATTRIBUTE_FILTERED)),
			"An attribute that is not sortable must not reach the registry: " + everyKey
		);

		// the object arm wrote its index object, the virtual arm created none and acquired none
		assertTrue(objectWritten, "The object arm must have written its index object");
		assertEquals(objectsBefore, virtualArm.maintainer.getPartitionObjectCount(), "An index object was created");
		assertEquals(0, virtualArm.maintainer.getPartitionAcquisitions(), "A partition object was acquired");
		// the facet arms wrote the global index and no partition
		assertTrue(partitionObject.getFacetingEntities().isEmpty(), "A facet reached the partition object");
		for (final Arm arm : List.of(objectArm, virtualArm)) {
			assertTrue(
				isFacetPresentInGroup(arm.globalIndex(), referenceKey, null),
				"The global index of the " + arm + " lost the facet"
			);
		}
	}

	@Test
	@DisplayName("the facet group arms move the facet of the global index and leave the partition untouched")
	void shouldMoveGlobalFacetAndLeavePartitionUntouched() {
		final Arm objectArm = new Arm(MutationHandle.MAINTAINED, REFERENCE_NAME);
		objectArm.seed(PARTITION_KEY, PARTITION_PK, true);
		final Arm virtualArm = new Arm(MutationHandle.WRITTEN_VIRTUALLY, REFERENCE_NAME);
		virtualArm.seed(PARTITION_KEY, PARTITION_PK, false);
		final ReferenceKey referenceKey = PARTITION_KEY.referenceKey();
		this.storage.setReference(
			new Reference(this.productSchema, referenceSchema(REFERENCE_NAME), referenceKey, null)
		);
		final Map<String, String> before = objectArm.snapshot(PARTITION_PK);

		replay(
			referenceMutation("set the group", new SetReferenceGroupMutation(referenceKey, GROUP_PK), false),
			objectArm, virtualArm
		);
		for (final Arm arm : List.of(objectArm, virtualArm)) {
			assertTrue(
				isFacetPresentInGroup(arm.globalIndex(), referenceKey, GROUP_PK),
				"The global index of the " + arm + " must hold the facet in the group"
			);
		}
		replay(
			referenceMutation("remove the group", new RemoveReferenceGroupMutation(referenceKey), false),
			objectArm, virtualArm
		);
		replay(
			new Mutation(
				"add the facet to the partition", false,
				executor -> ReferenceIndexMutator.addFacetToPartition(
					executor.getPartitionForWrite(Scope.LIVE, PARTITION_KEY, WRITE_SITE),
					referenceSchema(REFERENCE_NAME), referenceKey, GROUP_PK, OWNER_PK, executor
				),
				() -> {}
			),
			objectArm, virtualArm
		);
		for (final Arm arm : List.of(objectArm, virtualArm)) {
			assertTrue(
				isFacetPresentInGroup(arm.globalIndex(), referenceKey, null),
				"The global index of the " + arm + " must hold the facet without a group"
			);
			assertEquals(before, arm.snapshot(PARTITION_PK), "The facet arms changed the registry of the " + arm);
		}
		assertTrue(
			objectArm.partitionObject(PARTITION_KEY).getFacetingEntities().isEmpty(),
			"A facet reached the partition object"
		);
		assertEquals(0, virtualArm.maintainer.getPartitionObjectCount(), "An index object was created");
	}

	@ParameterizedTest(name = "removal half {0}")
	@EnumSource(PairForm.class)
	@DisplayName("an attribute moved between two partitions leaves both registries byte-identical, in any form")
	void shouldMoveAttributeBetweenPartitionsOfAnyForm(@Nonnull PairForm form) {
		final Arm objectArm = new Arm(MutationHandle.MAINTAINED, DUPLICATE_REFERENCE_NAME);
		objectArm.seed(GERMAN_KEY, GERMAN_PARTITION_PK, true);
		objectArm.seed(AMERICAN_KEY, AMERICAN_PARTITION_PK, true);
		final Arm virtualArm = new Arm(MutationHandle.WRITTEN_VIRTUALLY, DUPLICATE_REFERENCE_NAME);
		virtualArm.seed(GERMAN_KEY, GERMAN_PARTITION_PK, !form.germanVirtual());
		virtualArm.seed(AMERICAN_KEY, AMERICAN_PARTITION_PK, !form.americanVirtual());
		final ReferenceKey referenceKey = new ReferenceKey(DUPLICATE_REFERENCE_NAME, TARGET_PK, 1);
		final Reference reference = new Reference(
			this.productSchema, referenceSchema(DUPLICATE_REFERENCE_NAME), referenceKey, null
		);
		this.storage.setReference(
			(Reference) new ReferenceAttributeMutation(
				referenceKey, new UpsertAttributeMutation(ATTRIBUTE_VARIANT, "DE")
			).mutateLocal(this.productSchema, reference)
		);
		final int virtualObjects = virtualArm.maintainer.getPartitionObjectCount();

		final List<Map<String, String>> snapshots = new ArrayList<>(8);
		snapshots.add(objectArm.snapshot(GERMAN_PARTITION_PK, AMERICAN_PARTITION_PK));
		for (final Mutation mutation : List.of(
			pairMutation(
				"populate the window of the German partition", referenceKey, GERMAN_KEY, GERMAN_KEY,
				new UpsertAttributeMutation(ATTRIBUTE_WINDOW, IntegerNumberRange.between(1, 5))
			),
			pairMutation(
				"populate the rank of the German partition", referenceKey, GERMAN_KEY, GERMAN_KEY,
				new UpsertAttributeMutation(ATTRIBUTE_RANK, 3)
			),
			pairMutation(
				"move the rank to the American partition", referenceKey, GERMAN_KEY, AMERICAN_KEY,
				new UpsertAttributeMutation(ATTRIBUTE_RANK, 8)
			),
			pairMutation(
				"remove the rank from the American partition", referenceKey, AMERICAN_KEY, GERMAN_KEY,
				new RemoveAttributeMutation(ATTRIBUTE_RANK)
			)
		)) {
			replay(mutation, objectArm, virtualArm);
			final Map<String, String> snapshot = objectArm.snapshot(GERMAN_PARTITION_PK, AMERICAN_PARTITION_PK);
			assertNotEquals(snapshots.get(snapshots.size() - 1), snapshot, "`" + mutation.name() + "` wrote nothing");
			snapshots.add(snapshot);
		}
		// the move reached both partitions: the American one held the rank and the compound in between
		final Map<String, String> moved = snapshots.get(3);
		assertEquals("8", moved.get(AMERICAN_PARTITION_PK + " sort " + ATTRIBUTE_RANK), moved.toString());
		assertFalse(moved.containsKey(GERMAN_PARTITION_PK + " sort " + ATTRIBUTE_RANK), moved.toString());
		assertTrue(moved.containsKey(AMERICAN_PARTITION_PK + " sort " + COMPOUND_WINDOW_RANK), moved.toString());
		assertEquals(virtualObjects, virtualArm.maintainer.getPartitionObjectCount(), "An index object was created");
	}

	@Nested
	@DisplayName("a mutation that does not write virtually")
	class NotWrittenVirtually {

		@Test
		@DisplayName("refuses a virtual partition on every arm that takes one")
		void shouldRefuseVirtualPartitionOnEveryArm() {
			final Arm virtualArm = new Arm(MutationHandle.WRITTEN_VIRTUALLY, REFERENCE_NAME);
			virtualArm.seed(PARTITION_KEY, PARTITION_PK, false);
			final ReferenceKey referenceKey = PARTITION_KEY.referenceKey();
			final VirtualPartitionWriteArmEquivalenceTest outer = VirtualPartitionWriteArmEquivalenceTest.this;
			outer.storage.setReference(
				new Reference(outer.productSchema, referenceSchema(REFERENCE_NAME), referenceKey, null)
			);
			final VirtualReferencePartition partition = assertInstanceOf(
				VirtualReferencePartition.class,
				virtualArm.newExecutor().getPartitionForWrite(Scope.LIVE, PARTITION_KEY, WRITE_SITE)
			);
			// the same maintainer, read by a mutation that only maintains the registry
			final EntityIndexLocalMutationExecutor executor = virtualArm.newExecutor(MutationHandle.MAINTAINED);
			final ReferenceSchemaContract referenceSchema = referenceSchema(REFERENCE_NAME);
			final List<Runnable> arms = List.of(
				() -> ReferenceIndexMutator.attributeUpdate(
					executor, executor.getStoragePartExistingDataFactory(),
					virtualArm.typeIndex(), partition, partition, referenceSchema, referenceKey,
					new UpsertAttributeMutation(ATTRIBUTE_ORDER, 3)
				),
				() -> ReferenceIndexMutator.addFacetToPartition(
					partition, referenceSchema, referenceKey, null, OWNER_PK, executor
				),
				() -> ReferenceIndexMutator.requireWrittenVirtually(executor, referenceSchema, partition, WRITE_SITE)
			);
			for (final Runnable arm : arms) {
				final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, arm::run);
				assertTrue(error.getPrivateMessage().contains("it cannot write"), error.getPrivateMessage());
			}
			// and a mutation writing virtually refuses a partition of another reference
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceIndexMutator.requireWrittenVirtually(
					virtualArm.newExecutor(), referenceSchema(DUPLICATE_REFERENCE_NAME), partition, WRITE_SITE
				)
			);
			assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());
		}
	}

	@Nested
	@DisplayName("a price consumer")
	class PriceConsumer {

		@Test
		@DisplayName("refuses a virtual partition and passes an index object through")
		void shouldRefuseVirtualPartition() {
			final Arm virtualArm = new Arm(MutationHandle.WRITTEN_VIRTUALLY, REFERENCE_NAME);
			virtualArm.seed(PARTITION_KEY, PARTITION_PK, false);
			final ReferencePartition partition =
				virtualArm.newExecutor().getPartitionForWrite(Scope.LIVE, PARTITION_KEY, WRITE_SITE);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceIndexMutator.requireIndexObject(partition, referenceSchema(REFERENCE_NAME), WRITE_SITE)
			);
			assertTrue(error.getPrivateMessage().contains("holds no partitioning content"), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());

			final Arm objectArm = new Arm(MutationHandle.MAINTAINED, REFERENCE_NAME);
			objectArm.seed(PARTITION_KEY, PARTITION_PK, true);
			final ReducedEntityIndex partitionObject = objectArm.partitionObject(PARTITION_KEY);
			assertSame(
				partitionObject,
				ReferenceIndexMutator.requireIndexObject(partitionObject, referenceSchema(REFERENCE_NAME), WRITE_SITE)
			);
		}
	}

	/**
	 * Returns the main history: the mutations of an owner that has one reference to {@link #TARGET_PK}.
	 *
	 * @return the mutations, in order
	 */
	@Nonnull
	private List<Mutation> history() {
		final ReferenceKey key = PARTITION_KEY.referenceKey();
		return List.of(
			attributeMutation("upsert order", key, new UpsertAttributeMutation(ATTRIBUTE_ORDER, 3)),
			attributeMutation(
				"upsert price", key, new UpsertAttributeMutation(ATTRIBUTE_PRICE, new BigDecimal("12.345"))
			),
			attributeMutation(
				"upsert span", key,
				new UpsertAttributeMutation(
					ATTRIBUTE_SPAN, BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("7.255"))
				)
			),
			attributeMutation(
				"upsert valid from", key,
				new UpsertAttributeMutation(
					ATTRIBUTE_VALID_FROM, OffsetDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneOffset.UTC)
				)
			),
			attributeMutation(
				"upsert product chain", key, new UpsertAttributeMutation(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(5))
			),
			attributeMutation(
				"upsert category chain", key,
				new UpsertAttributeMutation(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(4))
			),
			referenceMutation(
				"upsert a value that is only filterable", new ReferenceAttributeMutation(
					key, new UpsertAttributeMutation(ATTRIBUTE_FILTERED, 1)
				), false
			),
			localeGained(
				"upsert English label, the owner gains English",
				attributeMutation("", key, new UpsertAttributeMutation(labelKey(Locale.ENGLISH), "Alpha")),
				Locale.ENGLISH
			),
			localeGained(
				"upsert Czech label, the owner gains Czech",
				attributeMutation("", key, new UpsertAttributeMutation(labelKey(CZECH), "Beta")),
				CZECH
			),
			attributeMutation("apply a delta to order", key, new ApplyDeltaAttributeMutation<>(ATTRIBUTE_ORDER, 4)),
			attributeMutation("update price", key, new UpsertAttributeMutation(ATTRIBUTE_PRICE, new BigDecimal("3.1"))),
			attributeMutation(
				"update English label", key,
				new UpsertAttributeMutation(labelKey(Locale.ENGLISH), "Gamma")
			),
			attributeMutation(
				"move product chain to head", key,
				new UpsertAttributeMutation(ATTRIBUTE_PRODUCT_ORDER, Predecessor.HEAD)
			),
			localeLost(
				"remove Czech label, the owner loses Czech",
				attributeMutation("", key, new RemoveAttributeMutation(labelKey(CZECH))),
				CZECH
			),
			attributeMutation("remove span", key, new RemoveAttributeMutation(ATTRIBUTE_SPAN)),
			attributeMutation("remove category chain", key, new RemoveAttributeMutation(ATTRIBUTE_CATEGORY_ORDER)),
			attributeMutation("remove valid from", key, new RemoveAttributeMutation(ATTRIBUTE_VALID_FROM)),
			referenceMutation("set the group", new SetReferenceGroupMutation(key, GROUP_PK), false),
			referenceMutation("remove the group", new RemoveReferenceGroupMutation(key), false),
			attributeMutation("remove order", key, new RemoveAttributeMutation(ATTRIBUTE_ORDER)),
			attributeMutation("remove price", key, new RemoveAttributeMutation(ATTRIBUTE_PRICE)),
			localeLost(
				"remove English label, the owner loses English",
				attributeMutation("", key, new RemoveAttributeMutation(labelKey(Locale.ENGLISH))),
				Locale.ENGLISH
			),
			attributeMutation("remove product chain", key, new RemoveAttributeMutation(ATTRIBUTE_PRODUCT_ORDER))
		);
	}

	/**
	 * Creates the mutation of a reference attribute that reaches the registry.
	 *
	 * @param name              the name of the mutation, for the failure messages
	 * @param referenceKey      the reference
	 * @param attributeMutation the attribute mutation
	 * @return the mutation
	 */
	@Nonnull
	private Mutation attributeMutation(
		@Nonnull String name,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		return referenceMutation(name, new ReferenceAttributeMutation(referenceKey, attributeMutation), true);
	}

	/**
	 * Creates a mutation applying one reference mutation through the executor, as the collector dispatches it, and to
	 * the owner's stored reference.
	 *
	 * @param name           the name of the mutation, for the failure messages
	 * @param mutation       the reference mutation
	 * @param writesRegistry TRUE when the mutation must change the registry
	 * @return the mutation
	 */
	@Nonnull
	private Mutation referenceMutation(
		@Nonnull String name,
		@Nonnull ReferenceMutation<?> mutation,
		boolean writesRegistry
	) {
		return new Mutation(
			name, writesRegistry,
			executor -> executor.applyMutation(mutation),
			() -> this.storage.setReference(
				(Reference) mutation.mutateLocal(this.productSchema, this.storage.getReference())
			)
		);
	}

	/**
	 * Creates a mutation that, besides its own writes, makes the owner gain a locale - its attributes and the entity
	 * itself are localized in it from now on.
	 *
	 * @param name     the name of the mutation, for the failure messages
	 * @param mutation the writes of the mutation
	 * @param locale   the locale the owner gains
	 * @return the mutation
	 */
	@Nonnull
	private Mutation localeGained(@Nonnull String name, @Nonnull Mutation mutation, @Nonnull Locale locale) {
		return new Mutation(
			name, true, mutation.indexWrite(),
			() -> {
				mutation.storageWrite().run();
				this.storage.localeAdded(locale);
			}
		);
	}

	/**
	 * Creates a mutation that, besides its own writes, makes the owner lose a locale.
	 *
	 * @param name     the name of the mutation, for the failure messages
	 * @param mutation the writes of the mutation
	 * @param locale   the locale the owner loses
	 * @return the mutation
	 */
	@Nonnull
	private Mutation localeLost(@Nonnull String name, @Nonnull Mutation mutation, @Nonnull Locale locale) {
		return new Mutation(
			name, true, mutation.indexWrite(),
			() -> {
				mutation.storageWrite().run();
				this.storage.localeRemoved(locale);
			}
		);
	}

	/**
	 * Creates a mutation writing a reference attribute of {@link #DUPLICATE_REFERENCE_NAME} into a pair of partitions
	 * - the one the old value leaves and the one the new value enters - as a change of the representative values of
	 * the reference writes it.
	 *
	 * @param name              the name of the mutation, for the failure messages
	 * @param referenceKey      the reference
	 * @param removalKey        the key of the partition the old value leaves
	 * @param upsertKey         the key of the partition the new value enters
	 * @param attributeMutation the attribute mutation
	 * @return the mutation
	 */
	@Nonnull
	private Mutation pairMutation(
		@Nonnull String name,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull RepresentativeReferenceKey removalKey,
		@Nonnull RepresentativeReferenceKey upsertKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final ReferenceAttributeMutation mutation = new ReferenceAttributeMutation(referenceKey, attributeMutation);
		return new Mutation(
			name, true,
			executor -> ReferenceIndexMutator.attributeUpdate(
				executor, executor.getStoragePartExistingDataFactory(),
				ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
					executor, DUPLICATE_REFERENCE_NAME, Scope.LIVE
				),
				executor.getPartitionForWrite(Scope.LIVE, removalKey, WRITE_SITE),
				executor.getPartitionForWrite(Scope.LIVE, upsertKey, WRITE_SITE),
				referenceSchema(DUPLICATE_REFERENCE_NAME), referenceKey, attributeMutation
			),
			() -> this.storage.setReference(
				(Reference) mutation.mutateLocal(this.productSchema, this.storage.getReference())
			)
		);
	}

	/**
	 * Replays one mutation of the owner in both arms - the index writes of each, then the storage of the owner moved
	 * forward, then the finalization of each, as one entity mutation runs - and asserts that the registries of both
	 * serialize to the same bytes afterwards.
	 *
	 * @param mutation   the mutation
	 * @param objectArm  the arm of index objects
	 * @param virtualArm the arm of registry rows
	 */
	private void replay(@Nonnull Mutation mutation, @Nonnull Arm objectArm, @Nonnull Arm virtualArm) {
		final EntityIndexLocalMutationExecutor objectExecutor = objectArm.newExecutor();
		final EntityIndexLocalMutationExecutor virtualExecutor = virtualArm.newExecutor();
		mutation.indexWrite().accept(objectExecutor);
		mutation.indexWrite().accept(virtualExecutor);
		mutation.storageWrite().run();
		objectExecutor.applyChanges();
		virtualExecutor.applyChanges();
		this.storage.mutationFinished();
		assertEquals(
			objectArm.snapshot(objectArm.partitionPrimaryKeys()),
			virtualArm.snapshot(virtualArm.partitionPrimaryKeys()),
			"The registries differ after `" + mutation.name() + "`"
		);
		assertArrayEquals(
			objectArm.serializedRegistry(), virtualArm.serializedRegistry(),
			"The registries serialize differently after `" + mutation.name() + "`"
		);
	}

	/**
	 * Returns the reference schema of the product.
	 *
	 * @param referenceName the reference
	 * @return the reference schema
	 */
	@Nonnull
	private ReferenceSchemaContract referenceSchema(@Nonnull String referenceName) {
		return this.productSchema.getReferenceOrThrowException(referenceName);
	}

	/**
	 * Creates the key of a value of the localized label.
	 *
	 * @param locale the locale of the value
	 * @return the key
	 */
	@Nonnull
	private static AttributeKey labelKey(@Nonnull Locale locale) {
		return new AttributeKey(ATTRIBUTE_LABEL, locale);
	}

	/**
	 * Returns TRUE when the index holds the facet of the reference for the owner in the group.
	 *
	 * @param index        the index
	 * @param referenceKey the reference
	 * @param groupId      the group, `null` for none
	 * @return TRUE when the facet is there
	 */
	private static boolean isFacetPresentInGroup(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId
	) {
		final FacetReferenceIndex facetReferenceIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		final FacetGroupIndex groupIndex = facetReferenceIndex == null ?
			null : facetReferenceIndex.getFacetsInGroup(groupId);
		final FacetIdIndex facetIdIndex = groupIndex == null ?
			null : groupIndex.getFacetIdIndex(referenceKey.primaryKey());
		return facetIdIndex != null && facetIdIndex.getRecords().contains(OWNER_PK);
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
	 * The forms of the two partitions of the relocation history in the virtual arm.
	 */
	enum PairForm {
		VIRTUAL_TO_VIRTUAL(true, true),
		VIRTUAL_TO_OBJECT(true, false),
		OBJECT_TO_VIRTUAL(false, true),
		OBJECT_TO_OBJECT(false, false);

		/**
		 * TRUE when the German partition is a registry row only.
		 */
		private final boolean germanVirtual;
		/**
		 * TRUE when the American partition is a registry row only.
		 */
		private final boolean americanVirtual;

		/**
		 * Creates the form.
		 *
		 * @param germanVirtual   TRUE when the German partition is a registry row only
		 * @param americanVirtual TRUE when the American partition is a registry row only
		 */
		PairForm(boolean germanVirtual, boolean americanVirtual) {
			this.germanVirtual = germanVirtual;
			this.americanVirtual = americanVirtual;
		}

		/**
		 * Returns TRUE when the German partition is a registry row only.
		 *
		 * @return TRUE for a row only
		 */
		boolean germanVirtual() {
			return this.germanVirtual;
		}

		/**
		 * Returns TRUE when the American partition is a registry row only.
		 *
		 * @return TRUE for a row only
		 */
		boolean americanVirtual() {
			return this.americanVirtual;
		}
	}

	/**
	 * One mutation of the owner.
	 *
	 * @param name           the name of the mutation, for the failure messages
	 * @param writesRegistry TRUE when the mutation must change the registry
	 * @param indexWrite     the index writes of the mutation, applied through the executor of each arm
	 * @param storageWrite   moves the storage of the owner past the mutation
	 */
	private record Mutation(
		@Nonnull String name,
		boolean writesRegistry,
		@Nonnull Consumer<EntityIndexLocalMutationExecutor> indexWrite,
		@Nonnull Runnable storageWrite
	) {
	}

	/**
	 * One arm of the comparison: the indexes of the owner's collection - a type index carrying the registry of one
	 * reference, the global index and the partition objects the arm keeps - and the handle its mutations write with.
	 */
	private final class Arm {
		/**
		 * The indexes of the arm.
		 */
		@Nonnull private final ArmIndexMaintainer maintainer = new ArmIndexMaintainer();
		/**
		 * The handle of every mutation of the arm.
		 */
		@Nonnull private final MutationHandle handle;
		/**
		 * The reference whose partitions the arm keeps.
		 */
		@Nonnull private final String referenceName;
		/**
		 * The primary keys of the partitions the arm keeps, in the order they were seeded.
		 */
		@Nonnull private final List<Integer> partitionPrimaryKeys = new ArrayList<>(2);

		/**
		 * Creates the arm with an empty type index of the reference.
		 *
		 * @param handle        the handle of every mutation of the arm
		 * @param referenceName the reference whose partitions the arm keeps
		 */
		Arm(@Nonnull MutationHandle handle, @Nonnull String referenceName) {
			this.handle = handle;
			this.referenceName = referenceName;
			this.maintainer.register(
				new ReferencedTypeEntityIndex(
					900, VirtualPartitionWriteArmEquivalenceTest.this.productSchema.getName(),
					ReferenceIndexMutator.getReferencedTypeIndexKey(referenceName, Scope.LIVE)
				)
			);
		}

		/**
		 * Registers a single-owner partition of the owner as a partition entering its first owner registers it - its
		 * registry row, listed for its target in the type index - and, when asked to, its index object holding the
		 * owner.
		 *
		 * @param key        the key of the partition
		 * @param primaryKey the primary key of the partition
		 * @param withObject TRUE to keep an index object of the partition next to its row
		 */
		void seed(@Nonnull RepresentativeReferenceKey key, int primaryKey, boolean withObject) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex();
			VirtualReferencePartitionWriter.partitionEntered(
				typeIndex.getVirtualReferencePartitionIndex(), primaryKey, OWNER_PK, key
			);
			typeIndex.insertPrimaryKeyIfMissing(primaryKey, key.primaryKey());
			if (withObject) {
				final ReducedEntityIndex partition = new ReducedEntityIndex(
					primaryKey, VirtualPartitionWriteArmEquivalenceTest.this.productSchema.getName(),
					ReferencePartitions.createKey(Scope.LIVE, key)
				);
				partition.insertPrimaryKeyIfMissing(OWNER_PK);
				this.maintainer.register(partition);
			}
			this.partitionPrimaryKeys.add(primaryKey);
		}

		/**
		 * Creates the executor of one mutation of the owner, written with the arm's handle.
		 *
		 * @return the executor
		 */
		@Nonnull
		EntityIndexLocalMutationExecutor newExecutor() {
			return newExecutor(this.handle);
		}

		/**
		 * Creates the executor of one mutation of the owner over the arm's indexes, written with the given handle.
		 *
		 * @param mutationHandle the handle of the mutation
		 * @return the executor
		 */
		@Nonnull
		EntityIndexLocalMutationExecutor newExecutor(@Nonnull MutationHandle mutationHandle) {
			final VirtualPartitionWriteArmEquivalenceTest outer = VirtualPartitionWriteArmEquivalenceTest.this;
			return new EntityIndexLocalMutationExecutor(
				outer.storage,
				OWNER_PK,
				this.maintainer,
				new MockEntityIndexCreator<>(outer.catalogIndex),
				() -> outer.productSchema,
				() -> {
					throw new UnsupportedOperationException("Not supported in the test.");
				},
				() -> {
					throw new UnsupportedOperationException("Not supported in the test.");
				},
				null,
				null,
				null,
				outer.classifierResolver,
				outer.usageRegistry,
				outer.catalogUsageRegistry,
				true,
				mutationHandle
			);
		}

		/**
		 * Returns the type index of the arm's reference.
		 *
		 * @return the type index
		 */
		@Nonnull
		ReferencedTypeEntityIndex typeIndex() {
			return (ReferencedTypeEntityIndex) this.maintainer.getIndexIfExists(
				ReferenceIndexMutator.getReferencedTypeIndexKey(this.referenceName, Scope.LIVE)
			);
		}

		/**
		 * Returns the global index of the arm, created by the first mutation it replayed.
		 *
		 * @return the global index
		 */
		@Nonnull
		GlobalEntityIndex globalIndex() {
			return (GlobalEntityIndex) this.maintainer.getIndexIfExists(
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
			);
		}

		/**
		 * Returns the index object of a partition the arm keeps one of.
		 *
		 * @param key the key of the partition
		 * @return the index object
		 */
		@Nonnull
		ReducedEntityIndex partitionObject(@Nonnull RepresentativeReferenceKey key) {
			return (ReducedEntityIndex) this.maintainer.getIndexIfExists(
				ReferencePartitions.createKey(Scope.LIVE, key)
			);
		}

		/**
		 * Returns the primary keys of the partitions the arm keeps.
		 *
		 * @return the primary keys
		 */
		@Nonnull
		int[] partitionPrimaryKeys() {
			return this.partitionPrimaryKeys.stream().mapToInt(Integer::intValue).toArray();
		}

		/**
		 * Returns what the registry holds: its declared keys and entry count and, for each of the partitions, its
		 * owner, its representative tuple and its sort values and chain predecessors, rendered for comparison.
		 *
		 * @param partitionPrimaryKeys the partitions to render
		 * @return the registry by the name of each of its parts
		 */
		@Nonnull
		Map<String, String> snapshot(@Nonnull int... partitionPrimaryKeys) {
			final VirtualReferencePartitionIndex registry = typeIndex().getVirtualReferencePartitionIndex();
			final Map<String, String> snapshot = new TreeMap<>();
			snapshot.put("entries", String.valueOf(registry.getEntryCount()));
			snapshot.put("declared sort keys", render(registry.getDeclaredSortKeys()));
			snapshot.put("declared chain keys", render(registry.getDeclaredChainKeys()));
			for (final int partitionPrimaryKey : partitionPrimaryKeys) {
				final OptionalLong owner = registry.getPartitionOwner(partitionPrimaryKey);
				if (owner.isEmpty()) {
					continue;
				}
				final String prefix = partitionPrimaryKeys.length == 1 ? "" : partitionPrimaryKey + " ";
				snapshot.put(prefix + "owner", String.valueOf(owner.getAsLong()));
				snapshot.put(
					prefix + "tuple",
					Arrays.toString(registry.getTuple(registry.getRepresentativeTupleId(partitionPrimaryKey)))
				);
				for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
					if (registry.hasSortValue(key, partitionPrimaryKey)) {
						final Serializable value = registry.getNormalizedSortValue(
							key, registry.getSortValueId(key, partitionPrimaryKey)
						);
						snapshot.put(
							prefix + "sort " + describe(key),
							value instanceof Serializable[] array ? Arrays.deepToString(array) : String.valueOf(value)
						);
					}
				}
				for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
					final OptionalInt predecessor = registry.getChainPredecessor(key, partitionPrimaryKey);
					if (predecessor.isPresent()) {
						snapshot.put(prefix + "chain " + describe(key), String.valueOf(predecessor.getAsInt()));
					}
				}
			}
			return snapshot;
		}

		/**
		 * Serializes the storage parts the registry would flush now, with the production serializer.
		 *
		 * @return the bytes of the flushed parts
		 */
		@Nonnull
		byte[] serializedRegistry() {
			final ReferencedTypeEntityIndex typeIndex = typeIndex();
			final TrappedChanges sink = new TrappedChanges();
			typeIndex.getVirtualReferencePartitionIndex().appendStorageParts(
				typeIndex.getPrimaryKey(), this.referenceName, sink
			);
			final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
			final VirtualReferencePartitionIndexStoragePartSerializer serializer =
				new VirtualReferencePartitionIndexStoragePartSerializer(keyCompressor);
			final Kryo kryo = KryoFactory.createKryo(
				SharedClassesConfigurer.INSTANCE
					.andThen(SchemaKryoConfigurer.INSTANCE)
					.andThen(new IndexStoragePartConfigurer(keyCompressor))
			);
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(bytes, 4_096)) {
				final Iterator<StoragePart> parts = sink.getTrappedChangesIterator();
				while (parts.hasNext()) {
					final StoragePart part = parts.next();
					if (part instanceof VirtualReferencePartitionIndexStoragePart registryPart) {
						serializer.write(kryo, output, registryPart);
					} else {
						output.writeString(part.getClass().getName());
					}
				}
			}
			return bytes.toByteArray();
		}

		/**
		 * Renders a set of registry keys in a stable order.
		 *
		 * @param keys the keys
		 * @return the rendering
		 */
		@Nonnull
		private String render(@Nonnull Set<AttributeIndexKey> keys) {
			return keys.stream().map(VirtualPartitionWriteArmEquivalenceTest::describe).sorted().toList().toString();
		}

		@Override
		public String toString() {
			return this.handle.writtenVirtually() ? "virtual arm" : "object arm";
		}
	}

	/**
	 * The storage of the owner: its body, whose attribute locales follow the locales it gains and loses, the one
	 * reference it has, and the locales the running mutation added or removed.
	 */
	private static final class OwnerStorage extends MockStorageContainerAccessor {
		/**
		 * The reference of the owner.
		 */
		@Nullable private Reference reference;
		/**
		 * The references part of the owner, holding {@link #reference}.
		 */
		@Nonnull private ReferencesStoragePart referencesPart = new ReferencesStoragePart(OWNER_PK);
		/**
		 * The locales the running mutation added.
		 */
		@Nonnull private LocaleWithScope[] addedLocales = new LocaleWithScope[0];
		/**
		 * The locales the running mutation removed.
		 */
		@Nonnull private LocaleWithScope[] removedLocales = new LocaleWithScope[0];
		/**
		 * Changes with every change of the locales, as the storage executor's identity hash does.
		 */
		private int localesIdentityHash;

		/**
		 * Returns the reference of the owner.
		 *
		 * @return the reference
		 */
		@Nonnull
		Reference getReference() {
			return Objects.requireNonNull(this.reference);
		}

		/**
		 * Replaces the reference of the owner.
		 *
		 * @param reference the reference as the mutation left it
		 */
		void setReference(@Nonnull Reference reference) {
			this.reference = reference;
			this.referencesPart = new ReferencesStoragePart(OWNER_PK, 1, new Reference[]{reference}, -1);
		}

		/**
		 * Makes the owner gain the locale in the running mutation.
		 *
		 * @param locale the locale
		 */
		void localeAdded(@Nonnull Locale locale) {
			body().addAttributeLocale(locale);
			this.addedLocales = new LocaleWithScope[]{
				new LocaleWithScope(locale, EnumSet.of(LocaleScope.ENTITY, LocaleScope.ATTRIBUTE))
			};
			this.localesIdentityHash++;
		}

		/**
		 * Makes the owner lose the locale in the running mutation.
		 *
		 * @param locale the locale
		 */
		void localeRemoved(@Nonnull Locale locale) {
			body().removeAttributeLocale(locale);
			this.removedLocales = new LocaleWithScope[]{
				new LocaleWithScope(locale, EnumSet.of(LocaleScope.ENTITY, LocaleScope.ATTRIBUTE))
			};
			this.localesIdentityHash++;
		}

		/**
		 * Forgets the locales the finished mutation added or removed.
		 */
		void mutationFinished() {
			if (this.addedLocales.length > 0 || this.removedLocales.length > 0) {
				this.addedLocales = new LocaleWithScope[0];
				this.removedLocales = new LocaleWithScope[0];
				this.localesIdentityHash++;
			}
		}

		/**
		 * Returns the body of the owner.
		 *
		 * @return the body
		 */
		@Nonnull
		private EntityBodyStoragePart body() {
			return getEntityStoragePart(ENTITY_NAME, OWNER_PK, EntityExistence.MUST_EXIST);
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			return this.referencesPart;
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getAddedLocales() {
			return this.addedLocales;
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getRemovedLocales() {
			return this.removedLocales;
		}

		@Override
		public int getLocalesIdentityHash() {
			return this.localesIdentityHash;
		}
	}

	/**
	 * Index maintainer over real indexes that creates an index for a key it does not hold, as the collection's
	 * maintainer does, and counts the partition objects it holds and every acquisition of one. Unlike the
	 * collection's maintainer it answers `null` for a primary key it holds no index of.
	 */
	private final class ArmIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {
		/**
		 * The indexes by key.
		 */
		@Nonnull private final Map<EntityIndexKey, EntityIndex> indexes = CollectionUtils.createHashMap(8);
		/**
		 * The indexes by primary key.
		 */
		@Nonnull private final Map<Integer, EntityIndex> indexesByPrimaryKey = CollectionUtils.createHashMap(8);
		/**
		 * The number of acquisitions of a partition object, by key or by primary key.
		 */
		private int partitionAcquisitions;
		/**
		 * The primary key of the next index the maintainer creates.
		 */
		private int nextPrimaryKey = 1_000;

		/**
		 * Registers an index under its own key and primary key.
		 *
		 * @param index the index
		 */
		void register(@Nonnull EntityIndex index) {
			this.indexes.put(index.getIndexKey(), index);
			this.indexesByPrimaryKey.put(index.getPrimaryKey(), index);
		}

		/**
		 * Returns the number of partition objects the maintainer holds.
		 *
		 * @return the number of partition objects
		 */
		int getPartitionObjectCount() {
			return (int) this.indexes.keySet().stream()
				.filter(it -> it.type() == EntityIndexType.REFERENCED_ENTITY)
				.count();
		}

		/**
		 * Returns the number of acquisitions of a partition object.
		 *
		 * @return the number of acquisitions
		 */
		int getPartitionAcquisitions() {
			return this.partitionAcquisitions;
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			if (entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY) {
				this.partitionAcquisitions++;
			}
			final EntityIndex existing = this.indexes.get(entityIndexKey);
			if (existing != null) {
				return existing;
			}
			final EntityIndex created = createIndex(entityIndexKey);
			register(created);
			return created;
		}

		@SuppressWarnings("DataFlowIssue")
		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			final EntityIndex index = this.indexesByPrimaryKey.get(indexPrimaryKey);
			if (index != null && index.getIndexKey().type() == EntityIndexType.REFERENCED_ENTITY) {
				this.partitionAcquisitions++;
			}
			return index;
		}

		@Nullable
		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexes.get(entityIndexKey);
		}

		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			return this.indexesByPrimaryKey.get(indexPrimaryKey);
		}

		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			final EntityIndex removed = this.indexes.remove(entityIndexKey);
			if (removed != null) {
				this.indexesByPrimaryKey.remove(removed.getPrimaryKey());
			}
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
			throw new UnsupportedOperationException("The test maintainer does not allocate partition primary keys");
		}

		/**
		 * Creates an empty index of the key's type, as the collection's maintainer does.
		 *
		 * @param key the key
		 * @return a fresh index
		 */
		@Nonnull
		private EntityIndex createIndex(@Nonnull EntityIndexKey key) {
			final int primaryKey = this.nextPrimaryKey++;
			final String entityType = VirtualPartitionWriteArmEquivalenceTest.this.productSchema.getName();
			return switch (key.type()) {
				case REFERENCED_ENTITY -> new ReducedEntityIndex(primaryKey, entityType, key);
				case REFERENCED_ENTITY_TYPE -> new ReferencedTypeEntityIndex(primaryKey, entityType, key);
				case GLOBAL -> new GlobalEntityIndex(primaryKey, entityType, key);
				default -> throw new UnsupportedOperationException(
					"The test maintainer does not create indexes of type " + key.type()
				);
			};
		}
	}

}
