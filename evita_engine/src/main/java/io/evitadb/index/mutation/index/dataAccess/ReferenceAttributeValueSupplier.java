/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This is auxiliary class that allows accessing attributes and attribute related data directly from an entity reference.
 */
@NotThreadSafe
@RequiredArgsConstructor
class ReferenceAttributeValueSupplier implements ExistingAttributeValueSupplier {
	private final Entity entity;
	private final ReferenceContract reference;

	@Nonnull
	@Override
	public Set<Locale> getEntityExistingAttributeLocales() {
		return this.entity.getAttributeLocales();
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return this.reference.getAttributeValue(attributeKey).filter(Droppable::exists);
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues() {
		return this.reference.getAttributeValues()
			.stream()
			.filter(Droppable::exists);
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
		return this.reference.getAttributeValues()
			.stream()
			.filter(Droppable::exists)
			.filter(it -> locale.equals(it.key().locale()));
	}

}
