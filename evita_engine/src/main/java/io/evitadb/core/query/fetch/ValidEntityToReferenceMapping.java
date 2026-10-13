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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

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
 * ## Two ways the verdicts get here
 *
 * - **Row by row** ({@link #ValidEntityToReferenceMapping(int, ReferenceSchema, boolean)}): the fetch judges every
 *   reference row of every fetched owner by itself and {@link #admit(int, ReferenceContract) admits} the rows that
 *   pass. Nothing written for one owner is ever read for another, so the verdict of a row depends on its owner and
 *   the row alone. A row is selected when it exists and was admitted for its owner - by its referenced primary key
 *   when two rows referencing the same entity cannot be told apart by the verdict (no filter, or a reference without
 *   duplicates), and by its {@link RepresentativeReferenceKey} otherwise.
 * - **Key by key** ({@link #ValidEntityToReferenceMapping(int, ReferenceSchema)}): the index route starts from every
 *   referenced key of every owner ({@link #setInitialVisibilityForEntity}) and narrows them by the verdicts the
 *   reduced indexes give per referenced key ({@link #restrictTo}, {@link #forbidAllExcept}, ...), each of which
 *   touches every known owner.
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
	private final Function<ReferenceContract, RepresentativeReferenceKey> representativeKeyProducer;
	/**
	 * Contains the source entity PK to allowed referenced entity PKs index.
	 * Key: source entity primary key
	 * Value: allowed referenced primary keys
	 */
	private final IntObjectMap<RepresentativeMapping> mapping;
	/**
	 * How the verdicts are recorded and read - fixed by the constructor.
	 */
	private final Verdicts verdicts;
	/**
	 * Internal helper variable containing a {@link PersistentRoaringBitmap} with all source entity primary keys present
	 * in {@link #mapping}. Lazily initialized on the first call to
	 * {@link #restrictTo(RepresentativeReferenceKey, Bitmap)} and cached for subsequent calls.
	 */
	private PersistentRoaringBitmap knownEntityPrimaryKeys;

	/**
	 * Creates a new validity mapping the index route narrows key by key - starting from
	 * {@link #setInitialVisibilityForEntity} and refined by the restricting methods.
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
		this(expectedEntityCount, referenceSchema, Verdicts.INDEX_ROUTE);
	}

	/**
	 * Creates a new validity mapping the fetch fills row by row through {@link #admit(int, ReferenceContract)}.
	 *
	 * The representative key of a row is built only when it can carry a verdict of its own - for a reference allowing
	 * duplicates whose rows are judged by a filter. Without a filter the verdict of a row depends on its referenced
	 * primary key alone (whether the referenced entity exists), and a reference without duplicates has nothing but
	 * that key to tell its rows apart, so in both cases the mapping records the referenced primary keys only and
	 * builds no representative key at all.
	 *
	 * @param expectedEntityCount the expected number of source entities, used to pre-size internal data structures
	 * @param referenceSchema     the schema of the reference being fetched
	 * @param rowVerdicts         TRUE when the rows are judged by a filter, so that two rows referencing the same
	 *                            entity may be given different verdicts; FALSE when the verdict of a row depends on
	 *                            its referenced primary key alone
	 */
	public ValidEntityToReferenceMapping(
		int expectedEntityCount,
		@Nonnull ReferenceSchema referenceSchema,
		boolean rowVerdicts
	) {
		this(
			expectedEntityCount,
			referenceSchema,
			rowVerdicts && referenceSchema.getCardinality().allowsDuplicates() ?
				Verdicts.BY_ROW : Verdicts.BY_PRIMARY_KEY
		);
	}

	/**
	 * Creates a new validity mapping recording its verdicts the passed way.
	 *
	 * @param expectedEntityCount the expected number of source entities, used to pre-size internal data structures
	 * @param referenceSchema     the schema of the reference being fetched
	 * @param verdicts            how the verdicts are recorded and read
	 */
	private ValidEntityToReferenceMapping(
		int expectedEntityCount,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull Verdicts verdicts
	) {
		this.verdicts = verdicts;
		this.mapping = new IntObjectHashMap<>(expectedEntityCount);
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			// representative values are read off the RAW contract, never off a decorator wrapping it:
			// `ReferenceDecorator` narrows its attribute values by the request's attribute predicate, so a
			// representative attribute the request did not ask for would read as its default here and produce
			// a different key than the same reference produces on the pre-decoration path
			this.representativeKeyProducer = ref -> new RepresentativeReferenceKey(
				ref.getReferenceKey(),
				rad.getRepresentativeValues(ref instanceof ReferenceDecorator rd ? rd.getDelegate() : ref));
		} else {
			this.representativeKeyProducer = ref -> new RepresentativeReferenceKey(ref.getReferenceKey());
		}
	}

	/**
	 * Admits an existing reference row of the owner entity - the row passed the filter of the fetched references (or
	 * there is none) and its referenced entity passed the existence check of the fetch. The referenced primary key of
	 * the row becomes one of the owner's valid referenced entities, and the row itself becomes selected for the owner
	 * - together with every other existing row of the owner referencing the same entity, unless the mapping keeps
	 * a verdict per row (see {@link #ValidEntityToReferenceMapping(int, ReferenceSchema, boolean)}).
	 *
	 * Admitting the same row twice changes nothing.
	 *
	 * @param ownerPrimaryKey the primary key of the owner entity
	 * @param row             the existing reference row of the owner entity, read off the raw entity
	 */
	public void admit(int ownerPrimaryKey, @Nonnull ReferenceContract row) {
		Assert.isPremiseValid(
			this.verdicts != Verdicts.INDEX_ROUTE,
			"The validity mapping of the index route records no verdicts of single rows!"
		);
		Assert.isPremiseValid(row.exists(), "A dropped reference row cannot be admitted!");
		RepresentativeMapping ownerMapping = this.mapping.get(ownerPrimaryKey);
		if (ownerMapping == null) {
			ownerMapping = new RepresentativeMapping(this.representativeKeyProducer);
			this.mapping.put(ownerPrimaryKey, ownerMapping);
		}
		if (this.verdicts == Verdicts.BY_ROW) {
			ownerMapping.admit(row);
		} else {
			ownerMapping.add(row.getReferencedPrimaryKey());
		}
	}

	/**
	 * Returns TRUE when the mapping is filled row by row ({@link #admit(int, ReferenceContract)}): a referenced key
	 * admitted for an owner then does not select every row of the owner holding it - a dropped row never, and a row
	 * of a reference allowing duplicates judged by a filter only when it was admitted itself - so the rows have to be
	 * asked one by one ({@link #isReferenceSelected}). FALSE for the mapping of the index route, which is narrowed key
	 * by key.
	 *
	 * @return TRUE for a mapping filled row by row
	 */
	public boolean isFilledRowByRow() {
		return this.verdicts != Verdicts.INDEX_ROUTE;
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
				final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
					writer.add(entry.key);
				}
				this.knownEntityPrimaryKeys = writer.get();
			}
			final PersistentRoaringBitmap invalidRecords = PersistentRoaringBitmap.andNot(
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
	 *
	 * - A mapping filled row by row selects an existing reference that was admitted for the entity - by its referenced
	 *   primary key, or by its representative key when the mapping keeps a verdict per row. An entity nothing was
	 *   admitted for selects nothing.
	 * - The mapping of the index route delegates to {@link RepresentativeMapping#contains(int, ReferenceContract)},
	 *   which evaluates both simple primary key presence and representative key restrictions.
	 *
	 * @param entityPrimaryKey the primary key of the source entity
	 * @param reference        the reference to check visibility for, raw or decorated
	 * @return `true` if the reference is allowed for the given entity, `false` otherwise
	 */
	public boolean isReferenceSelected(int entityPrimaryKey, @Nonnull ReferenceContract reference) {
		// deliberately not `ofNullable(...).map(...).orElse(false)`: this is asked about every reference of every
		// fetched entity, and that form costs two Optionals, a capturing lambda and a boxed Boolean per call
		final RepresentativeMapping mappingForEntity = this.mapping.get(entityPrimaryKey);
		if (mappingForEntity == null) {
			return false;
		}
		return switch (this.verdicts) {
			case BY_PRIMARY_KEY -> reference.exists() &&
				mappingForEntity.containsReferencedPrimaryKey(reference.getReferencedPrimaryKey());
			case BY_ROW -> reference.exists() && mappingForEntity.isAdmitted(reference);
			case INDEX_ROUTE -> mappingForEntity.contains(entityPrimaryKey, reference);
		};
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("Valid references: ");
		for (IntObjectCursor<RepresentativeMapping> entry : this.mapping) {
			sb.append("\n   ").append(entry.key).append(" -> ").append(entry.value);
		}
		return sb.toString();
	}

	/**
	 * How a mapping records its verdicts and reads them back.
	 */
	private enum Verdicts {
		/**
		 * The verdicts are given per referenced key and applied to every known owner by the restricting methods of the
		 * index route.
		 */
		INDEX_ROUTE,
		/**
		 * The rows are admitted one by one, and a row is selected by its referenced primary key - no representative
		 * key is ever built.
		 */
		BY_PRIMARY_KEY,
		/**
		 * The rows are admitted one by one, and a row is selected by its representative key - two rows referencing
		 * the same entity keep verdicts of their own.
		 */
		BY_ROW
	}

}
