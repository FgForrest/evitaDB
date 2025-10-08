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

package io.evitadb.store.spi.model.storageParts.index;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;

/**
 * Composite key that allows to uniquely identify attribute index (unique, filter, sort, chain) by combination of
 * attribute name, reference name and locale.
 *
 * @param referenceName might be null when the attribute is defined on entity level and not on reference
 * @param attributeName name of the attribute
 * @param locale        might be null when the attribute is not localized
 */
public record AttributeIndexKey(
	@Nullable String referenceName,
	@Nonnull String attributeName,
	@Nullable Locale locale
) implements Comparable<AttributeIndexKey>, Serializable {

	@Override
	public int compareTo(@Nonnull AttributeIndexKey o) {
		if (this.referenceName == null) {
			if (o.referenceName != null) {
				return -1;
			}
		} else {
			if (o.referenceName == null) {
				return 1;
			} else {
				final int referenceComparison = this.referenceName.compareTo(o.referenceName);
				if (referenceComparison != 0) {
					return referenceComparison;
				}
			}
		}

		final int attributeComparison = this.attributeName.compareTo(o.attributeName);
		if (attributeComparison != 0) {
			return attributeComparison;
		}

		if (this.locale == null) {
			if (o.locale != null) {
				return -1;
			} else {
				return 0;
			}
		} else {
			if (o.locale == null) {
				return 1;
			} else {
				return this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
			}
		}
	}
}
