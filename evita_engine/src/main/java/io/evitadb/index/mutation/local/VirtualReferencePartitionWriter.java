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
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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
 * A partition that goes from two owners back to one (2 -> 1) is not decided when the owner leaves: another owner may
 * join it later in the same root mutation, so whether it is a single-owner one again is known only once every
 * mutation of the root mutation has been dispatched. The executor that took the owner away decides it when it applies
 * its changes:
 *
 * - **demoted** (2 -> 1, and still one owner then) - the partition's row is registered for its remaining owner and
 *   filled by **extraction**: every sort value, compound value and chain predecessor the partition holds for that
 *   owner is copied into the registry, read by the owner's record - the referenced entity's record for a
 *   `ReferencedEntityPredecessor` chain, which the partition indexes under the referenced entity rather than the
 *   owner. The values are copied as the partition's sort indexes store them, in their normalized form, and recorded
 *   as such (see {@link VirtualReferencePartitionIndex#putNormalizedSortValue}). A partition that has a row by then -
 *   another executor of the root mutation demoted it already, or it was vacated and entered again meanwhile - keeps
 *   it, and one that has no owner or several owners by then gets none. A mutation writing its single-owner
 *   partitions virtually then retires the partition's index object, so the row alone represents the partition.
 *
 * The opposite move - a partition kept as a row gaining its second owner - is the reference index mutator's: it
 * installs the partition's index object and writes the first owner into it before the second owner enters, and the
 * entering drops the row as for any partition becoming a shared one.
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
 *   fails the mutation instead of drifting. A chain predecessor is upserted, as the chain index upserts it, and its
 *   removal requires one to already be recorded - an absent one fails the mutation instead of being silently
 *   skipped;
 * - a key is declared with its first value: a plain attribute with the comparator base and decimal places its sort
 *   index has, a compound with the comparator base its elements define. It stays declared while partitions have rows,
 *   even when no partition holds a value for it.
 *
 * The values are recorded as the attribute values the partition receives - the elements of a compound as they were
 * before the partition's sort index normalized its copy of them - and normalized by the registry as a sort index
 * normalizes them.
 *
 * # Virtual partitions
 *
 * A write may be directed at a {@link VirtualReferencePartition} - a partition represented by its row only, with no
 * index object to receive the write. The attribute index mutator then skips the object write and the mirror is the
 * only writer of the value, so nothing it would otherwise pass over may be passed over silently: the write fails
 * when the mutation does not maintain the registry, when the partition is not one of the written reference whose
 * partitions may be virtual, when the value belongs to the owner entity rather than to the reference, or when the
 * partition's row is missing or names another owner. An attribute of the reference that is not sortable in the
 * partition's scope is the only value a virtual partition takes nothing of: an index object puts it into its filter
 * or unique index only, and a virtual partition keeps sort values and chain predecessors only.
 *
 * The referenced-type entity index carrying the registry is acquired for writing only when something is mirrored, and
 * without joining the indexes the end of the mutation inspects for emptiness: the registry is a shadow of the
 * partitions and must not change which indexes the mutation is seen to have touched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionWriter {

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
	public static boolean isShadowed(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
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
	 * Demotes a partition the mutation left with one owner of two (2 -> 1), once every mutation of the root mutation
	 * has been dispatched: when the partition still has exactly one owner and no row, its row is registered for that
	 * owner and filled by extraction - see the class documentation. The partition is resolved anew by its key, so a
	 * partition removed since, or replaced by another one of the same key, is left alone. A mutation writing its
	 * single-owner partitions virtually retires the partition's index object once its row is in place - this runs while
	 * the root mutation is finishing, when an index object may be retired but none may be installed.
	 *
	 * @param executor  the mutation executor that left the partition with one owner
	 * @param partition the partition as it was when it was left with one owner - only its key and primary key are read
	 */
	static void partitionLeftWithOneOwner(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencePartition partition
	) {
		final int partitionPrimaryKey = partition.getPrimaryKey();
		// the index object itself - the extraction below reads the partition's own sort and chain indexes, which a
		// partition has only as an index object
		final EntityIndex currentIndex = executor.getIndexIfExists(partition.getIndexKey());
		final ReferencePartition current = ReferencePartitions.asPartition(currentIndex);
		// emptied or shared again since it was left with one owner - there is nothing to demote
		if (current == null || current.getPrimaryKey() != partitionPrimaryKey || current.size() != 1) {
			return;
		}
		final String referenceName = current.getRepresentativeReferenceKey().referenceName();
		final Scope scope = current.getIndexKey().scope();
		// TODO JNO - remove at the end of #1615 (the condition, its body stays)
		if (executor.getVirtualReferencePartitionMutation().maintained()) {
			final EntityIndex typeIndex = executor.getIndexIfExists(
				ReferenceIndexMutator.getReferencedTypeIndexKey(referenceName, scope)
			);
			if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
				throw new GenericEvitaInternalError(
					"Partition " + partitionPrimaryKey + " of `" + referenceName + "` in " + scope + " has an owner, " +
						"but the reference has no referenced-type entity index to carry its row!"
				);
			}
			final OptionalLong row = referencedTypeIndex.getVirtualReferencePartitionIndex()
				.getPartitionOwner(partitionPrimaryKey);
			// a partition another executor of the root mutation demoted already, or one vacated and entered again
			// since, has its row - filled by that demotion or by the mirror
			if (row.isEmpty()) {
				final VirtualReferencePartitionIndex registry =
					((ReferencedTypeEntityIndex) executor.getIndexForSideWrite(referencedTypeIndex.getIndexKey()))
						.getVirtualReferencePartitionIndex();
				final int ownerPrimaryKey = current.getAllPrimaryKeys().getFirst();
				partitionEntered(
					registry, partitionPrimaryKey, ownerPrimaryKey, current.getRepresentativeReferenceKey()
				);
				final EntitySchemaContract entitySchema = executor.getEntitySchema();
				extractValuesOfOwner(
					registry, entitySchema, entitySchema.getReferenceOrThrowException(referenceName), current,
					currentIndex, ownerPrimaryKey
				);
			}
		}
		// TODO JNO - remove at the end of #1615 (the condition, its body stays)
		if (executor.getVirtualReferencePartitionMutation().writtenVirtually()) {
			// the row holds everything the object holds of the only owner now, so the row alone represents the
			// partition - an index object of a single-owner partition is never kept
			executor.retirePartitionObject(currentIndex);
		}
		// TODO JNO - remove at the end of #1615
		executor.getVirtualReferencePartitionMutation().transitionPassed(
			TransitionStage.DEMOTED, referenceName, scope, partitionPrimaryKey
		);
	}

	/**
	 * Copies every sort value, compound value and chain predecessor the partition holds for its only owner into the
	 * registry, declaring the keys they belong to. Each value is read by its record - the owner's, or the referenced
	 * entity's for a `ReferencedEntityPredecessor` chain - so a key the owner holds no value of is left out, whatever
	 * other records the partition's index holds. Sort values are copied in the normalized form the partition's sort
	 * index stores them in.
	 *
	 * Only the keys of the partition's own reference are read; a partition of a reference indexed `FOR_FILTERING` holds
	 * no other.
	 *
	 * The sort and chain indexes are read from the partition's index object: they are not part of the partition handle,
	 * because a virtual partition has none of its own.
	 *
	 * @param registry        the registry, holding the partition's row and nothing else of it
	 * @param entitySchema    the schema of the owner entity
	 * @param referenceSchema the reference the partition belongs to
	 * @param partition       the partition
	 * @param partitionIndex  the index object of the partition, whose sort and chain indexes are read
	 * @param ownerPrimaryKey the primary key of its only owner
	 */
	private static void extractValuesOfOwner(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferencePartition partition,
		@Nonnull EntityIndex partitionIndex,
		int ownerPrimaryKey
	) {
		final String referenceName = referenceSchema.getName();
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final ReferenceSchemaAttributeAndCompoundSchemaProvider schemaProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(entitySchema, referenceSchema);
		partitionIndex.forEachAttributeIndexKey(
			AttributeIndexType.SORT,
			key -> {
				if (!referenceName.equals(key.referenceName())) {
					return;
				}
				final SortIndex sortIndex = partitionIndex.getSortIndex(key);
				final Optional<Serializable> value = sortIndex == null ?
					Optional.empty() : sortIndex.getValueOfRecord(ownerPrimaryKey);
				if (value.isPresent()) {
					final Optional<? extends SortableAttributeCompoundSchemaContract> compoundSchema =
						referenceSchema.getSortableAttributeCompound(key.attributeName());
					if (compoundSchema.isPresent()) {
						declareSortKey(
							registry, key, compoundSchema.get(),
							attributeName -> schemaProvider.getAttributeSchema(attributeName).getPlainType()
						);
					} else {
						declareSortKey(registry, key, schemaProvider.getAttributeSchema(key.attributeName()));
					}
					registry.putNormalizedSortValue(key, partitionPrimaryKey, value.get());
				}
			}
		);
		partitionIndex.forEachAttributeIndexKey(
			AttributeIndexType.CHAIN,
			key -> {
				if (!referenceName.equals(key.referenceName())) {
					return;
				}
				// the type this collection's reference declares, which reflection may have inverted from the source's
				final boolean keyedByReferencedEntity = ReferencedEntityPredecessor.class.isAssignableFrom(
					schemaProvider.getAttributeSchema(key.attributeName()).getPlainType()
				);
				final ChainIndex chainIndex = partitionIndex.getChainIndex(key);
				final OptionalInt predecessor = chainIndex == null ?
					OptionalInt.empty() :
					chainIndex.getPredecessorPrimaryKey(
						keyedByReferencedEntity ? partition.getReferenceKey().primaryKey() : ownerPrimaryKey
					);
				if (predecessor.isPresent()) {
					registry.declareChainKey(key);
					registry.putChainPredecessor(key, partitionPrimaryKey, predecessor.getAsInt());
				}
			}
		);
	}

	/**
	 * Declares the sort key of a plain reference attribute with the comparator base and the decimal places the
	 * partition's own sort index of the attribute has.
	 *
	 * @param registry        the registry
	 * @param key             the sort key
	 * @param attributeSchema the attribute
	 */
	private static void declareSortKey(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey key,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		final ComparatorSource comparatorSource = new ComparatorSource(
			attributeSchema.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST
		);
		registry.declareSortKey(
			key, new ComparatorSource[]{comparatorSource}, attributeSchema.getIndexedDecimalPlaces()
		);
	}

	/**
	 * Declares the sort key of a sortable attribute compound of the reference with the comparator base its elements
	 * define.
	 *
	 * @param registry              the registry
	 * @param key                   the sort key
	 * @param compoundSchema        the compound
	 * @param attributeTypeProvider resolves the value type of each element attribute by name
	 */
	private static void declareSortKey(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull AttributeIndexKey key,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider
	) {
		// a compound's sort index keeps its `BigDecimal` elements exact, so no decimal places apply
		registry.declareSortKey(key, AttributeIndex.createComparatorBase(compoundSchema, attributeTypeProvider), 0);
	}

	/**
	 * Returns the partition an attribute index write concerns when the registry may mirror it - the write is directed
	 * at a partition of the given reference, whose partitions may be virtual in the partition's scope, or at a row the
	 * reference left while it was indexed for filtering only, which the acquisition released to a write taking content
	 * out of it (see {@link EntityIndexLocalMutationExecutor#isReleasedRowOfFormerIndexing}).
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the written attribute or compound belongs to, `null` for an entity one
	 * @param partition       the partition written into, `null` when the write is directed at an index that is not a
	 *                        partition
	 * @return the partition, or `null` when the write is not mirrored
	 * @throws GenericEvitaInternalError when a virtual partition's write would not be mirrored - see the class
	 *                                   documentation
	 */
	@Nullable
	private static ReferencePartition getMirroredPartition(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable ReferencePartition partition
	) {
		// TODO JNO - remove at the end of #1615
		final MutationHandle mutation = executor.getVirtualReferencePartitionMutation();
		// TODO JNO - remove at the end of #1615 (the whole condition)
		if (!mutation.maintained() && mutation.probe() == null) {
			if (partition instanceof final VirtualReferencePartition virtual) {
				throw virtualPartitionWriteLost(virtual, "the mutation does not maintain the registry");
			}
			return null;
		}
		if (partition == null) {
			return null;
		}
		if (
			referenceSchema != null &&
				referenceSchema.getName().equals(partition.getRepresentativeReferenceKey().referenceName()) &&
				(
					isShadowed(referenceSchema, partition.getIndexKey().scope()) ||
						executor.isReleasedRowOfFormerIndexing(partition)
				)
		) {
			return partition;
		}
		if (partition instanceof final VirtualReferencePartition virtual) {
			throw virtualPartitionWriteLost(
				virtual,
				referenceSchema == null ?
					"the write belongs to no reference" :
					"the write belongs to `" + referenceSchema.getName() + "` in a scope its partitions are not " +
						"virtual in, or to another reference"
			);
		}
		return null;
	}

	/**
	 * Mirrors an attribute value just written into a partition: a sort value is recorded, a chain predecessor upserted.
	 * See the class documentation for which writes are mirrored.
	 *
	 * @param executor        the mutation executor
	 * @param referenceSchema the reference the attribute belongs to, `null` for an entity attribute
	 * @param attributeSchema the attribute
	 * @param written         the partition the value was written into, `null` when it was written into an index
	 *                        that is not a partition
	 * @param locale          the locale of the value, `null` for a language-agnostic attribute
	 * @param value           the value as the partition received it
	 */
	static void attributeValueAdded(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable ReferencePartition written,
		@Nullable Locale locale,
		@Nonnull Serializable value
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, written);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final Scope scope = partition.getIndexKey().scope();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale);
		// only an attribute of the reference sortable in the scope reaches the partition's sort or chain index
		if (!isMirroredAttribute(partition, attributeSchema, scope, referenceName, key)) {
			return;
		}
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, partition, referenceName, scope
		);
		if (registry != null) {
			if (value instanceof final ChainableType predecessor) {
				registry.declareChainKey(key);
				registry.putChainPredecessor(key, partitionPrimaryKey, predecessor.predecessorPk());
			} else {
				declareSortKey(registry, key, attributeSchema);
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
	 * @param written         the partition the value was removed from, `null` when it was removed from an index that
	 *                        is not a partition
	 * @param locale          the locale of the value, `null` for a language-agnostic attribute
	 * @param value           the value as the partition received it for the removal
	 */
	static void attributeValueRemoved(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable ReferencePartition written,
		@Nullable Locale locale,
		@Nonnull Serializable value
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, written);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final Scope scope = partition.getIndexKey().scope();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale);
		// only an attribute of the reference sortable in the scope reaches the partition's sort or chain index
		if (!isMirroredAttribute(partition, attributeSchema, scope, referenceName, key)) {
			return;
		}
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, partition, referenceName, scope
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
	 * @param written         the partition about to be written, `null` when the index about to be written is not a
	 *                        partition
	 * @param values          the values of the compound elements, in element order
	 * @return the copy, or `null` when the write is not mirrored
	 */
	@Nullable
	static Serializable[] copyIfMirrored(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable ReferencePartition written,
		@Nonnull Serializable[] values
	) {
		return getMirroredPartition(executor, referenceSchema, written) == null ?
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
	 * @param written               the partition the value was written into, `null` when it was written into an index
	 *                              that is not a partition
	 * @param locale                the locale of the value, `null` for a language-agnostic compound
	 * @param values                the values of the compound elements as they were before the partition's index
	 *                              received them, see {@link #copyIfMirrored}
	 */
	static void compoundValueAdded(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable ReferencePartition written,
		@Nullable Locale locale,
		@Nonnull Serializable[] values
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, written);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(
			entitySchema, referenceSchema, compoundSchema, locale
		);
		// an entity compound has no reference name, and only the reference's own compounds are mirrored
		if (!isMirroredCompound(partition, referenceName, key)) {
			return;
		}
		final Scope scope = partition.getIndexKey().scope();
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, partition, referenceName, scope
		);
		if (registry != null) {
			declareSortKey(registry, key, compoundSchema, attributeTypeProvider);
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
	 * @param written         the partition the value was removed from, `null` when it was removed from an index that
	 *                        is not a partition
	 * @param locale          the locale of the value, `null` for a language-agnostic compound
	 * @param values          the values of the compound elements as they were before the partition's index received
	 *                        them, see {@link #copyIfMirrored}
	 */
	static void compoundValueRemoved(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable ReferencePartition written,
		@Nullable Locale locale,
		@Nonnull Serializable[] values
	) {
		final ReferencePartition partition = getMirroredPartition(executor, referenceSchema, written);
		if (partition == null) {
			return;
		}
		final String referenceName = partition.getRepresentativeReferenceKey().referenceName();
		final AttributeIndexKey key = AttributeIndex.createAttributeKey(
			entitySchema, referenceSchema, compoundSchema, locale
		);
		// an entity compound has no reference name, and only the reference's own compounds are mirrored
		if (!isMirroredCompound(partition, referenceName, key)) {
			return;
		}
		final Scope scope = partition.getIndexKey().scope();
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final VirtualReferencePartitionIndex registry = getRegistryOfOwnPartition(
			executor, partition, referenceName, scope
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
	 * @param executor      the mutation executor
	 * @param partition     the partition written into
	 * @param referenceName the reference
	 * @param scope         the scope of the partition
	 * @return the registry, or `null` when the partition has no row or its row names another owner
	 * @throws GenericEvitaInternalError when the partition is virtual and the registry is not returned - the partition
	 *                                   is its row, so a write into it the registry does not take would be lost
	 */
	@Nullable
	private static VirtualReferencePartitionIndex getRegistryOfOwnPartition(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencePartition partition,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final VirtualReferencePartitionIndex registry = findRegistryOfOwnPartition(
			executor, referenceName, scope, partition.getPrimaryKey()
		);
		if (registry == null && partition instanceof final VirtualReferencePartition virtual) {
			throw virtualPartitionWriteLost(
				virtual, "the registry holds no row of the partition owned by " + executor.getOwnerPrimaryKey()
			);
		}
		return registry;
	}

	/**
	 * Looks the registry of the reference in the scope up when the partition's row names the mutated entity as its
	 * owner, acquired for writing - see {@link #getRegistryOfOwnPartition}.
	 *
	 * @param executor            the mutation executor
	 * @param referenceName       the reference
	 * @param scope               the scope of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @return the registry, or `null` when the partition has no row or its row names another owner
	 */
	@Nullable
	private static VirtualReferencePartitionIndex findRegistryOfOwnPartition(
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
	 * Returns TRUE when the value of the attribute reaches the partition's sort or chain index - the attribute is one
	 * of the partition's own reference and sortable in its scope. An attribute of the owner entity written into a
	 * virtual partition fails: such a partition holds nothing of its owner's attributes. A non-sortable attribute of
	 * the reference is not mirrored, whichever the partition.
	 *
	 * @param partition       the partition written into
	 * @param attributeSchema the attribute
	 * @param scope           the scope of the partition
	 * @param referenceName   the reference of the partition
	 * @param key             the key of the attribute value
	 * @return TRUE when the value is mirrored
	 * @throws GenericEvitaInternalError when the attribute belongs to the owner entity and the partition is virtual
	 */
	private static boolean isMirroredAttribute(
		@Nonnull ReferencePartition partition,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull AttributeIndexKey key
	) {
		if (!referenceName.equals(key.referenceName())) {
			if (partition instanceof final VirtualReferencePartition virtual) {
				throw virtualPartitionWriteLost(virtual, "the attribute `" + key + "` belongs to the owner entity");
			}
			return false;
		}
		return attributeSchema.isSortableInScope(scope);
	}

	/**
	 * Returns TRUE when the value of the compound reaches the partition's sort index - the compound is one of the
	 * partition's own reference. A compound of the owner entity written into a virtual partition fails: such a
	 * partition holds nothing of its owner's attributes.
	 *
	 * @param partition     the partition written into
	 * @param referenceName the reference of the partition
	 * @param key           the key of the compound value
	 * @return TRUE when the value is mirrored
	 * @throws GenericEvitaInternalError when the compound belongs to the owner entity and the partition is virtual
	 */
	private static boolean isMirroredCompound(
		@Nonnull ReferencePartition partition,
		@Nonnull String referenceName,
		@Nonnull AttributeIndexKey key
	) {
		if (referenceName.equals(key.referenceName())) {
			return true;
		}
		if (partition instanceof final VirtualReferencePartition virtual) {
			throw virtualPartitionWriteLost(virtual, "the compound `" + key + "` belongs to the owner entity");
		}
		return false;
	}

	/**
	 * Creates the error of a write directed at a virtual partition that the registry would not take - the partition
	 * has no index object, so the write would be lost.
	 *
	 * @param partition the virtual partition written into
	 * @param reason    why the registry does not take the write
	 * @return the error to throw
	 */
	@Nonnull
	private static GenericEvitaInternalError virtualPartitionWriteLost(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull String reason
	) {
		return new GenericEvitaInternalError(
			"A write into virtual partition " + partition.getPrimaryKey() + " `" + partition.getIndexKey() +
				"` would be lost: " + reason + "!"
		);
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
