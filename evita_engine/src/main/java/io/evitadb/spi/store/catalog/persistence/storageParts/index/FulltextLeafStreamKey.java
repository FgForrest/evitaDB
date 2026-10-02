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

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Identity of one page stream of a fulltext index, registered in the catalog header's {@link KeyCompressor}: the
 * compressed id of this key is the `streamId` half of every page's primary key `pack(streamId, pageSequence)`.
 *
 * The owning entity index's primary key is part of the identity because a stream id is catalog-wide while the same
 * locale has a fulltext index in many entity indexes; the {@link StreamKind} tells apart the streams one index writes,
 * whose page sequences would otherwise collide.
 *
 * @param entityIndexPrimaryKey primary key of the owning entity index
 * @param locale                the locale of the partition the fulltext index serves
 * @param streamKind            which of the index's page streams this key identifies
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record FulltextLeafStreamKey(
	int entityIndexPrimaryKey,
	@Nonnull Locale locale,
	@Nonnull StreamKind streamKind
) implements Comparable<FulltextLeafStreamKey>, Serializable {

	@Serial private static final long serialVersionUID = -983489469258262080L;

	/**
	 * The page streams of one fulltext index.
	 */
	public enum StreamKind {

		/**
		 * The term dictionary's leaf pages, one per leaf of its bucket tree, numbered by the dictionary's
		 * page-sequence allocator.
		 */
		DICTIONARY,
		/**
		 * The field length tables' blocks, numbered by the field id and the high 16 bits of the primary keys the
		 * block covers.
		 */
		FIELD_LENGTHS

	}

	@Override
	public int compareTo(@Nonnull FulltextLeafStreamKey o) {
		final int pkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		if (pkResult != 0) {
			return pkResult;
		}
		final int localeResult = this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
		return localeResult != 0 ? localeResult : this.streamKind.compareTo(o.streamKind);
	}

}
