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

import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValuePage;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serial;
import java.util.Locale;
import java.util.Objects;

/** One typed reference value child leaf, keyed by its owning generation and child stream. */
public class FulltextReferenceValueLeafPagePart extends AbstractLeafPagePart {
	/** Identity of this storage-part format. */
	@Serial private static final long serialVersionUID = -7030474820759200563L;
	/** Owning index on the write path, null on reload. */
	@Nullable private final Integer entityIndexPrimaryKey;
	/** Locale partition on the write path, null on reload. */
	@Nullable private final Locale locale;
	/** Owning generation, also retained in the payload to validate field association on reload. */
	@Getter private final int fieldId;
	/** The typed child leaf. */
	@Nonnull @Getter private final ValuePage page;

	/**
	 * Creates a page whose stream is resolved on write.
	 *
	 * @param entityIndexPrimaryKey owning entity index
	 * @param locale locale partition
	 * @param fieldId immutable field generation
	 * @param page leaf contents
	 */
	public FulltextReferenceValueLeafPagePart(int entityIndexPrimaryKey, @Nonnull Locale locale, int fieldId,
		@Nonnull ValuePage page) {
		super(page.pageSequence());
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.fieldId = fieldId;
		this.page = page;
	}

	/**
	 * Creates a page read from a resolved stream.
	 *
	 * @param streamId resolved stream id
	 * @param fieldId immutable field generation
	 * @param page leaf contents
	 */
	public FulltextReferenceValueLeafPagePart(int streamId, int fieldId, @Nonnull ValuePage page) {
		super(streamId, page.pageSequence(), computeUniquePartId(streamId, page.pageSequence()));
		this.entityIndexPrimaryKey = null;
		this.locale = null;
		this.fieldId = fieldId;
		this.page = page;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		return keyCompressor.getId(new FulltextReferenceValueStreamKey(
			Objects.requireNonNull(this.entityIndexPrimaryKey), Objects.requireNonNull(this.locale),
			this.fieldId, this.page.kind()));
	}
}
