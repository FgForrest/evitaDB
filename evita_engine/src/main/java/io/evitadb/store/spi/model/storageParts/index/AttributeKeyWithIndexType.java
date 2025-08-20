/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.ComparatorUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * This DTO distinguishes different {@link AttributeIndexStoragePart} instances. It uniquely identifies the attribute
 * index by attribute name, locale and index type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public class AttributeKeyWithIndexType implements Comparable<AttributeKeyWithIndexType>, Serializable {
	@Serial private static final long serialVersionUID = 2526424804226344907L;

	/**
	 * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
	 * single entity instance.
	 */
	@Getter private final String attributeName;
	/**
	 * Contains locale in case the attribute is locale specific (i.e. {@link AttributeSchema#isLocalized()}
	 */
	@Getter private final Locale locale;
	/**
	 * Contains type of the index that is represented by this part - multiple parts/indexes may target same attribute
	 * and this enum is used to distinguish these indexes among themselves by type.
	 */
	@Getter private final AttributeIndexType indexType;

	/**
	 * Constructor for the locale specific attribute.
	 */
	public AttributeKeyWithIndexType(String attributeName, Locale locale, AttributeIndexType indexType) {
		this.attributeName = attributeName;
		this.locale = locale;
		this.indexType = indexType;
	}

	/**
	 * Constructor for the locale specific attribute.
	 */
	public AttributeKeyWithIndexType(@Nonnull AttributeKey attributeKey, @Nonnull AttributeIndexType indexType) {
		this.attributeName = attributeKey.attributeName();
		this.locale = attributeKey.locale();
		this.indexType = indexType;
	}

	/**
	 * Returns true if attribute is localized.
	 */
	public boolean isLocalized() {
		return this.locale != null;
	}

	@Override
	public int compareTo(AttributeKeyWithIndexType o) {
		return ComparatorUtils.compareLocale(
			this.locale, o.locale,
			() -> {
				final int attributeNameResult = this.attributeName.compareTo(o.attributeName);
				if (attributeNameResult == 0) {
					return this.indexType.compareTo(o.indexType);
				} else {
					return attributeNameResult;
				}
			}
		);
	}

}
