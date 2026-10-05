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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * The root record of one fulltext index - the index of one locale partition of an entity index. It carries no terms
 * and no lengths itself; it says how to read the pages that do, and with which configuration they were written:
 *
 * - the **field registry** in field-id order - the id of a field is its position, and it is the key prefix of every
 *   term the field owns, so the order is part of the format - with each field's identity, its length pivot, whether
 *   it was retired, and the blocks of its length table that have a page;
 * - the **dictionary page list** - the sequences of the dictionary's leaf pages in key order, which a load assembles
 *   into the tree leaf by leaf - and the high-water of the dictionary's page-sequence allocator, so a sequence is never
 *   handed out twice across a restart;
 * - the **analyzer** the terms were produced by and the **default pivot** a field registered later gets.
 *
 * The pages themselves are {@link FulltextDictionaryLeafPagePart}s, and the length blocks pages of their own; both
 * are keyed by a {@link FulltextLeafStreamKey} of this index.
 *
 * The analyzer and the pivots are persisted here only until the schema owns them: an index must not be read back
 * with a different analyzer or pivot than it was built with, and today nothing else remembers which it was.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ToString(of = {"entityIndexPrimaryKey", "locale"})
public class FulltextIndexStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = -8709870492189630144L;

	/**
	 * Primary key of the owning entity index.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * The locale of the partition the index serves.
	 */
	@Getter @Nonnull private final Locale locale;
	/**
	 * Name of the analyzer that produced the index's terms.
	 */
	//TODO JNO change it at the end of #258
	@Getter @Nonnull private final String analyzerName;
	/**
	 * Length pivot a field registered without one gets.
	 */
	//TODO JNO change it at the end of #258
	@Getter private final double defaultLengthPivot;
	/**
	 * The registered fields, the i-th having field id `i`, retired ones included.
	 */
	@Getter @Nonnull private final FieldEntry[] fields;
	/**
	 * The highest page sequence the dictionary ever allocated.
	 */
	@Getter private final int dictionaryHighWaterPageSequence;
	/**
	 * Sequences of the dictionary's leaf pages in ascending key order. Never empty: an empty dictionary is the single
	 * empty page of its root leaf.
	 */
	@Getter @Nonnull private final int[] dictionaryPageSequences;
	/**
	 * The storage-part primary key `pack(entityIndexPrimaryKey, id of the FulltextIndexKey)`; `null` until assigned.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * One registered field.
	 *
	 * A retired field is persisted with everything it held, because its postings are still in the dictionary pages
	 * under its id: dropping its entry would shift the ids of every later field off their key prefixes.
	 *
	 * @param key          the field's identity
	 * @param lengthPivot  the field's length pivot, which every impact of the field was computed with
	 * @param retired      whether the field was retired - its key resolves to no field, or to a later one
	 * @param lengthBlocks the high 16 bits of the primary keys of every block of the field's length table that has a
	 *                     page, strictly ascending
	 */
	public record FieldEntry(
		@Nonnull FulltextFieldKey key,
		//TODO JNO change it at the end of #258
		double lengthPivot,
		boolean retired,
		@Nonnull int[] lengthBlocks
	) {

		/**
		 * Verifies the block list is a strictly ascending list of 16-bit block keys.
		 */
		public FieldEntry {
			for (int i = 0; i < lengthBlocks.length; i++) {
				final int block = lengthBlocks[i];
				final int previous = i == 0 ? -1 : lengthBlocks[i - 1];
				Assert.isPremiseValid(
					block > previous && block <= 0xFFFF,
					() -> "The length blocks of fulltext field " + key + " must be strictly ascending 16-bit " +
						"block keys, but " + block + " follows " + previous + "!"
				);
			}
		}

	}

	/**
	 * Creates the root record.
	 *
	 * @param entityIndexPrimaryKey           primary key of the owning entity index
	 * @param locale                          the locale of the partition the index serves
	 * @param analyzerName                    name of the analyzer that produced the index's terms
	 * @param defaultLengthPivot              length pivot a field registered without one gets
	 * @param fields                          the registered fields in field-id order
	 * @param dictionaryHighWaterPageSequence the highest page sequence the dictionary ever allocated
	 * @param dictionaryPageSequences         the dictionary's page sequences in key order, at least one
	 * @param storagePartPK                   the precomputed primary key, or `null` to have it computed
	 */
	public FulltextIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		@Nonnull String analyzerName,
		double defaultLengthPivot,
		@Nonnull FieldEntry[] fields,
		int dictionaryHighWaterPageSequence,
		@Nonnull int[] dictionaryPageSequences,
		@Nullable Long storagePartPK
	) {
		Assert.isPremiseValid(
			dictionaryPageSequences.length > 0,
			"A fulltext dictionary is persisted as at least one page!"
		);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.analyzerName = analyzerName;
		this.defaultLengthPivot = defaultLengthPivot;
		this.fields = fields;
		this.dictionaryHighWaterPageSequence = dictionaryHighWaterPageSequence;
		this.dictionaryPageSequences = dictionaryPageSequences;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Computes the primary key of the root record of the fulltext index of the passed locale in the passed entity
	 * index.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the index serves
	 * @param keyCompressor         the compressor the locale key is registered in
	 * @return the 64-bit storage-part primary key
	 */
	public static long computeUniquePartId(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		@Nonnull KeyCompressor keyCompressor
	) {
		return NumberUtils.pack(entityIndexPrimaryKey, keyCompressor.getId(new FulltextIndexKey(locale)));
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = StoragePart.verifyUniquePartId(
			computeUniquePartId(this.entityIndexPrimaryKey, this.locale, keyCompressor),
			this.storagePartPK
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

}
