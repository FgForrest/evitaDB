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

import io.evitadb.index.fulltext.ReferenceFulltextValueIndex;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;

import javax.annotation.Nonnull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Compressed identity of one reference value child stream in a field generation.
 * @param entityIndexPrimaryKey owning global index
 * @param locale owner locale partition
 * @param fieldId immutable field generation
 * @param childKind typed child stream
 */
public record FulltextReferenceValueStreamKey(
	int entityIndexPrimaryKey, @Nonnull Locale locale, int fieldId, @Nonnull ChildKind childKind
) implements Comparable<FulltextReferenceValueStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = 2578326734550091082L;

	/**
	 * Checks the registry can address this generation's child streams before any page allocation.
	 * @param entityIndexPrimaryKey owning index identity
	 * @param locale partition locale
	 * @param fieldId stable generation id
	 * @param childKind child identity
	 */
	public FulltextReferenceValueStreamKey {
		ReferenceFulltextValueIndex.streamNumber(fieldId, childKind);
	}

	@Override
	public int compareTo(@Nonnull FulltextReferenceValueStreamKey other) {
		int comparison = Integer.compare(this.entityIndexPrimaryKey, other.entityIndexPrimaryKey);
		if (comparison == 0) {
			comparison = this.locale.toLanguageTag().compareTo(other.locale.toLanguageTag());
		}
		if (comparison == 0) {
			comparison = Integer.compare(this.fieldId, other.fieldId);
		}
		return comparison == 0 ? this.childKind.compareTo(other.childKind) : comparison;
	}
}
