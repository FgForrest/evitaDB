
/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.sort.attribute.comparator;

import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.evitadb.core.query.sort.attribute.comparator.PredecessorAttributeComparator.cachedPositionOf;
import static io.evitadb.core.query.sort.attribute.comparator.PredecessorAttributeComparator.comparePositionsAcrossProviders;
import static io.evitadb.core.query.sort.attribute.sorter.MergedSortedRecordsSupplierSorter.createSortedRecordsOffsets;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation. This implementation
 * adheres to {@link MergeMode#APPEND_ALL} which relates to {@link TraverseByEntityProperty} ordering.
 *
 * The comparator sorts one traversal block at a time - the block key, naming the referenced entity of the block, is
 * bound by {@link #withReferencedEntityId(RepresentativeReferenceKey, Runnable)}. Within a bound block, an entity is
 * represented by its first row to that referenced entity, in the order of the representative attribute values, that
 * carries the chain attribute and that a chain provider of the row's representative key holds; an entity without
 * such a row is classified as not sortable ({@link #canSort(EntityContract)}), so it is passed on to the next block
 * rather than claimed by this one - matching what the index route does, where the providers of a block claim their
 * owners in the same order. Chain positions are not comparable across providers, so the entities of a block follow
 * the provider that placed them: entities represented by a key whose providers are lined up earlier precede those
 * represented by a key lined up later - even when the representative values of the two keys compare equal - and for
 * one key the live chain precedes the archived one. Without a bound block (a chain sorted under an implicit
 * `pickFirst`), the entity is represented the same way among all its rows, so an entity without a placeable valued
 * row is classified as not sortable there too. Direct comparisons without prior classification report every entity
 * they cannot place through {@link #getNonSortedEntities()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TraverseReferencePredecessorAttributeComparator
	extends AbstractReferenceAttributeComparator
	implements EntityReferenceSensitiveComparator {
	@Serial private static final long serialVersionUID = 2199278500724685085L;
	/**
	 * Supplier providing access to sorted record indexes.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	/**
	 * Definition of the representative attributes telling the rows of one referenced entity apart, `null` when the
	 * reference allows no duplicates and a referenced entity has a single row per entity.
	 */
	@Nullable private final RepresentativeAttributeDefinition representativeAttributeDefinition;
	/**
	 * Sink appending an entity that no provider could place to the inherited {@code nonSortedEntities} container
	 * (created on first use). Allocated once so {@code comparePositionsAcrossProviders} can hand off unsortable
	 * entities without allocating a capturing lambda on the comparison hot path.
	 */
	private final Consumer<EntityContract> unsortedCollector = this::addUnsorted;
	/**
	 * The id of the referenced entity that is being traversed.
	 */
	@Nullable private RepresentativeReferenceKey referenceKey;
	/**
	 * Memoized information about sorted record providers.
	 */
	private SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * Memoized information of the sorted records offsets (reference key indexes).
	 */
	@Nullable private LinkedHashMap<RepresentativeReferenceKey, OffsetAndLimit> sortedRecordsOffsets;
	/**
	 * Array of caches storing the index positions of entities for each {@link SortedRecordsProvider}. Named apart from
	 * the inherited `cache` of the compared values, which lives for one block only, while positions stay valid
	 * for the whole query.
	 */
	private IntIntMap[] positionCaches;
	/**
	 * Whether {@link #sortedRecordsProviders}, {@link #sortedRecordsOffsets} and {@link #positionCaches} were resolved;
	 * the offsets alone cannot tell, because they stay `null` for a family without any chain provider.
	 */
	private boolean providersInitialized;

	/**
	 * Creates a chain comparator whose row selection follows the supplied providers on both routes.
	 *
	 * @param attributeName         the chain attribute
	 * @param type                  the predecessor type
	 * @param referenceSchema       the ordered reference
	 * @param locale                the locale of the chain attribute, if localized
	 * @param orderDirection        the direction of the chain
	 * @param sortedRecordsSupplier the providers in target and representative-key order
	 */
	public TraverseReferencePredecessorAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		super(
			attributeName,
			type,
			// a predecessor carries no decimal places to compare at
			0,
			referenceSchema,
			locale,
			orderDirection
		);
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		this.representativeAttributeDefinition = referenceSchema.getCardinality().allowsDuplicates() ?
			referenceSchema.getRepresentativeAttributeDefinition() : null;
	}

	/**
	 * Binds the comparator to the key of the sorted block for the duration of the lambda, which classifies and sorts
	 * the entities of the block; the key is released whatever the outcome.
	 *
	 * @param referenceKey the key of the sorted block
	 * @param lambda       the classification and sorting of the block
	 */
	@Override
	public void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda) {
		try {
			Assert.isPremiseValid(this.referenceKey == null, "Cannot set referenced entity id twice!");
			Assert.isPremiseValid(
				this.referenceName.equals(referenceKey.referenceName()),
				"Referenced entity id must be for the same reference!"
			);
			this.referenceKey = referenceKey;
			lambda.run();
		} finally {
			this.referenceKey = null;
		}
	}

	/**
	 * Picks the reference the entity is sorted by: within a bound block the row representing the entity in it (see
	 * {@link #pickRow(EntityContract, Collection)}), without a block the first valued reference in target and
	 * representative-key order, following the chain providers just as the index route does. The inherited
	 * classifier memoizes this choice before sorting, so even an unbound singleton needs a placeable valued row.
	 *
	 * @param entity the entity whose reference is picked
	 * @return the picked reference, empty when there is none
	 */
	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		return Optional.ofNullable(pickRow(
			entity,
			this.referenceKey == null ? entity.getReferences(this.referenceName) :
				entity.getReferences(this.referenceKey.referenceKey())
		));
	}

	/**
	 * Picks the row representing the entity among the supplied rows: among those that
	 * carry the chain attribute and that one of the chain providers of their key holds, the one whose key's providers
	 * are lined up first - the providers that claim the entity on the index route, which walks them in the same order.
	 * The order of the providers decides, not the representative values themselves, so keys whose values compare equal
	 * without being equal are told apart exactly as the index route tells them apart. The positions found on the way
	 * are memoized, so the comparisons that follow find them in the cache.
	 *
	 * @param entity the entity whose row is picked
	 * @param rows   the rows of the current target block, or all rows for implicit pick-first
	 * @return the representing row, `null` when no row of the entity can be placed in the block
	 */
	@Nullable
	private ReferenceContract pickRow(
		@Nonnull EntityContract entity,
		@Nonnull Collection<ReferenceContract> rows
	) {
		initializeProviders();
		final LinkedHashMap<RepresentativeReferenceKey, OffsetAndLimit> offsets = this.sortedRecordsOffsets;
		if (offsets == null) {
			return null;
		}
		final int primaryKey = entity.getPrimaryKeyOrThrowException();
		ReferenceContract picked = null;
		int pickedOffset = Integer.MAX_VALUE;
		for (final ReferenceContract row : rows) {
			if (this.attributeExtractor.apply(row) != null) {
				final OffsetAndLimit rowProviders = offsets.get(representativeKeyOf(row));
				if (
					rowProviders != null && rowProviders.offset() < pickedOffset &&
						isPlacedByProviders(rowProviders, primaryKey)
				) {
					picked = row;
					pickedOffset = rowProviders.offset();
				}
			}
		}
		return picked;
	}

	/**
	 * Returns the representative key of the row - the key of the reduced index, and so of the chain providers, the
	 * row belongs to.
	 *
	 * @param row the row of the ordered reference
	 * @return the representative key of the row
	 */
	@Nonnull
	private RepresentativeReferenceKey representativeKeyOf(@Nonnull ReferenceContract row) {
		return this.representativeAttributeDefinition == null ?
			new RepresentativeReferenceKey(row.getReferenceKey()) :
			new RepresentativeReferenceKey(
				row.getReferenceKey(), this.representativeAttributeDefinition.getRepresentativeValues(row)
			);
	}

	/**
	 * Tells whether one of the chain providers of one representative key holds the entity.
	 *
	 * @param offsetAndLimit the range of the key's providers
	 * @param primaryKey     the primary key of the entity
	 * @return `true` when a provider of the key holds the entity
	 */
	private boolean isPlacedByProviders(@Nonnull OffsetAndLimit offsetAndLimit, int primaryKey) {
		for (int i = offsetAndLimit.offset(); i < offsetAndLimit.limit(); i++) {
			final int position = cachedPositionOf(
				this.sortedRecordsProviders, this.positionCaches, i, this.estimatedCount, primaryKey
			);
			if (position >= 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Compares two entities by their positions in the chain providers. Within a bound block both entities were
	 * classified as placeable by {@link #canSort(EntityContract)}, so each is found in the providers of its
	 * representing row's key: entities of one key follow its chains, entities of two keys follow the order the
	 * providers of the keys are lined up in - the order the index route appends their chains in, which tells apart
	 * even keys whose representative values compare equal. Direct comparisons without prior classification
	 * report every entity they cannot place through {@link #getNonSortedEntities()}.
	 *
	 * @param o1 the first entity
	 * @param o2 the second entity
	 * @return the comparison result
	 */
	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		initializeProviders();
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		// to correctly compare the references we need to compare only attributes on the same reference
		final boolean bothAttributesSpecified = attribute1 != null && attribute2 != null;
		final boolean attributesExistOnSameReference = bothAttributesSpecified && attribute1.referencedKey().equals(
			attribute2.referencedKey());
		if (attributesExistOnSameReference) {
			// if the offset is null, the sorted record provider was not found for the given reference key
			final OffsetAndLimit offsetAndLimit = this.sortedRecordsOffsets == null ?
				null : this.sortedRecordsOffsets.get(attribute1.referencedKey());
			if (offsetAndLimit != null) {
				// scan only the providers belonging to the shared reference key
				return comparePositionsAcrossProviders(
					this.sortedRecordsProviders, this.positionCaches,
					offsetAndLimit.offset(), offsetAndLimit.limit(),
					this.estimatedCount, o1, o2, this.unsortedCollector
				);
			} else {
				// if they don't share a ref-key we compare them by position of their sorted record providers
				final OffsetAndLimit offsetAndLimit1 = this.sortedRecordsOffsets == null
					? null : this.sortedRecordsOffsets.get(attribute1.referencedKey());
				final OffsetAndLimit offsetAndLimit2 = this.sortedRecordsOffsets == null
					? null : this.sortedRecordsOffsets.get(attribute2.referencedKey());
				if (offsetAndLimit1 != null && offsetAndLimit2 != null) {
					return Integer.compare(offsetAndLimit1.offset(), offsetAndLimit2.offset());
				}
			}
			return 0;
		} else if (bothAttributesSpecified) {
			return Integer.compare(
				providerOffsetOf(attribute1.referencedKey()),
				providerOffsetOf(attribute2.referencedKey())
			);
		} else if (attribute1 == null && attribute2 != null) {
			return 1;
		} else if (attribute1 != null) {
			return -1;
		} else {
			return 0;
		}
	}

	/**
	 * Returns the position of the first chain provider of the representative key among all providers. The key belongs
	 * to a row that {@link #pickRow(EntityContract, Collection)} picked, so it always has providers.
	 *
	 * @param representativeKey the key of the row representing an entity
	 * @return the offset of the key's providers
	 * @throws GenericEvitaInternalError when the picked row has no chain provider
	 */
	private int providerOffsetOf(@Nonnull RepresentativeReferenceKey representativeKey) {
		final OffsetAndLimit offsetAndLimit = this.sortedRecordsOffsets == null ?
			null : this.sortedRecordsOffsets.get(representativeKey);
		if (offsetAndLimit == null) {
			throw new GenericEvitaInternalError(
				"Only entities with a row held by the chain providers may be compared!"
			);
		}
		return offsetAndLimit.offset();
	}

	/**
	 * Resolves the chain providers, their offsets per reference key and the per-provider position caches on the
	 * first use; they stay valid for the whole query.
	 */
	private void initializeProviders() {
		if (!this.providersInitialized) {
			this.sortedRecordsProviders = this.sortedRecordsSupplier.get();
			this.sortedRecordsOffsets = createSortedRecordsOffsets(this.sortedRecordsProviders);
			this.positionCaches = new IntIntMap[this.sortedRecordsProviders.length];
			this.providersInitialized = true;
		}
	}

	/**
	 * Appends an entity that none of the scanned providers could place to the inherited {@code nonSortedEntities}
	 * container, lazily creating it on first use.
	 *
	 * @param entity the entity to park at the end of the sorted result
	 */
	private void addUnsorted(@Nonnull EntityContract entity) {
		if (this.nonSortedEntities == null) {
			this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		this.nonSortedEntities.add(entity);
	}

}
