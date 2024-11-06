/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This auxiliary interface allows to get existing attribute value for particular attribute key and retrieve set
 * of available locales for the updated entity.
 */
public interface ExistingAttributeValueSupplier {

	/**
	 * Empty implementation of the {@link ExistingAttributeValueSupplier} interface.
	 */
	ExistingAttributeValueSupplier NO_EXISTING_VALUE_SUPPLIER = new ExistingAttributeValueSupplier() {
		@Nonnull
		@Override
		public Set<Locale> getEntityAttributeLocales() {
			return Collections.emptySet();
		}

		@Nonnull
		@Override
		public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public Stream<AttributeValue> getAttributeValues() {
			return Stream.empty();
		}

		@Nonnull
		@Override
		public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
			return Stream.empty();
		}
	};

	/**
	 * Returns complete set of locales for the entity attributes.
	 */
	@Nonnull
	Set<Locale> getEntityAttributeLocales();

	/**
	 * Returns existing attribute value for particular attribute key.
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey);

	/**
	 * Returns stream of all attribute values for the entity.
	 */
	@Nonnull
	Stream<AttributeValue> getAttributeValues();

	/**
	 * Returns stream of all attribute values for the entity in particular locale.
	 */
	@Nonnull
	Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale);

}
