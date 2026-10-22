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

import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
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
 * One block of one field length table of a fulltext index: the lengths of the entities whose primary keys share their
 * high 16 bits.
 *
 * The page is keyed `pack(streamId, pageSequence)`, the stream being the {@link StreamKind#FIELD_LENGTHS} stream of its
 * index. A block needs no allocated sequence: its identity is already stable, so the page sequence is the field id and
 * the block key packed into one int - see {@link #pageSequenceOf(int, int)} - and a block that empties and fills again
 * is written under the key it had.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextFieldLengthBlockPart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = 3190867898811927373L;

	/**
	 * The highest field id a block page can name: the field id takes the 15 bits above the block key, so the page
	 * sequence stays non-negative.
	 */
	public static final int MAX_FIELD_ID = 0x7FFF;

	/**
	 * The highest block key: a block key is the high 16 bits of a primary key, and it takes the low 16 bits of the page
	 * sequence - so the value is also the mask that reads it back out of one.
	 */
	public static final int MAX_BLOCK_KEY = 0xFFFF;

	/**
	 * Primary key of the owning entity index - write-path identity; `null` on a rehydrated page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The locale of the partition the index serves - write-path identity; `null` on a rehydrated page.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * The block.
	 */
	@Nonnull @Getter private final LengthBlock block;

	/**
	 * Packs a field id and a block key into the page sequence of the block's page.
	 *
	 * @param fieldId  the field id, at most {@link #MAX_FIELD_ID}
	 * @param blockKey the block key, at most {@link #MAX_BLOCK_KEY}
	 * @return the page sequence
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the field id does not fit
	 */
	public static int pageSequenceOf(int fieldId, int blockKey) {
		Assert.isPremiseValid(
			fieldId >= 0 && fieldId <= MAX_FIELD_ID,
			() -> "A fulltext index persists at most " + (MAX_FIELD_ID + 1) + " fields, field id " + fieldId +
				" does not fit!"
		);
		return fieldId << 16 | blockKey;
	}

	/**
	 * Creates a WRITE-PATH page carrying the index identity; its stream id and primary key are resolved store-side.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the index serves
	 * @param fieldId               id of the field whose table the block belongs to
	 * @param block                 the block
	 */
	public FulltextFieldLengthBlockPart(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		int fieldId,
		@Nonnull LengthBlock block
	) {
		super(pageSequenceOf(fieldId, block.blockKey()));
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.block = block;
	}

	/**
	 * Creates a READ-PATH page with an already resolved stream id and primary key; the write-path identity is `null`.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence, the packed field id and block key
	 * @param lows          low 16 bits of the primary keys of the block's entities, ascending
	 * @param lengths       their encoded lengths
	 * @param storagePartPK the precomputed primary key
	 */
	public FulltextFieldLengthBlockPart(
		int streamId,
		int pageSequence,
		@Nonnull char[] lows,
		@Nonnull byte[] lengths,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.entityIndexPrimaryKey = null;
		this.locale = null;
		this.block = new LengthBlock(pageSequence & MAX_BLOCK_KEY, lows, lengths);
	}

	/**
	 * Returns the id of the field whose table the block belongs to.
	 *
	 * @return the field id
	 */
	public int getFieldId() {
		return getPageSequence() >>> 16;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// only a write-path page carries the identity; a rehydrated one already has its stream id
		final String missingIdentity = "A length block page must carry its index identity to resolve the stream id!";
		return keyCompressor.getId(
			new FulltextLeafStreamKey(
				Objects.requireNonNull(this.entityIndexPrimaryKey, missingIdentity),
				Objects.requireNonNull(this.locale, missingIdentity),
				StreamKind.FIELD_LENGTHS
			)
		);
	}

}
