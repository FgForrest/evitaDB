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

import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormPage;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;
import lombok.Getter;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Objects;

/** A surface dictionary leaf: front-ordered keys and primitive reference counts, without posting lists. */
public class FulltextSurfaceFormLeafPagePart extends AbstractLeafPagePart {
	/** Identity of this storage-part format. */
	@Serial private static final long serialVersionUID = 4266005728431046721L;
	/** Owning index on the write path, null on reload. */
	@Nullable private final Integer entityIndexPrimaryKey;
	/** Locale partition on the write path, null on reload. */
	@Nullable private final Locale locale;
	/** The leaf contents, also validating count alignment. */
	@Nonnull @Getter private final SurfaceFormPage page;

	/**
	 * Creates a page whose stream is resolved on write.
	 *
	 * @param entityIndexPrimaryKey owning entity index
	 * @param locale locale partition
	 * @param page leaf contents
	 */
	public FulltextSurfaceFormLeafPagePart(int entityIndexPrimaryKey, @Nonnull Locale locale,
		@Nonnull SurfaceFormPage page) {
		super(page.pageSequence());
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.page = page;
	}

	/**
	 * Creates a page read from a resolved stream.
	 *
	 * @param streamId resolved stream id
	 * @param page leaf contents
	 */
	public FulltextSurfaceFormLeafPagePart(int streamId, @Nonnull SurfaceFormPage page) {
		super(streamId, page.pageSequence(), computeUniquePartId(streamId, page.pageSequence()));
		this.entityIndexPrimaryKey = null;
		this.locale = null;
		this.page = page;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		return keyCompressor.getId(new FulltextLeafStreamKey(
			Objects.requireNonNull(this.entityIndexPrimaryKey), Objects.requireNonNull(this.locale),
			StreamKind.SURFACE_FORMS));
	}
}
