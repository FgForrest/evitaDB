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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Keeps the {@link VirtualReferencePartitionIndex} in step with the reference partitions it describes. It is told about
 * the transitions of a partition's owner count the reference index mutator decides, and about the sort values and
 * chain predecessors the attribute index mutator writes into a partition, and about nothing else.
 *
 * # Owner rows
 *
 * The transitions of a partition's owner count decide its row:
 *
 * - **entered** (0 -> 1) - the partition gained its only owner: its row is registered, naming the owner, the entity
 *   the partition references and its representative attribute values. The mutator registers it before it replicates
 *   the owner's data into the partition, so the row is in place while that data is being written;
 * - **shared** (1 -> 2) - the partition gained a second owner and is no longer a single-owner one: everything the
 *   index records about it is dropped at once;
 * - **vacated** (1 -> 0) - the partition lost its last owner: everything the index records about it is dropped.
 *
 * Every drop is followed by the retirement of the declared sort and chain keys when no partition has an owner row
 * left, so an index without a single row declares no key either - a key nothing can hold would keep the index, and the
 * referenced-type entity index carrying it, from ever being empty.
 *
 * A partition that goes from two owners back to one (2 -> 1) is not decided here: whether it is a single-owner one
 * again is known only once the mutation that took the owner away has finished, so its row is a matter for the end of
 * that mutation.
 *
 * Only references indexed `FOR_FILTERING` with the referenced entity component in the scope have virtual partitions
 * (see {@link #isShadowed(ReferenceSchemaContract, Scope)}); a reference indexed for partitioning keeps a reduced
 * entity index for every partition.
 *
 * # Sort values and chain predecessors
 *
 * Every sort value and chain predecessor of a reference attribute or a sortable attribute compound of the reference
 * written into a partition, or removed from it, is mirrored into the registry right after the partition itself was
 * written - the value the partition orders its owner by becomes the value the registry orders the partition by:
 *
 * - only an attribute sortable in the partition's scope is mirrored, exactly as only such an attribute reaches the
 *   partition's sort or chain index - a filterable or unique attribute next to it is not;
 * - only a partition whose row names the mutated entity as its owner is mirrored. A partition without a row - one with
 *   several owners, or one left with a single owner of two whose row is not back yet - is not, and neither is the data
 *   of another owner written into a partition that has a row: the owner, not the partition's current owner count,
 *   decides, because the owner count already changed when a departing owner's data is removed;
 * - a sort value is mirrored strictly - a value is recorded only for a partition that holds none for the key, and
 *   removed only when it is exactly the value the partition held - so a mirror that disagrees with the partition
 *   fails the mutation instead of drifting. A chain predecessor is upserted, as the chain index upserts it, and
 *   removed only when it is recorded;
 * - a key is declared with its first value: a plain attribute with the comparator base and decimal places its sort
 *   index has, a compound with the comparator base its elements define. It stays declared while partitions have rows,
 *   even when no partition holds a value for it.
 *
 * The values are recorded as the attribute values the partition receives - the elements of a compound as they were
 * before the partition's sort index normalized its copy of them - and normalized by the registry as a sort index
 * normalizes them.
 *
 * The referenced-type entity index carrying the registry is acquired for writing only when something is mirrored, and
 * without joining the indexes the end of the mutation inspects for emptiness: the registry is a shadow of the
 * partitions and must not change which indexes the mutation is seen to have touched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReferencePartitionWriter {

	private VirtualReferencePartitionWriter() {
	}

	/**
	 * Returns TRUE when the partitions of the reference in the scope may be virtual - the reference is indexed
	 * `FOR_FILTERING`, not for partitioning, and with the referenced entity component.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @return TRUE when the registry keeps rows for the reference in the scope
	 */
	static boolean isShadowed(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING &&
			referenceSchema.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY);
	}

	/**
	 * Registers the row of a partition that has just gained its only owner (0 -> 1).
	 *
	 * @param registry                   the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey        the primary key of the partition
	 * @param ownerPrimaryKey            the primary key of its only owner
	 * @param representativeReferenceKey the key of the partition - the referenced entity and the representative
	 *                                   attribute values
	 */
	static void partitionEntered(
		@Nonnull VirtualReferencePartitionIndex registry,
		int partitionPrimaryKey,
		int ownerPrimaryKey,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		registry.putPartitionOwner(partitionPrimaryKey, ownerPrimaryKey, representativeReferenceKey.primaryKey());
		// a reference without representative attributes has the empty tuple, which the registry stands for by absence
		if (representativeReferenceKey.representativeAttributeValues().length > 0) {
			registry.putRepresentativeTuple(
				partitionPrimaryKey, representativeReferenceKey.representativeAttributeValues()
			);
		}
	}

	/**
	 * Drops the row of a partition that has just gained its second owner (1 -> 2).
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	static void partitionShared(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		dropPartition(registry, partitionPrimaryKey);
	}

	/**
	 * Drops the row of a partition that has just lost its last owner (1 -> 0).
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	static void partitionVacated(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		dropPartition(registry, partitionPrimaryKey);
	}

	/**
	 * Returns the partition an attribute index write concerns when the registry may mirror it - the index is a
	 * partition of the given reference, whose partitions may be virtual in the partition's scope.
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the written attribute or compound belongs to, `null` for an entity one
	 * @param index           the index written into
	 * @return the partition, or `null` when the write is not mirrored
	 */
	@Nullable
	static ReferencePartition getMirroredPartition(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex index
	) {
		// TODO JNO - remove at the end of #1615
		final MutationHandle mutation = executor.getVirtualReferencePartitionMutation();
		// TODO JNO - remove at the end of #1615 (the whole condition)
		if (!mutation.maintained() && mutation.probe() == null) {
			return null;
		}
		final ReferencePartition partition = ReferencePartitions.asPartition(index);
		if (referenceSchema == null || partition == null) {
			return null;
		}
		return referenceSchema.getName().equals(partition.getRepresentativeReferenceKey().referenceName()) &&
			isShadowed(referenceSchema, partition.getIndexKey().scope()) ? partition : null;
	}

	/**
	 * Mirrors an attribute value just written into a partition: a sort value is recorded, a chain predecessor upserted.
	 * See the class documentation for which writes are mirrored.
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the attribute belongs to, `null` for an entity attribute
	 * @param attributeSchema the attribute
	 * @param index           the index the value was written into
	 * @param locale          the locale of the value, `null` for a language-agnostic attribute
	 * @param value           the value as the index received it
	 */
	static void attributeValueAdded(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull EntityIndex index,
		@Nullable Locale locale,
		@Nonnull Serializable value
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, index);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final Scope scope = partition.getIndexKey().scope();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale);
		// only an attribute of the reference sortable in the scope reaches the partition's sort or chain index
		if (!attributeSchema.isSortableInScope(scope) || !referenceName.equals(key.referenceName())) {
			return;
		}
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, referenceName, scope, partitionPrimaryKey
		);
		if (registry != null) {
			if (value instanceof final ChainableType predecessor) {
				registry.declareChainKey(key);
				registry.putChainPredecessor(key, partitionPrimaryKey, predecessor.predecessorPk());
			} else {
				// the comparator base and the decimal places the partition's own sort index of the attribute has
				final ComparatorSource comparatorSource = new ComparatorSource(
					attributeSchema.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST
				);
				registry.declareSortKey(
					key, new ComparatorSource[]{comparatorSource}, attributeSchema.getIndexedDecimalPlaces()
				);
				assertNoSortValue(registry, key, partitionPrimaryKey, value);
				registry.putSortValue(key, partitionPrimaryKey, value);
			}
		}
		// TODO JNO - remove at the end of #1615
		executor.getVirtualReferencePartitionMutation().transitionPassed(
			TransitionStage.MIRRORED, referenceName, scope, partitionPrimaryKey
		);
	}

	/**
	 * Mirrors an attribute value just removed from a partition: the sort value is removed, provided it is exactly the
	 * removed value, or the chain predecessor, provided one is recorded. See the class documentation for which writes
	 * are mirrored.
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the attribute belongs to, `null` for an entity attribute
	 * @param attributeSchema the attribute
	 * @param index           the index the value was removed from
	 * @param locale          the locale of the value, `null` for a language-agnostic attribute
	 * @param value           the value as the index received it for the removal
	 */
	static void attributeValueRemoved(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull EntityIndex index,
		@Nullable Locale locale,
		@Nonnull Serializable value
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, index);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final Scope scope = partition.getIndexKey().scope();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale);
		// only an attribute of the reference sortable in the scope reaches the partition's sort or chain index
		if (!attributeSchema.isSortableInScope(scope) || !referenceName.equals(key.referenceName())) {
			return;
		}
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, referenceName, scope, partitionPrimaryKey
		);
		if (registry != null) {
			// the partition picks its chain or sort index by the declared type, and so does the mirror
			if (ChainableType.class.isAssignableFrom(attributeSchema.getPlainType())) {
				if (!registry.removeChainPredecessor(key, partitionPrimaryKey)) {
					throw new GenericEvitaInternalError(
						"Partition " + partitionPrimaryKey + " has no predecessor of `" + key + "` in the registry " +
							"of single-owner partitions, though its owner had one in the partition!"
					);
				}
			} else {
				assertSortValue(registry, key, partitionPrimaryKey, value);
				registry.removeSortValue(key, partitionPrimaryKey);
			}
		}
		// TODO JNO - remove at the end of #1615
		executor.getVirtualReferencePartitionMutation().transitionPassed(
			TransitionStage.MIRRORED, referenceName, scope, partitionPrimaryKey
		);
	}

	/**
	 * Returns a copy of the values of a sortable attribute compound about to be written into, or removed from, an
	 * index when the registry may mirror the write - the sort index of the compound normalizes the array it receives
	 * in place, and the registry records the values as they were.
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the compound belongs to, `null` for an entity compound
	 * @param index           the index about to be written
	 * @param values          the values of the compound elements, in element order
	 * @return the copy, or `null` when the write is not mirrored
	 */
	@Nullable
	static Serializable[] copyIfMirrored(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex index,
		@Nonnull Serializable[] values
	) {
		return getMirroredPartition(executor, referenceSchema, index) == null ?
			null : Arrays.copyOf(values, values.length);
	}

	/**
	 * Mirrors the value of a sortable attribute compound just written into a partition. See the class documentation
	 * for which writes are mirrored.
	 *
	 * @param executor              the mutation executor
	 * @param entitySchema          the entity schema
	 * @param referenceSchema       the reference the compound belongs to, `null` for an entity compound
	 * @param compoundSchema        the sortable attribute compound
	 * @param attributeTypeProvider resolves the value type of each element attribute by name
	 * @param index                 the index the value was written into
	 * @param locale                the locale of the value, `null` for a language-agnostic compound
	 * @param values                the values of the compound elements as they were before the index received them,
	 *                              see {@link #copyIfMirrored}
	 */
	static void compoundValueAdded(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nonnull EntityIndex index,
		@Nullable Locale locale,
		@Nonnull Serializable[] values
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, index);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(
			entitySchema, referenceSchema, compoundSchema, locale
		);
		// an entity compound has no reference name, and only the reference's own compounds are mirrored
		if (!referenceName.equals(key.referenceName())) {
			return;
		}
		final Scope scope = partition.getIndexKey().scope();
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, referenceName, scope, partitionPrimaryKey
		);
		if (registry != null) {
			// a compound's sort index keeps its `BigDecimal` elements exact, so no decimal places apply
			registry.declareSortKey(key, AttributeIndex.createComparatorBase(compoundSchema, attributeTypeProvider), 0);
			assertNoSortValue(registry, key, partitionPrimaryKey, values);
			registry.putSortValue(key, partitionPrimaryKey, values);
		}
		// TODO JNO - remove at the end of #1615
		executor.getVirtualReferencePartitionMutation().transitionPassed(
			TransitionStage.MIRRORED, referenceName, scope, partitionPrimaryKey
		);
	}

	/**
	 * Mirrors the value of a sortable attribute compound just removed from a partition, provided it is exactly the
	 * value the registry records. See the class documentation for which writes are mirrored.
	 *
	 * @param executor        the mutation executor
	 * @param entitySchema    the entity schema
	 * @param referenceSchema the reference the compound belongs to, `null` for an entity compound
	 * @param compoundSchema  the sortable attribute compound
	 * @param index           the index the value was removed from
	 * @param locale          the locale of the value, `null` for a language-agnostic compound
	 * @param values          the values of the compound elements as they were before the index received them, see
	 *                        {@link #copyIfMirrored}
	 */
	static void compoundValueRemoved(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull EntityIndex index,
		@Nullable Locale locale,
		@Nonnull Serializable[] values
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, index);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(
			entitySchema, referenceSchema, compoundSchema, locale
		);
		// an entity compound has no reference name, and only the reference's own compounds are mirrored
		if (!referenceName.equals(key.referenceName())) {
			return;
		}
		final Scope scope = partition.getIndexKey().scope();
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, referenceName, scope, partitionPrimaryKey
		);
		if (registry != null) {
			assertSortValue(registry, key, partitionPrimaryKey, values);
			registry.removeSortValue(key, partitionPrimaryKey);
		}
		// TODO JNO - remove at the end of #1615
		executor.getVirtualReferencePartitionMutation().transitionPassed(
			TransitionStage.MIRRORED, referenceName, scope, partitionPrimaryKey
		);
	}

	/**
	 * Returns the registry of the reference in the scope when the partition's row names the mutated entity as its
	 * owner, acquired for writing - registered for modification so that its change is persisted, but without joining
	 * the indexes the end of the mutation inspects for emptiness.
	 *
	 * @param executor            the mutation executor
	 * @param referenceName       the reference
	 * @param scope               the scope of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @return the registry, or `null` when the partition has no row or its row names another owner
	 */
	@Nullable
	private static VirtualReferencePartitionIndex getRegistryOfOwnPartition(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partitionPrimaryKey
	) {
		// TODO JNO - remove at the end of #1615 (the whole condition)
		if (!executor.getVirtualReferencePartitionMutation().maintained()) {
			return null;
		}
		final EntityIndex typeIndex = executor.getIndexIfExists(
			ReferenceIndexMutator.getReferencedTypeIndexKey(referenceName, scope)
		);
		if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
			return null;
		}
		final OptionalLong row = referencedTypeIndex.getVirtualReferencePartitionIndex()
			.getPartitionOwner(partitionPrimaryKey);
		if (row.isEmpty() || NumberUtils.unpackHigh(row.getAsLong()) != executor.getOwnerPrimaryKey()) {
			return null;
		}
		return ((ReferencedTypeEntityIndex) executor.getIndexForSideWrite(referencedTypeIndex.getIndexKey()))
			.getVirtualReferencePartitionIndex();
	}

	/**
	 * Fails when the partition already has a sort value recorded for the key - a value written into a partition
	 * always follows the removal of the previous one, so the registry must have lost track of the partition.
	 *
	 * @param registry            the registry
	 * @param key                 the declared sort key
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param value               the value about to be recorded, for the message
	 */
	private static void assertNoSortValue(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey key,
		int partitionPrimaryKey,
		@Nonnull Serializable value
	) {
		if (registry.hasSortValue(key, partitionPrimaryKey)) {
			throw new GenericEvitaInternalError(
				"Partition " + partitionPrimaryKey + " already has a sort value of `" + key + "` in the registry of " +
					"single-owner partitions, though its owner had none in the partition before `" + describe(value) +
					"` was written!"
			);
		}
	}

	/**
	 * Fails when the partition does not have exactly the given sort value recorded for the key - the value removed from
	 * the partition must be the value the registry recorded for it.
	 *
	 * @param registry            the registry
	 * @param key                 the declared sort key
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param value               the value removed from the partition
	 */
	private static void assertSortValue(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey key,
		int partitionPrimaryKey,
		@Nonnull Serializable value
	) {
		if (!registry.hasSortValue(key, partitionPrimaryKey, value)) {
			throw new GenericEvitaInternalError(
				"Partition " + partitionPrimaryKey + " does not have the sort value `" + describe(value) + "` of `" +
					key + "` in the registry of single-owner partitions, though its owner had it in the partition!"
			);
		}
	}

	/**
	 * Renders a sort value for a failure message.
	 *
	 * @param value the attribute value, or the values of the compound elements
	 * @return the rendering
	 */
	@Nonnull
	private static String describe(@Nonnull Serializable value) {
		return value instanceof final Serializable[] values ? Arrays.toString(values) : String.valueOf(value);
	}

	/**
	 * Drops everything the registry records about the partition and retires the declared keys when no partition has
	 * an owner row left.
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	private static void dropPartition(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		registry.removePartition(partitionPrimaryKey);
		registry.retireAllKeysIfNoPartition();
	}

}
