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

import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Objects;

/**
 * One leaf page of a fulltext index's term dictionary: the leaf's buckets - each an encoded term key with its posting
 * list - and the impact of every posting.
 *
 * The page is keyed `pack(streamId, pageSequence)`, the stream being the {@link StreamKind#DICTIONARY} stream of its
 * index. A write-path page carries the index's identity and resolves the stream id store-side on first
 * {@link #computeUniquePartIdAndSet(KeyCompressor)}; a rehydrated page carries the stream id it was read with instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextDictionaryLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -5058184485158687201L;

	/**
	 * Primary key of the owning entity index - write-path identity; `null` on a rehydrated page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The locale of the partition the index serves - write-path identity; `null` on a rehydrated page.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * The leaf's buckets in ascending key order: the encoded term key and its posting list.
	 */
	@Nonnull @Getter private final ValueToRecord[] buckets;
	/**
	 * The impacts of each bucket, `impacts[i][j]` belonging to the j-th posting of `buckets[i]` in ascending primary
	 * key order.
	 */
	@Nonnull @Getter private final byte[][] impacts;

	/**
	 * Creates a WRITE-PATH page carrying the index identity; its stream id and primary key are resolved store-side.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the index serves
	 * @param pageSequence          the page sequence within the dictionary stream
	 * @param buckets               the leaf's buckets in ascending key order
	 * @param impacts               the impacts of each bucket, one per posting
	 */
	public FulltextDictionaryLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		int pageSequence,
		@Nonnull ValueToRecord[] buckets,
		@Nonnull byte[][] impacts
	) {
		super(pageSequence);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.buckets = buckets;
		this.impacts = verifyAlignment(buckets, impacts);
	}

	/**
	 * Creates a READ-PATH page with an already resolved stream id and primary key; the write-path identity is `null`.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the dictionary stream
	 * @param buckets       the leaf's buckets in ascending key order
	 * @param impacts       the impacts of each bucket, one per posting
	 * @param storagePartPK the precomputed primary key
	 */
	public FulltextDictionaryLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull ValueToRecord[] buckets,
		@Nonnull byte[][] impacts,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.entityIndexPrimaryKey = null;
		this.locale = null;
		this.buckets = buckets;
		this.impacts = verifyAlignment(buckets, impacts);
	}

	/**
	 * Checks there is exactly one impact per posting. An impact column out of step with its postings would score every
	 * posting past the first divergence with another posting's impact, which no query could ever notice, so it is
	 * refused at construction.
	 *
	 * @param buckets the page's buckets
	 * @param impacts the page's impacts
	 * @return the impacts, unchanged
	 */
	@Nonnull
	private static byte[][] verifyAlignment(@Nonnull ValueToRecord[] buckets, @Nonnull byte[][] impacts) {
		Assert.isPremiseValid(
			impacts.length == buckets.length,
			() -> "A fulltext dictionary page must carry the impacts of every bucket, but has " + impacts.length +
				" impact columns for " + buckets.length + " buckets!"
		);
		for (int i = 0; i < buckets.length; i++) {
			final int postingCount = buckets[i].getRecordIds().size();
			final int impactCount = impacts[i].length;
			final int bucketIndex = i;
			Assert.isPremiseValid(
				postingCount == impactCount,
				() -> "Bucket " + bucketIndex + " of a fulltext dictionary page has " + postingCount +
					" postings but " + impactCount + " impacts!"
			);
		}
		return impacts;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// only a write-path page carries the identity; a rehydrated one already has its stream id
		final String missingIdentity = "A dictionary page must carry its index identity to resolve the stream id!";
		return keyCompressor.getId(
			new FulltextLeafStreamKey(
				Objects.requireNonNull(this.entityIndexPrimaryKey, missingIdentity),
				Objects.requireNonNull(this.locale, missingIdentity),
				StreamKind.DICTIONARY
			)
		);
	}

}
