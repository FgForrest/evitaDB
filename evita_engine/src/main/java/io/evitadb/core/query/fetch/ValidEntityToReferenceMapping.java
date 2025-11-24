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
	 * Function that produces representative key for a given reference decorator.
	 */
	private final Function<ReferenceDecorator, RepresentativeReferenceKey> representativeKeyProducer;
	/**
	 * Contains the source entity PK to allowed referenced entity PKs index.
	 * Key: source entity primary key
	 * Value: allowed referenced primary keys
	 */
	private final IntObjectMap<RepresentativeMapping> mapping;
	/**
	 * Internal, helper variable that contains initialized {@link RoaringBitmap} with all source entity primary keys.
	 */
	private RoaringBitmap knownEntityPrimaryKeys;

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
		final int expectedSize = referencedPrimaryKeys.size();
		final RepresentativeMapping matchingReferencedPrimaryKeys = ofNullable(this.mapping.get(entityPrimaryKey))
			.orElseGet(() -> {
				final RepresentativeMapping theSet = new RepresentativeMapping(
					this.representativeKeyProducer, expectedSize);
				this.mapping.put(entityPrimaryKey, theSet);
				return theSet;
			});
		final OfInt it = referencedPrimaryKeys.iterator();
		while (it.hasNext()) {
			matchingReferencedPrimaryKeys.add(it.nextInt());
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
	 * Clears validity mappings for all source entities except those that are present in the input
	 * `referencedPrimaryKeys` argument. Each source entity not present in the input set will be left with
	 * no referenced entities allowed.
	 */
	public void forbidAllExcept(@Nonnull IntSet referencedPrimaryKeys) {
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			entry.value.removeAll(it -> !referencedPrimaryKeys.contains(it));
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
	 * Restricts the existing validity mapping - for passed referenced primary key. If this reference is present
	 * in other records than present in input `entityPrimaryKeys` it will be removed from there (not allowed to
	 * be visible there).
	 */
	public void restrictTo(
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull Bitmap entityPrimaryKeys
	) {
		final OfInt it1 = entityPrimaryKeys.iterator();
		while (it1.hasNext()) {
			final int entityPk = it1.nextInt();
			final RepresentativeMapping mappingForEntity = this.mapping.get(entityPk);
			if (mappingForEntity != null) {
				mappingForEntity.restrictTo(representativeReferenceKey);
			}
		}

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
	}

	/**
	 * Returns true if `referencedPrimaryKey` is allowed to be fetched for passed `entityPrimaryKey`.
	 */
	public boolean isReferenceSelected(int entityPrimaryKey, @Nonnull ReferenceDecorator reference) {
		return ofNullable(this.mapping.get(entityPrimaryKey))
			.map(it -> it.contains(reference))
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
