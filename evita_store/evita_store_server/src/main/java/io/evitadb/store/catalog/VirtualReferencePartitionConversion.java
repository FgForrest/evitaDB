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

package io.evitadb.store.catalog;

import com.carrotsearch.hppc.IntHashSet;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.component.loader.VirtualReferencePartitionLoader;
import io.evitadb.index.mutation.local.VirtualReferencePartitionWriter;
import io.evitadb.spi.store.catalog.header.HeaderInfoSupplier;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Converts the single-owner reference partitions of one collection written by evitaDB 2026.2 (storage protocol 6),
 * where every partition is an index object of its own, into rows of the registry of single-owner partitions - the
 * representation this version keeps them in. It is one step of the storage protocol migration
 * {@link Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_8}, which runs it for each collection after it
 * re-keyed the collection's other indexes, and publishes the result of both.
 *
 * # What it converts
 *
 * Every index object of a partition with a single owner, of a reference indexed for filtering with the referenced
 * entity component in the partition's scope ({@link VirtualReferencePartitionWriter#isShadowed}), is replaced by the
 * row the write path keeps for it: the same primary key, the same owner, the same referenced entity and
 * representative attribute values, and the sort values and chain predecessors the partition holds for its owner. The
 * row is written by {@link VirtualReferencePartitionWriter#demoteToRow} - the very demotion the write path runs when a
 * partition is left with one owner of two - so the conversion and the write path cannot drift apart. The partition's
 * storage parts are removed and its collection header no longer lists it; the referenced-type entity index keeps
 * advertising its primary key, as it advertises every partition, and the membership of its owner is rebuilt from the
 * row at every load.
 *
 * The partitions are classified against the schema the loaded catalog will have: the migration binds reflected
 * references to the references they reflect ({@link EntityCollection#bindReflectedReferences}) before it hands the
 * schema over, because a reflected reference may inherit its indexing from the reference it reflects, and the stored
 * schema alone does not say what it inherits.
 *
 * A catalog of protocol 6 holds no registry row: 2026.2 kept no registry, and a registry without a stored root loads
 * empty. A registry row met on the way is therefore a premise failure rather than something to rebuild - it would mean
 * the catalog was stamped with a protocol it was not written under.
 *
 * # What it leaves as it is
 *
 * A partition with several owners stays an index object, as the write path keeps it, and so does every partition of a
 * reference indexed for partitioning. A partition of a reference that is neither - the reference is gone from the
 * schema, or no longer indexed with the referenced entity component in the scope, or a partition without an owner or
 * without a referenced-type entity index to carry its row - is left as it is, and the operator is warned. Such a
 * partition never aborts the conversion.
 *
 * # Durability
 *
 * Nothing written here is published by it. The collection data store is only ever flushed through
 * {@link DefaultEntityCollectionPersistenceService#flush}, which appends records and an offset-index fragment no
 * published header names yet - it neither compacts a data store nor retires or deletes a file; compaction and file
 * retirement live in the catalog persistence service's own collection flush and bootstrap preparation, which the
 * conversion never calls. Removals are offset-index records, not files. The migration puts the new collection header
 * into the catalog data store unflushed and publishes all of it at once, with the catalog header of the current
 * protocol and one bootstrap record. A failure anywhere before that leaves the last published bootstrap record
 * pointing at the catalog as it was, so it loads as it was and a later run starts from it again.
 *
 * # Memory
 *
 * One registry and one partition are held at a time: the referenced-type entity indexes of a collection are converted
 * one by one - loading only their registries, never the entity indexes carrying them - and their partitions one by
 * one. The records written into the collection data store wait in memory until it is flushed, so the conversion
 * flushes it whenever the records it wrote since the last flush reach a threshold. The migration holds every
 * collection data store open while it runs, and the records the re-key wrote into one wait unflushed until the
 * conversion's first flush.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated removable together with {@link Migration_2026_3}, once no catalog older than 2026.3 can be encountered -
 * no catalog can then still represent its single-owner partitions by index objects
 */
@Deprecated(since = "2026.3", forRemoval = true)
final class VirtualReferencePartitionConversion {
	/**
	 * How many records the conversion may write into one collection data store before it flushes it.
	 */
	static final int DEFAULT_FLUSH_THRESHOLD = 10_000;

	private VirtualReferencePartitionConversion() {
	}

	/**
	 * Converts the single-owner partitions of one collection into registry rows - see the class documentation - and,
	 * when it wrote anything, flushes the collection data store with a collection header that no longer lists the
	 * converted partitions. Nothing is published: the caller puts the collection header the data store holds afterwards
	 * into the catalog data store and publishes it with the catalog header.
	 *
	 * The migration runs it after its other steps over the collection: it reads only the entity indexes the collection
	 * header lists, and removes the ones it converts. Its final flush writes whatever those steps left unflushed too.
	 *
	 * @param catalogVersion   the catalog version being converted
	 * @param collectionHeader the collection header as published
	 * @param collectionService the data store of the collection, opened over that header
	 * @param entitySchema     the schema of the collection, its reflected references bound
	 * @param observer         observes the conversion
	 * @param flushThreshold   how many records may be written into the data store before it is flushed
	 * @return what the conversion did
	 * @throws GenericEvitaInternalError when a registry of the collection holds a row - see the class documentation -
	 *                                   or the flush threshold is not positive
	 */
	@Nonnull
	static CollectionConversion convertCollection(
		long catalogVersion,
		@Nonnull EntityCollectionFileHeader collectionHeader,
		@Nonnull DefaultEntityCollectionPersistenceService collectionService,
		@Nonnull EntitySchema entitySchema,
		@Nonnull Observer observer,
		int flushThreshold
	) {
		Assert.isPremiseValid(flushThreshold > 0, "The flush threshold must be positive!");
		final String entityType = collectionHeader.entityType();
		final OffsetIndexStoragePartPersistenceService storage = collectionService.getStoragePartPersistenceService();
		final CollectionWriter writer = new CollectionWriter(
			catalogVersion, collectionHeader, collectionService, flushThreshold
		);
		final List<String> leftovers = new ArrayList<>(0);

		final IndexClassification classification = classifyIndexes(
			catalogVersion, collectionHeader, storage, entitySchema
		);
		for (final Entry<ReferenceInScope, int[]> reference : classification.notShadowed().entrySet()) {
			leftovers.add(
				reference.getKey() + ": " + reference.getValue()[0] + " partition(s) of a reference the schema does " +
					"not index for filtering with the referenced entity in the scope"
			);
		}

		// convert the partitions of one referenced-type entity index at a time
		int converted = 0;
		int shared = 0;
		for (final Entry<ReferenceInScope, Integer> typeIndex : classification.typeIndexes().entrySet()) {
			final ReferenceInScope reference = typeIndex.getKey();
			final int typeIndexPrimaryKey = typeIndex.getValue();
			final VirtualReferencePartitionIndex registry = VirtualReferencePartitionLoader.loadIndex(
				storage, catalogVersion, typeIndexPrimaryKey, reference.referenceName()
			);
			writer.heldRegistries++;
			observer.registryLoaded(entityType, reference.referenceName(), reference.scope(), registry);
			// the rows are written from the objects below - a registry already holding anything was written by no
			// release, so the catalog does not carry the protocol it was written under
			Assert.isPremiseValid(
				registry.isEmpty(),
				() -> "The protocol-6 catalog holds a registry row of " + reference + " of collection `" + entityType +
					"`, which no release writes - the catalog was not written under the protocol its header carries!"
			);
			final CompositeIntArray partitions = classification.candidates().remove(reference);
			if (partitions != null) {
				final PartitionCounts counts = convertPartitions(
					catalogVersion, entityType, collectionService, entitySchema, observer, writer,
					entitySchema.getReferenceOrThrowException(reference.referenceName()), registry, partitions
				);
				converted += counts.converted();
				shared += counts.shared();
				if (counts.ownerless() > 0) {
					leftovers.add(reference + ": " + counts.ownerless() + " partition(s) without an owner");
				}
			}
			final TrappedChanges registryParts = new TrappedChanges();
			registry.appendStorageParts(typeIndexPrimaryKey, reference.referenceName(), registryParts);
			writer.write(registryParts);
			observer.checkpoint(writer.checkpoint(entityType));
			writer.heldRegistries--;
		}
		for (final Entry<ReferenceInScope, CompositeIntArray> reference : classification.candidates().entrySet()) {
			leftovers.add(
				reference.getKey() + ": " + reference.getValue().getSize() + " partition(s) without a " +
					"referenced-type entity index to carry their rows"
			);
		}

		final boolean changed = writer.finish();
		return new CollectionConversion(
			entityType, changed, converted, shared, classification.partitioned(), writer.intermediateFlushes, leftovers
		);
	}

	/**
	 * Classifies every entity index the collection header lists by its key - only the manifest of each is read: the
	 * referenced-type entity indexes carrying the registries, the partitions to convert by the reference they belong
	 * to, the partitions of references indexed for partitioning, which stay index objects by design, and the
	 * partitions of the other references, which stay index objects although their kind is not kept.
	 *
	 * @param catalogVersion   the catalog version being converted
	 * @param collectionHeader the collection header as published
	 * @param storage          the storage part service of the collection data store
	 * @param entitySchema     the schema of the collection, its reflected references bound
	 * @return the classification
	 * @throws GenericEvitaInternalError when the manifest of a listed entity index is missing
	 */
	@Nonnull
	private static IndexClassification classifyIndexes(
		long catalogVersion,
		@Nonnull EntityCollectionFileHeader collectionHeader,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull EntitySchema entitySchema
	) {
		final SortedMap<ReferenceInScope, Integer> typeIndexes = new TreeMap<>(ReferenceInScope.ORDER);
		final Map<ReferenceInScope, CompositeIntArray> candidates = CollectionUtils.createHashMap(8);
		final SortedMap<ReferenceInScope, int[]> notShadowed = new TreeMap<>(ReferenceInScope.ORDER);
		int partitioned = 0;
		for (final Integer indexPrimaryKey : collectionHeader.usedEntityIndexPrimaryKeys()) {
			final EntityIndexStoragePart manifest = storage.getStoragePart(
				catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
			);
			if (manifest == null) {
				throw new GenericEvitaInternalError(
					"Entity index storage part for primary key " + indexPrimaryKey + " of collection `" +
						collectionHeader.entityType() + "` is missing!"
				);
			}
			final EntityIndexKey indexKey = manifest.getEntityIndexKey();
			final Scope scope = indexKey.scope();
			if (indexKey.type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
				typeIndexes.put(new ReferenceInScope((String) indexKey.discriminator(), scope), indexPrimaryKey);
			} else if (indexKey.type() == EntityIndexType.REFERENCED_ENTITY) {
				final String referenceName = ((RepresentativeReferenceKey) indexKey.discriminator()).referenceName();
				final ReferenceInScope reference = new ReferenceInScope(referenceName, scope);
				final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName).orElse(null);
				if (referenceSchema != null && VirtualReferencePartitionWriter.isShadowed(referenceSchema, scope)) {
					candidates.computeIfAbsent(reference, it -> new CompositeIntArray()).add(indexPrimaryKey);
				} else if (referenceSchema != null && isPartitioned(referenceSchema, scope)) {
					partitioned++;
				} else {
					notShadowed.computeIfAbsent(reference, it -> new int[1])[0]++;
				}
			}
		}
		return new IndexClassification(typeIndexes, candidates, notShadowed, partitioned);
	}

	/**
	 * Converts the partitions of one reference in one scope: reads each of them, demotes one with a single owner into
	 * a row of the registry and removes its index object, and keeps one with several owners - or none - as it is. The
	 * observer is given a checkpoint after every partition, while the partition is still held.
	 *
	 * @param catalogVersion    the catalog version being converted
	 * @param entityType        the entity type of the collection
	 * @param collectionService the data store of the collection
	 * @param entitySchema      the schema of the collection, its reflected references bound
	 * @param observer          observes the conversion
	 * @param writer            writes into the data store of the collection
	 * @param referenceSchema   the reference the partitions belong to
	 * @param registry          the registry of the reference in the scope, receiving the rows
	 * @param partitions        the primary keys of the partitions
	 * @return how many partitions were converted, kept as shared, and found without an owner
	 * @throws GenericEvitaInternalError when a listed partition does not load as a partition
	 */
	@Nonnull
	private static PartitionCounts convertPartitions(
		long catalogVersion,
		@Nonnull String entityType,
		@Nonnull DefaultEntityCollectionPersistenceService collectionService,
		@Nonnull EntitySchema entitySchema,
		@Nonnull Observer observer,
		@Nonnull CollectionWriter writer,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull CompositeIntArray partitions
	) {
		int converted = 0;
		int shared = 0;
		int ownerless = 0;
		final OfInt it = partitions.iterator();
		while (it.hasNext()) {
			final int partitionPrimaryKey = it.nextInt();
			final EntityIndex index = collectionService.readEntityIndex(
				catalogVersion, partitionPrimaryKey, entitySchema, false
			);
			writer.heldPartitions++;
			observer.partitionRead(entityType, partitionPrimaryKey, index);
			final ReferencePartition partition = ReferencePartitions.asPartition(index);
			if (partition == null) {
				throw new GenericEvitaInternalError(
					"Entity index " + partitionPrimaryKey + " of collection `" + entityType + "` is listed " +
						"as a partition, but loads as `" + index.getClass().getName() + "`!"
				);
			}
			final int owners = partition.size();
			if (owners == 1) {
				VirtualReferencePartitionWriter.demoteToRow(
					registry, entitySchema, referenceSchema, partition, index
				);
				writer.removeIndex(index);
				converted++;
			} else if (owners > 1) {
				shared++;
			} else {
				ownerless++;
			}
			// the checkpoint comes while the partition is still held - the peak the observer measures
			observer.checkpoint(writer.checkpoint(entityType));
			writer.heldPartitions--;
		}
		return new PartitionCounts(converted, shared, ownerless);
	}

	/**
	 * Returns TRUE when the partitions of the reference in the scope are kept as index objects by design - the
	 * reference is indexed for partitioning, with the referenced entity component.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @return TRUE for a reference whose partitions are always index objects
	 */
	private static boolean isPartitioned(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING &&
			referenceSchema.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY);
	}

	/**
	 * Tells the operator what the conversion did with one collection holding partitions - including how many times it
	 * flushed the collection data store before its final flush, which tells the operator of a large collection that
	 * the conversion bounds what it keeps in memory - and warns about every partition it left as an index object
	 * although its reference is not one whose partitions are always index objects.
	 *
	 * @param catalogName the name of the catalog
	 * @param conversion  what the conversion did
	 */
	static void report(@Nonnull String catalogName, @Nonnull CollectionConversion conversion) {
		if (
			conversion.convertedPartitions() + conversion.sharedPartitions() +
				conversion.partitionedPartitions() == 0 && conversion.leftovers().isEmpty()
		) {
			// a collection without partitions has nothing to report
			return;
		}
		ConsoleWriter.writeLine(
			"Entity collection `" + conversion.entityType() + "`: " + conversion.convertedPartitions() +
				" single-owner partition(s) converted into registry rows, " + conversion.sharedPartitions() +
				" shared and " + conversion.partitionedPartitions() + " partitioned partition(s) kept as index " +
				"objects, the data store flushed " + conversion.intermediateFlushes() + " time(s) before the " +
				"final flush.",
			ConsoleColor.BRIGHT_BLUE
		);
		if (!conversion.leftovers().isEmpty()) {
			final String message = "Catalog `" + catalogName + "`, entity collection `" + conversion.entityType() +
				"`: partitions left as index objects although the conversion does not keep their kind - " +
				Migration_2026_3.formatAttributeList(conversion.leftovers()) + ". They are served as before; " +
				"reindex the collection to drop them.";
			ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_YELLOW);
			LoggerFactory.getLogger(VirtualReferencePartitionConversion.class).warn(message);
		}
	}

	/**
	 * Observes the conversion. Every method does nothing by default.
	 */
	interface Observer {
		/**
		 * The observer of a conversion nobody watches.
		 */
		Observer NONE = new Observer() {
		};

		/**
		 * The registry of a referenced-type entity index was loaded.
		 *
		 * An observer must not keep the registry reachable: whether the conversion releases it is exactly what an
		 * observer may want to check, through a weak reference.
		 *
		 * @param entityType    the entity type of the collection
		 * @param referenceName the reference of the registry
		 * @param scope         the scope of the registry
		 * @param registry      the registry, as loaded
		 */
		default void registryLoaded(
			@Nonnull String entityType,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			@Nonnull VirtualReferencePartitionIndex registry
		) {
		}

		/**
		 * The index object of a partition was loaded.
		 *
		 * An observer must not keep the index object reachable: whether the conversion releases it is exactly what an
		 * observer may want to check, through a weak reference.
		 *
		 * @param entityType          the entity type of the collection
		 * @param partitionPrimaryKey the primary key of the partition
		 * @param partition           the index object of the partition, as loaded
		 */
		default void partitionRead(@Nonnull String entityType, int partitionPrimaryKey, @Nonnull EntityIndex partition) {
		}

		/**
		 * A partition was converted or left, or a registry was written - reported while the conversion still holds
		 * the partition and the registry.
		 *
		 * @param checkpoint the state of the conversion at that moment
		 */
		default void checkpoint(@Nonnull Checkpoint checkpoint) {
		}

		/**
		 * A collection was converted and its data store closed; nothing of it is published yet.
		 *
		 * @param conversion what the conversion did with the collection
		 */
		default void collectionConverted(@Nonnull CollectionConversion conversion) {
		}

	}

	/**
	 * The state of the conversion of one collection between two of its steps.
	 *
	 * @param entityType       the entity type of the collection
	 * @param heldPartitions   how many partition index objects the conversion holds
	 * @param heldRegistries   how many registries the conversion holds
	 * @param pendingRecords   how many records the conversion wrote into the data store since its last flush
	 * @param flushes          how many times the conversion flushed the data store so far
	 * @param storage          the storage part service of the collection data store
	 */
	record Checkpoint(
		@Nonnull String entityType,
		int heldPartitions,
		int heldRegistries,
		int pendingRecords,
		int flushes,
		@Nonnull OffsetIndexStoragePartPersistenceService storage
	) {
	}

	/**
	 * What the conversion did with one collection.
	 *
	 * @param entityType            the entity type of the collection
	 * @param changed               TRUE when anything was written into its data store, which is then flushed
	 * @param convertedPartitions   the single-owner partitions converted into registry rows
	 * @param sharedPartitions      the partitions with several owners kept as index objects
	 * @param partitionedPartitions the partitions of references indexed for partitioning kept as index objects
	 * @param intermediateFlushes   how many times the data store was flushed before the final flush
	 * @param leftovers             the partitions left as index objects although their kind is not kept, described
	 */
	record CollectionConversion(
		@Nonnull String entityType,
		boolean changed,
		int convertedPartitions,
		int sharedPartitions,
		int partitionedPartitions,
		int intermediateFlushes,
		@Nonnull List<String> leftovers
	) {
	}

	/**
	 * A reference in a scope - the identity of a referenced-type entity index and of its registry within a collection.
	 *
	 * @param referenceName the reference
	 * @param scope         the scope
	 */
	private record ReferenceInScope(@Nonnull String referenceName, @Nonnull Scope scope) {
		/**
		 * Orders the references by name and scope, so a collection is always converted in the same order.
		 */
		private static final Comparator<ReferenceInScope> ORDER =
			Comparator.comparing(ReferenceInScope::referenceName).thenComparing(ReferenceInScope::scope);

		@Nonnull
		@Override
		public String toString() {
			return "`" + this.referenceName + "` in " + this.scope;
		}
	}

	/**
	 * The entity indexes of one collection classified by their key - see `classifyIndexes`.
	 *
	 * @param typeIndexes the primary keys of the referenced-type entity indexes, in the order of their references
	 * @param candidates  the primary keys of the partitions to convert, by the reference they belong to
	 * @param notShadowed how many partitions of a reference whose kind is not kept each reference has, in one slot
	 * @param partitioned how many partitions belong to references indexed for partitioning
	 */
	private record IndexClassification(
		@Nonnull SortedMap<ReferenceInScope, Integer> typeIndexes,
		@Nonnull Map<ReferenceInScope, CompositeIntArray> candidates,
		@Nonnull SortedMap<ReferenceInScope, int[]> notShadowed,
		int partitioned
	) {
	}

	/**
	 * What the conversion did with the partitions of one reference in one scope - see `convertPartitions`.
	 *
	 * @param converted the single-owner partitions converted into registry rows
	 * @param shared    the partitions with several owners kept as index objects
	 * @param ownerless the partitions without an owner kept as index objects
	 */
	private record PartitionCounts(int converted, int shared, int ownerless) {
	}

	/**
	 * Writes into the data store of one collection and flushes it whenever the records written since the last flush
	 * reach the threshold. The flush runs right before a write, never after one, so the final flush always has
	 * something to write once anything was written - which is what makes it build the collection header listing the
	 * remaining entity indexes.
	 */
	private static final class CollectionWriter {
		/**
		 * The catalog version being converted - every record is written at it.
		 */
		private final long catalogVersion;
		/**
		 * The collection header as published.
		 */
		@Nonnull private final EntityCollectionFileHeader collectionHeader;
		/**
		 * The data store of the collection.
		 */
		@Nonnull private final DefaultEntityCollectionPersistenceService collectionService;
		/**
		 * The storage part service of the data store.
		 */
		@Nonnull private final OffsetIndexStoragePartPersistenceService storage;
		/**
		 * How many records may be written before the data store is flushed.
		 */
		private final int flushThreshold;
		/**
		 * The primary keys of the partitions converted into rows - the entity indexes the header stops listing.
		 */
		@Nonnull private final IntHashSet removedIndexes = new IntHashSet();
		/**
		 * The records written since the last flush.
		 */
		private int pendingRecords;
		/**
		 * The records written in total.
		 */
		private int writtenRecords;
		/**
		 * The flushes before the final one.
		 */
		private int intermediateFlushes;
		/**
		 * How many partition index objects the conversion holds right now.
		 */
		private int heldPartitions;
		/**
		 * How many registries the conversion holds right now.
		 */
		private int heldRegistries;

		/**
		 * @param catalogVersion    the catalog version being converted
		 * @param collectionHeader  the collection header as published
		 * @param collectionService the data store of the collection
		 * @param flushThreshold    how many records may be written before the data store is flushed
		 */
		private CollectionWriter(
			long catalogVersion,
			@Nonnull EntityCollectionFileHeader collectionHeader,
			@Nonnull DefaultEntityCollectionPersistenceService collectionService,
			int flushThreshold
		) {
			this.catalogVersion = catalogVersion;
			this.collectionHeader = collectionHeader;
			this.collectionService = collectionService;
			this.storage = collectionService.getStoragePartPersistenceService();
			this.flushThreshold = flushThreshold;
		}

		/**
		 * Removes every storage part of a converted partition - exactly the footprint the engine reclaims when it drops
		 * an index (`DataStoreChanges#reclaimRemovedIndexFootprint`): every persisted sub-index root and leaf page, the
		 * manifest and the membership bitmaps.
		 *
		 * @param partition the index object of the converted partition, as loaded
		 */
		private void removeIndex(@Nonnull EntityIndex partition) {
			final TrappedChanges footprint = new TrappedChanges();
			partition.emitFootprintRemovals(footprint);
			write(footprint);
			final int partitionPrimaryKey = partition.getPrimaryKey();
			remove(partitionPrimaryKey, EntityIndexStoragePart.class);
			remove(partitionPrimaryKey, EntityIdsStoragePart.class);
			this.removedIndexes.add(partitionPrimaryKey);
		}

		/**
		 * Applies every part in `changes`: a removal instruction removes the part it names, anything else is stored.
		 *
		 * @param changes the parts to apply, in order
		 */
		private void write(@Nonnull TrappedChanges changes) {
			final Iterator<StoragePart> it = changes.getTrappedChangesIterator();
			while (it.hasNext()) {
				final StoragePart part = it.next();
				if (part instanceof final DeferredRemovalStoragePart removal) {
					remove(
						removal.computeUniquePartIdAndSet(this.storage.getReadOnlyKeyCompressor()),
						removal.removedContainerType()
					);
				} else {
					flushIfDue();
					this.storage.putStoragePart(this.catalogVersion, part);
					written();
				}
			}
		}

		/**
		 * Removes one stored part, when it is stored.
		 *
		 * @param storagePartPrimaryKey the primary key of the part
		 * @param containerType         the type of the part
		 */
		private void remove(long storagePartPrimaryKey, @Nonnull Class<? extends StoragePart> containerType) {
			// a part that is not stored writes no record - and must not trigger the flush either, see the class docs
			if (this.storage.containsStoragePart(this.catalogVersion, storagePartPrimaryKey, containerType)) {
				flushIfDue();
				this.storage.removeStoragePart(this.catalogVersion, storagePartPrimaryKey, containerType);
				written();
			}
		}

		/**
		 * Flushes the data store when the records written since the last flush reached the threshold. The collection
		 * header this flush builds is never published - the final flush builds the one that is.
		 */
		private void flushIfDue() {
			if (this.pendingRecords >= this.flushThreshold) {
				this.collectionService.flush(
					this.catalogVersion, new NoChangeHeaderInfoSupplier(this.collectionHeader)
				);
				this.pendingRecords = 0;
				this.intermediateFlushes++;
			}
		}

		/**
		 * Counts one written record.
		 */
		private void written() {
			this.pendingRecords++;
			this.writtenRecords++;
		}

		/**
		 * Flushes the data store a last time, with a collection header listing every entity index but the converted
		 * partitions, when anything was written at all.
		 *
		 * @return TRUE when anything was written
		 */
		private boolean finish() {
			if (this.writtenRecords == 0) {
				return false;
			}
			final List<Integer> usedIndexes = this.collectionHeader.usedEntityIndexPrimaryKeys();
			final List<Integer> remainingIndexes = new ArrayList<>(usedIndexes.size() - this.removedIndexes.size());
			for (final Integer indexPrimaryKey : usedIndexes) {
				if (!this.removedIndexes.contains(indexPrimaryKey)) {
					remainingIndexes.add(indexPrimaryKey);
				}
			}
			this.collectionService.flush(
				this.catalogVersion, new RemainingIndexesHeaderInfoSupplier(this.collectionHeader, remainingIndexes)
			);
			this.pendingRecords = 0;
			return true;
		}

		/**
		 * Describes the state of the conversion right now.
		 *
		 * @param entityType the entity type of the collection
		 * @return the checkpoint
		 */
		@Nonnull
		private Checkpoint checkpoint(@Nonnull String entityType) {
			return new Checkpoint(
				entityType, this.heldPartitions, this.heldRegistries, this.pendingRecords, this.intermediateFlushes,
				this.storage
			);
		}
	}

	/**
	 * Supplies the collection header of a converted collection: everything as published, except the entity indexes,
	 * which no longer include the converted partitions. The index primary key sequence is kept - it covers the primary
	 * keys of the rows as it covered those of the objects.
	 *
	 * @param collectionHeader the collection header as published
	 * @param remainingIndexes the entity indexes the collection still stores
	 */
	private record RemainingIndexesHeaderInfoSupplier(
		@Nonnull EntityCollectionFileHeader collectionHeader,
		@Nonnull List<Integer> remainingIndexes
	) implements HeaderInfoSupplier {

		@Override
		public int getLastAssignedPrimaryKey() {
			return this.collectionHeader.lastPrimaryKey();
		}

		@Override
		public int getLastAssignedIndexKey() {
			return this.collectionHeader.lastEntityIndexPrimaryKey();
		}

		@Override
		public int getLastAssignedInternalPriceId() {
			return this.collectionHeader.lastInternalPriceId();
		}

		@Nonnull
		@Override
		public OptionalInt getGlobalIndexPrimaryKey() {
			return this.collectionHeader.globalEntityIndexPrimaryKey() == null ?
				OptionalInt.empty() :
				OptionalInt.of(this.collectionHeader.globalEntityIndexPrimaryKey());
		}

		@Nonnull
		@Override
		public List<Integer> getIndexPrimaryKeys() {
			return this.remainingIndexes;
		}
	}

}
