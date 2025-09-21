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

package io.evitadb.index.mutation.storagePart;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This implementation of {@link ReflectedReferenceAttributeValueProvider} is based on array of {@link ReferenceKey}
 * that are found in index of referenced entity. It's used to handle situations where new entity is inserted to database
 * and we need to analyze all existing entities that already refer to this newly created entity and create their
 * (reflected) reference counterparts in this newly created entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
class ReferencedEntityAttributeValueProvider implements ReflectedReferenceAttributeValueProvider<ReferenceKey> {
	/**
	 * The version of the catalog where the entity is being updated.
	 */
	private final long catalogVersion;
	/**
	 * The primary key of the entity that is being created.
	 */
	private final int entityPrimaryKey;
	/**
	 * The array of reference keys that are found in index of referenced entity that referer to the created entity.
	 */
	private final List<ReferenceKey> referenceKeys;
	/**
	 * The reader that is used to fetch the referenced entity reference part.
	 */
	private final DataStoreReader dataStoreReader;

	@Nonnull
	@Override
	public Stream<? extends AttributeSchemaContract> getAttributeSchemas(
		@Nonnull ReferenceSchema localReferenceSchema,
		@Nonnull ReferenceSchema referencedEntityReferenceSchema,
		@Nonnull Set<String> inheritedAttributes
	) {
		// base of the automatically upserted attributes is retrieved from local entity schema
		final Map<String, AttributeSchema> nonNullableOrDefaultValueAttributes = localReferenceSchema.getNonNullableOrDefaultValueAttributes();
		return Stream.concat(
			nonNullableOrDefaultValueAttributes.values().stream(),
			// and add all inherited attributes that are not already present in the base set
			referencedEntityReferenceSchema.getAttributes()
				.entrySet()
				.stream()
				.filter(it -> inheritedAttributes.contains(it.getKey()) && !nonNullableOrDefaultValueAttributes.containsKey(it.getKey()))
				.map(Map.Entry::getValue)
		);
	}

	@Nonnull
	@Override
	public Stream<ReferenceKey> getReferenceCarriers() {
		return this.referenceKeys.stream();
	}

	@Nonnull
	@Override
	public Stream<ReferenceKey> getReferenceCarriers(@Nonnull ReferenceKey genericReferenceKey) {
		return this.referenceKeys.stream()
			.filter(it -> it.equalsInGeneral(genericReferenceKey));
	}

	@Nonnull
	@Override
	public Optional<ReferenceKey> getReferenceCarrier(@Nonnull ReferenceKey referenceKey) {
		return Optional.ofNullable(this.referenceKeys.contains(referenceKey) ? referenceKey : null);
	}

	@Override
	public int getReferencedEntityPrimaryKey(@Nonnull ReferenceKey referenceCarrier) {
		return referenceCarrier.primaryKey();
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey(@Nonnull ReferenceSchema referenceSchema, @Nonnull ReferenceKey referenceCarrier) {
		return referenceCarrier;
	}

	@Nonnull
	@Override
	public Serializable[] getRepresentativeAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey referenceCarrier
	) {
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			// fetch the referenced entity reference part, this is quite expensive operation
			final ReferencesStoragePart referencedEntityReferencePart = this.dataStoreReader.fetch(
				this.catalogVersion, referenceCarrier.primaryKey(), ReferencesStoragePart.class
			);
			if (referencedEntityReferencePart == null) {
				return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
			} else {
				// find the appropriate reference in the referenced entity
				final ReferenceContract reference = referencedEntityReferencePart.findReferenceOrThrowException(
					new ReferenceKey(
						referenceSchema.getName(), this.entityPrimaryKey, referenceCarrier.internalPrimaryKey())
				);
				// and propagate the inherited attributes
				return referenceSchema.getRepresentativeAttributeDefinition().getRepresentativeValues(reference);
			}
		} else {
			return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
		}
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey referenceCarrier,
		@Nonnull String attributeName
	) {
		// fetch the referenced entity reference part, this is quite expensive operation
		final ReferencesStoragePart referencedEntityReferencePart = this.dataStoreReader.fetch(
			this.catalogVersion, referenceCarrier.primaryKey(), ReferencesStoragePart.class
		);
		if (referencedEntityReferencePart == null) {
			return List.of();
		} else {
			// find the appropriate reference in the referenced entity
			final ReferenceContract reference = referencedEntityReferencePart.findReferenceOrThrowException(
				new ReferenceKey(referenceSchema.getName(), this.entityPrimaryKey, referenceCarrier.internalPrimaryKey())
			);
			// and propagate the inherited attributes
			return reference.getAttributeValues(attributeName);
		}
	}
}
