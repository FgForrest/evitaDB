/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceAttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Reference decorator class envelopes any {@link Reference} and allows to filter out properties that are not passing predicate
 * conditions. This allows us to reuse rich {@link Reference} / {@link Entity} objects from the cache even if clients requests thinner ones.
 * For example if we have full-blown entity in our cache and client asks for entity in English language, we can use
 * entity decorator to hide all attributes that refers to other languages than English one.
 *
 * We try to keep evitaDB responses consistent and provide only those type of data that were really requested in the query
 * and avoid inconsistent situations that richer data are returned just because the entity was found in cache in a form
 * that more than fulfills the request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceDecorator implements ReferenceContract {
	@Serial private static final long serialVersionUID = 1098992030664849469L;

	/**
	 * Contains reference to the (possibly richer than requested) entity object.
	 */
	@Getter private final ReferenceContract delegate;
	/**
	 * Contains body of the referenced entity. The body is accessible only when the input request (query) contains
	 * requirements for fetching entity (i.e. {@link EntityFetch}) in the {@link ReferenceContent} requirement.
	 */
	private final SealedEntity referencedEntity;
	/**
	 * Contains body of the referenced entity group. The body is accessible only when the input request (query) contains
	 * requirements for fetching entity (i.e. {@link EntityGroupFetch}) in the {@link ReferenceContent} requirement.
	 */
	private final SealedEntity referencedGroupEntity;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	@Getter private final ReferenceAttributeValueSerializablePredicate attributePredicate;
	/**
	 * Optimization that ensures that expensive attributes filtering using predicates happens only once.
	 */
	private List<AttributeValue> filteredAttributes;
	/**
	 * Optimization that ensures that expensive attribute locale resolving happens only once.
	 */
	private Set<Locale> attributeLocales;

	public ReferenceDecorator(@Nonnull ReferenceContract delegate, @Nonnull ReferenceAttributeValueSerializablePredicate attributePredicate) {
		if (delegate instanceof ReferenceDecorator referenceDecorator) {
			this.delegate = referenceDecorator.delegate;
			this.referencedEntity = referenceDecorator.referencedEntity;
			this.referencedGroupEntity = referenceDecorator.referencedGroupEntity;
		} else {
			this.delegate = delegate;
			this.referencedEntity = null;
			this.referencedGroupEntity = null;
		}
		this.attributePredicate = attributePredicate;
	}

	@Override
	public boolean isDropped() {
		return delegate.isDropped();
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey() {
		return delegate.getReferenceKey();
	}

	@Nonnull
	@Override
	public String getReferencedEntityType() {
		return delegate.getReferencedEntityType();
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getReferencedEntity() {
		return ofNullable(referencedEntity);
	}

	@Nonnull
	@Override
	public Cardinality getReferenceCardinality() {
		return delegate.getReferenceCardinality();
	}

	@Nonnull
	@Override
	public Optional<GroupEntityReference> getGroup() {
		return delegate.getGroup().filter(Droppable::exists);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getGroupEntity() {
		return ofNullable(referencedGroupEntity);
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return delegate.getReferenceSchema();
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract getReferenceSchemaOrThrow() {
		return delegate.getReferenceSchemaOrThrow();
	}

	@Override
	public int getVersion() {
		return delegate.getVersion();
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return getAttributeValue(attributeName)
			.map(it -> (T) it.getValue())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return getAttributeValue(attributeName)
			.map(it -> (T[]) it.getValue())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		final Optional<AttributeValue> result;
		if (attributePredicate.isLocaleSet()) {
			final Locale locale = attributePredicate.getLocale();
			result = locale == null ?
				delegate.getAttributeValue(attributeName) : delegate.getAttributeValue(attributeName, locale);
		} else {
			result = delegate.getAttributeValue(attributeName);
		}
		return result.filter(attributePredicate);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return delegate.getAttributeValue(attributeName, locale)
			.filter(attributePredicate)
			.map(it -> (T) it.getValue())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return delegate.getAttributeValue(attributeName, locale)
			.filter(attributePredicate)
			.map(it -> (T[]) it.getValue())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		return delegate.getAttributeValue(attributeName, locale)
			.filter(attributePredicate);
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return delegate.getAttributeSchema(attributeName);
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return getAttributeValues()
			.stream()
			.map(it -> it.getKey().getAttributeName())
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return getAttributeValues()
			.stream()
			.map(AttributeValue::getKey)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return delegate.getAttributeValue(attributeKey)
			.filter(attributePredicate);
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues() {
		if (filteredAttributes == null) {
			filteredAttributes = delegate.getAttributeValues()
				.stream()
				.filter(attributePredicate)
				.collect(Collectors.toList());
		}
		return filteredAttributes;
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		return getAttributeValues()
			.stream()
			.filter(it -> attributeName.equals(it.getKey().getAttributeName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAttributeLocales() {
		if (this.attributeLocales == null) {
			this.attributeLocales = getAttributeValues()
				.stream()
				.map(AttributeValue::getKey)
				.map(AttributeKey::getLocale)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		}
		return this.attributeLocales;
	}

	@Override
	public boolean equals(Object obj) {
		return delegate.equals(obj instanceof ReferenceDecorator ? ((ReferenceDecorator) obj).delegate : obj);
	}

	@Override
	public int hashCode() {
		return delegate.hashCode();
	}

	@Override
	public String toString() {
		return delegate.toString();
	}

}
