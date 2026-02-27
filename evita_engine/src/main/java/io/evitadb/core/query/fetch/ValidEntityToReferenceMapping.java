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

package io.evitadb.core.query.fetch;


import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This DTO contains the validity mappings between main entity and referenced one. Because we fetch references for
 * all main entities at once the following situation might occur:
 *
 * We look up for PARAMETER (referenced) entity from PRODUCT ENTITY (main), we look up for those parameters that
 * are mapped with reference having attribute X greater than 5 and there is following data layout:
 *
 * | PRODUCT PK  | ATTRIBUTE X    | PARAMETER PK |
 * |*************|****************|**************|
 * | 1           | 1              | 10           |
 * | 1           | 7              | 11           |
 * | 2           | 7              | 10           |
 *
 * If the validity mapping wasn't involved we'd initialize reference to PARAMETER 10 for PRODUCT with pk = 1 because
 * the PARAMETER 10 passes the filtering constraint for PRODUCT with pk = 2. This validity mapping allows us to
 * state that the PARAMETER with pk = 10 should be initialized only in PRODUCT with pk = 2.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ValidEntityToReferenceMapping {
	/**
	 * Function that produces a {@link RepresentativeReferenceKey} for a given {@link ReferenceDecorator}.
	 * For references whose cardinality allows duplicates, the produced key includes representative attribute
	 * values extracted via {@link RepresentativeAttributeDefinition#getRepresentativeValues} to disambiguate
	 * multiple references pointing to the same entity. For references without duplicates, the key contains
	 * only the reference key (name + primary key).
	 */
	private final Function<ReferenceDecorator, RepresentativeReferenceKey> representativeKeyProducer;
	/**
	 * Contains the source entity PK to allowed referenced entity PKs index.
	 * Key: source entity primary key
	 * Value: allowed referenced primary keys
	 */
	private final IntObjectMap<RepresentativeMapping> mapping;
	/**
	 * Internal helper variable containing a {@link RoaringBitmap} with all source entity primary keys present
	 * in {@link #mapping}. Lazily initialized on the first call to
	 * {@link #restrictTo(RepresentativeReferenceKey, Bitmap)} and cached for subsequent calls.
	 */
	private RoaringBitmap knownEntityPrimaryKeys;

	/**
	 * Creates a new validity mapping for tracking which referenced entities are allowed for each source entity.
	 *
	 * @param expectedEntityCount the expected number of source entities, used to pre-size internal data structures
	 * @param referenceSchema     the schema of the reference being fetched; its cardinality determines whether
	 *                            the representative key producer includes representative attribute values
	 *                            (for duplicate-cardinality references) or only the reference key
	 */
	public ValidEntityToReferenceMapping(
		int expectedEntityCount,
		@Nonnull ReferenceSchema referenceSchema
	) {
		this.mapping = new IntObjectHashMap<>(expectedEntityCount);
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			this.representativeKeyProducer = ref -> new RepresentativeReferenceKey(
				ref.getReferenceKey(), rad.getRepresentativeValues(ref.getDelegate()));
		} else {
			this.representativeKeyProducer = ref -> new RepresentativeReferenceKey(ref.getReferenceKey());
		}
	}

	/**
	 * Initializes the validity mapping - for `entityPrimaryKey` the `referencedPrimaryKeys` will
	 * become "allowed".
	 */
	public void setInitialVisibilityForEntity(int entityPrimaryKey, @Nonnull Bitmap referencedPrimaryKeys) {
		Assert.isPremiseValid(
			this.knownEntityPrimaryKeys == null,
			"Known entity primary keys are not expected to be initialized here."
		);
		final RepresentativeMapping matchingReferencedPrimaryKeys = ofNullable(this.mapping.get(entityPrimaryKey))
			.orElseGet(() -> {
				final RepresentativeMapping theSet = new RepresentativeMapping(this.representativeKeyProducer);
				this.mapping.put(entityPrimaryKey, theSet);
				return theSet;
			});
		final OfInt it = referencedPrimaryKeys.iterator();
		while (it.hasNext()) {
			matchingReferencedPrimaryKeys.add(it.nextInt());
		}
	}

	/**
	 * Retrieves a formula representation of valid referenced entities for the given entity primary key.
	 * If there is no mapping for the provided primary key, an empty formula is returned.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the valid referenced entities formula is to be retrieved
	 * @return a formula representing the valid referenced entities, or an empty formula if no mapping exists for the entity
	 */
	@Nonnull
	public Formula getValidReferencedEntitiesFormula(int entityPrimaryKey) {
		final RepresentativeMapping representativeMapping = this.mapping.get(entityPrimaryKey);
		if (representativeMapping == null) {
			return EmptyFormula.INSTANCE;
		} else {
			return representativeMapping.toFormula();
		}
	}

	/**
	 * Clears all validity mappings - no referenced entity will be allowed for any of known source entities.
	 */
	public void forbidAll() {
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			entry.value.clear();
		}
	}

	/**
	 * Removes referenced entity primary keys from every source entity's mapping unless they are present
	 * in the provided `referencedPrimaryKeys` set. After this call, each source entity will only retain
	 * references to entities whose primary keys appear in the input set.
	 *
	 * @param referencedPrimaryKeys the set of referenced entity primary keys that should remain allowed
	 */
	public void forbidAllExcept(@Nonnull IntSet referencedPrimaryKeys) {
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			entry.value.removeAll(it -> !referencedPrimaryKeys.contains(it));
		}
	}

	/**
	 * Performs the same filtering as {@link #forbidAllExcept(IntSet)} — removes referenced entity primary keys
	 * not present in the provided set — and additionally disables implicit allowance of representative keys
	 * that lack explicit restrictions by setting {@link RepresentativeMapping#setUnrestrictedKeysAllowed(boolean)}
	 * to `false` on each mapping entry.
	 *
	 * This method is used for references with duplicate cardinality, where representative attribute values
	 * serve as discriminators and only explicitly restricted representative keys should be considered valid.
	 *
	 * @param referencedPrimaryKeys the set of referenced entity primary keys that should remain allowed
	 */
	public void forbidAllExceptIncludingDiscriminators(@Nonnull IntSet referencedPrimaryKeys) {
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			entry.value.removeAll(it -> !referencedPrimaryKeys.contains(it));
			entry.value.setUnrestrictedKeysAllowed(false);
		}
	}

	/**
	 * Restricts the existing validity mapping - for each known mapping only the set of `referencedPrimaryKeys`
	 * will remain "allowed".
	 */
	public void restrictTo(@Nonnull Bitmap referencedPrimaryKeys) {
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			entry.value.retainAll(referencedPrimaryKeys::contains);
		}
	}

	/**
	 * Restricts visibility of a specific referenced entity (identified by `representativeReferenceKey`) to only
	 * the source entities whose primary keys are present in `entityPrimaryKeys`.
	 *
	 * The method handles two cases based on the representative reference key:
	 *
	 * - **No representative attribute values** (simple references without duplicates): iterates over all source
	 *   entities NOT in `entityPrimaryKeys` and removes the referenced entity PK from their mappings, effectively
	 *   making the reference invisible for those source entities.
	 * - **With representative attribute values** (duplicate-cardinality references): for each source entity
	 *   in `entityPrimaryKeys`, records a fine-grained per-discriminator restriction via
	 *   {@link RepresentativeMapping#restrictTo(RepresentativeReferenceKey, Bitmap)}, allowing the mapping
	 *   to distinguish between multiple references to the same entity based on their representative attributes.
	 *
	 * @param representativeReferenceKey the key identifying the referenced entity, optionally including
	 *                                   representative attribute values for disambiguation
	 * @param entityPrimaryKeys          the set of source entity primary keys for which this reference
	 *                                   should remain visible
	 */
	public void restrictTo(
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull Bitmap entityPrimaryKeys
	) {
		if (representativeReferenceKey.representativeAttributeValues().length == 0) {
			if (this.knownEntityPrimaryKeys == null) {
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
					writer.add(entry.key);
				}
				this.knownEntityPrimaryKeys = writer.get();
			}
			final RoaringBitmap invalidRecords = RoaringBitmap.andNot(
				this.knownEntityPrimaryKeys,
				RoaringBitmapBackedBitmap.getRoaringBitmap(
					entityPrimaryKeys
				)
			);
			final int referencedEntityPk = representativeReferenceKey.primaryKey();
			final PeekableIntIterator it2 = invalidRecords.getIntIterator();
			while (it2.hasNext()) {
				this.mapping
					.get(it2.next())
					.removeAll(pk -> pk == referencedEntityPk);
			}
		} else {
			final OfInt it1 = entityPrimaryKeys.iterator();
			while (it1.hasNext()) {
				final int entityPk = it1.nextInt();
				RepresentativeMapping mappingForEntity = this.mapping.get(entityPk);
				if (mappingForEntity == null) {
					mappingForEntity = new RepresentativeMapping(this.representativeKeyProducer);
					this.mapping.put(entityPk, mappingForEntity);
				}
				mappingForEntity.restrictTo(representativeReferenceKey, entityPrimaryKeys);
			}
		}
	}

	/**
	 * Returns `true` if the given `reference` is allowed to be visible for the specified `entityPrimaryKey`.
	 * Delegates to {@link RepresentativeMapping#contains(int, ReferenceDecorator)} which evaluates both
	 * simple primary key presence and representative key restrictions.
	 *
	 * @param entityPrimaryKey the primary key of the source entity
	 * @param reference        the reference decorator to check visibility for
	 * @return `true` if the reference is allowed for the given entity, `false` otherwise
	 */
	public boolean isReferenceSelected(int entityPrimaryKey, @Nonnull ReferenceDecorator reference) {
		return ofNullable(this.mapping.get(entityPrimaryKey))
			.map(it -> it.contains(entityPrimaryKey, reference))
			.orElse(false);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("Valid references: ");
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			sb.append("\n   ").append(entry.key).append(" -> ").append(entry.value);
		}
		return sb.toString();
	}

}
