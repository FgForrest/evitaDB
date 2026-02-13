/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Exception thrown when a query or entity operation requires locale information but none is provided.
 *
 * evitaDB supports localized attributes and associated data that vary by language/locale. When accessing localized
 * properties, the system must know which locale to retrieve. This exception is thrown when:
 *
 * - **Query filtering** uses localized attributes without specifying locale via `entityLocaleEquals()`
 * - **Entity fetching** requests localized attributes or associated data without locale context
 * - **Unique attribute lookups** access locale-specific unique attributes without providing locale
 * - **Index operations** attempt to retrieve unique values from locale-specific unique indexes without locale
 *
 * **Common scenarios:**
 *
 * - Filtering by localized attribute: `attributeEquals('title', 'Product')` when `title` is localized but no
 *   `entityLocaleEquals(Locale.ENGLISH)` is present
 * - Fetching localized data: `attributeContent('description')` when `description` is localized but query has no locale
 * - Global unique attribute access across locales where locale-specific uniqueness applies
 *
 * **Special cases:**
 *
 * - Attributes marked as globally unique (not locale-specific) can be accessed without locale
 * - Some attributes may be unique within locale but the index allows cross-locale access in specific contexts
 *
 * **Resolution**: Add `entityLocaleEquals(locale)` constraint to the query filter, or ensure the query context
 * includes locale information when accessing localized entity properties.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
public class EntityLocaleMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -252413231677437811L;
	/**
	 * Names of localized attributes that require locale information.
	 */
	@Getter private final String[] attributeNames;
	/**
	 * Names of localized associated data that require locale information.
	 */
	@Getter private final String[] associatedDataNames;

	/**
	 * Creates exception for localized attributes accessed without locale.
	 *
	 * @param attributeNames names of the localized attributes that cannot be accessed without locale
	 */
	public EntityLocaleMissingException(
		@Nonnull String... attributeNames
	) {
		super(
			"Query requires localized attributes: `" + String.join(", ", attributeNames) + "`" +
			", and doesn't provide information about the locale!"
		);
		this.attributeNames = attributeNames;
		this.associatedDataNames = new String[0];
	}

	/**
	 * Creates exception for localized attributes and/or associated data accessed without locale.
	 *
	 * @param attributeNames names of the localized attributes requiring locale, or null if none
	 * @param associatedDataNames names of the localized associated data requiring locale, or null if none
	 */
	public EntityLocaleMissingException(
		@Nullable String[] attributeNames,
		@Nullable String[] associatedDataNames
	) {
		super(
			"Query requires localized " +
				(ArrayUtils.isEmpty(attributeNames) ? "" : "attributes: `" + String.join(", ", attributeNames) + "`") +
				(ArrayUtils.isEmpty(attributeNames) || ArrayUtils.isEmpty(associatedDataNames) ? "" : " and ") +
				(ArrayUtils.isEmpty(associatedDataNames) ? "" : "associated data: `" + String.join(", ", associatedDataNames) + "`") +
				", and doesn't provide information about the locale!"
		);
		this.attributeNames = attributeNames;
		this.associatedDataNames = associatedDataNames;
	}

}
