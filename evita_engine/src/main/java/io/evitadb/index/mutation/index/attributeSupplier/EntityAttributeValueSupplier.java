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

package io.evitadb.index.mutation.index.attributeSupplier;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This is auxiliary class that allows accessing attributes and attribute related data directly from an entity instance.
 */
@NotThreadSafe
@RequiredArgsConstructor
class EntityAttributeValueSupplier implements ExistingAttributeValueSupplier {
	private final Entity entity;
	private final Set<AttributeKey> removedAttributes = CollectionUtils.createHashSet(16);

	@Nonnull
	@Override
	public Set<Locale> getEntityAttributeLocales() {
		return this.entity.getAttributeLocales();
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return this.removedAttributes.contains(attributeKey) ?
			Optional.empty() : this.entity.getAttributeValueWithoutSchemaCheck(attributeKey);
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues() {
		return this.entity.getAttributeValues()
			.stream()
			.filter(it -> this.removedAttributes.contains(it.key()));
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
		return this.entity.getAttributeValues()
			.stream()
			.filter(it -> this.removedAttributes.contains(it.key()) || locale.equals(it.key().locale()));
	}

	/**
	 * Registers the removal of the attribute specified by the given key. This method adds the given
	 * attribute key to the set of removed attributes and will hide the attribute from {@link #getAttributeValues()}
	 * and {@link #getAttributeValues(Locale)} methods.
	 *
	 * This is used to mimic behavior of making attribute dropped in {@link ContainerizedLocalMutationExecutor}, which
	 * is not used in this context (when entity changes scope).
	 *
	 * @param key the key of the attribute to be removed; must not be null
	 */
	public void registerRemoval(@Nonnull AttributeKey key) {
		this.removedAttributes.add(key);
	}

}
