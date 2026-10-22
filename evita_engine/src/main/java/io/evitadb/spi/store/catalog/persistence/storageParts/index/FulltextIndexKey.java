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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Compressed key of a fulltext index root part: the locale of the partition the index serves. One entity index holds
 * at most one fulltext index per locale, so the locale alone tells its roots apart, and the owning entity index's
 * primary key is packed beside the compressed id by {@link FulltextIndexStoragePart#computeUniquePartId}.
 *
 * The locale is never `null`: a searchable field must be localized, because a fulltext index is partitioned by
 * locale and a non-localized value has no partition to live in.
 *
 * @param locale the locale of the partition the fulltext index serves
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record FulltextIndexKey(@Nonnull Locale locale) implements Comparable<FulltextIndexKey>, Serializable {

	@Serial private static final long serialVersionUID = 65083013947898264L;

	@Override
	public int compareTo(@Nonnull FulltextIndexKey o) {
		return this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
	}

}
