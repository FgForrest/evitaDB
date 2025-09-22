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
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ComparableReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This implementation of {@link ReflectedReferenceAttributeValueProvider} is based on array of {@link Reference} that
 * are present in the initial version of the entity container. It's used to handle situations where new entity is
 * inserted to database and we need to analyze all its references for creating their counterparts based on reflected
 * reference schemas in this entity schema, or referenced entity schemas.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class ReferenceAttributeValueProvider implements ReflectedReferenceAttributeValueProvider<ReferenceContract> {
	/**
	 * The primary key of the entity that is being updated.
	 */
	private final int entityPrimaryKey;
	/**
	 * Index of the first reference that should be considered in the provided array.
	 */
	private final int start;
	/**
	 * Index of the last reference that should be considered in the provided array.
	 */
	private final int end;
	/**
	 * The array of references present in the reference storage container.
	 */
	private final ReferenceContract[] referenceContracts;
	/**
	 * The array of references present in the reference storage container.
	 */
	private Map<ComparableReferenceKey, ReferenceContract> referenceIndex = null;

	/**
	 * Constructs a ReferenceAttributeValueProvider instance with the provided primary key and reference contracts.
	 *
	 * @param entityPrimaryKey the primary key of the entity that is being updated
	 * @param referenceContracts an array of references present in the initial version of the entity
	 */
	public ReferenceAttributeValueProvider(
		int entityPrimaryKey,
		int startIndex,
		@Nonnull ReferenceContract[] referenceContracts,
		@Nonnull String referenceName
	) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.start = startIndex;
		this.referenceContracts = referenceContracts;
		int theEnd = referenceContracts.length;
		for (int i = startIndex; i < referenceContracts.length; i++) {
			if (!referenceContracts[i].getReferenceKey().referenceName().equals(referenceName)) {
				theEnd = i;
				break;
			}
		}
		this.end = theEnd;
	}

	@Nonnull
	@Override
	public Stream<? extends AttributeSchemaContract> getAttributeSchemas(
		@Nonnull ReferenceSchema localReferenceSchema,
		@Nonnull ReferenceSchema referencedEntityReferenceSchema,
		@Nonnull Set<String> inheritedAttributes
	) {
		// base of the automatically upserted attributes is retrieved from referenced entity schema
		final Map<String, AttributeSchema> nonNullableOrDefaultValueAttributes = referencedEntityReferenceSchema.getNonNullableOrDefaultValueAttributes();
		return Stream.concat(
			nonNullableOrDefaultValueAttributes.values().stream(),
			// and add all inherited attributes that are not already present in the base set
			localReferenceSchema.getAttributes()
				.entrySet()
				.stream()
				.filter(it -> inheritedAttributes.contains(it.getKey()) && !nonNullableOrDefaultValueAttributes.containsKey(it.getKey()))
				.map(Map.Entry::getValue)
		);
	}

	@Nonnull
	@Override
	public Stream<ReferenceContract> getReferenceCarriers() {
		return Arrays.stream(this.referenceContracts, this.start, this.end)
		             .filter(Droppable::exists);
	}

	@Nonnull
	@Override
	public Stream<ReferenceContract> getReferenceCarriers(@Nonnull ReferenceKey genericReferenceKey) {
		return getReferenceCarriers()
			.filter(it -> it.getReferenceKey().equalsInGeneral(genericReferenceKey));
	}

	@Override
	public int getReferencedEntityPrimaryKey(@Nonnull ReferenceContract referenceCarrier) {
		return referenceCarrier.getReferencedPrimaryKey();
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey(@Nonnull ReferenceSchema referenceSchema, @Nonnull ReferenceContract referenceCarrier) {
		return new ReferenceKey(referenceSchema.getName(), this.entityPrimaryKey, referenceCarrier.getReferenceKey().internalPrimaryKey());
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReferenceCarrier(@Nonnull ReferenceKey referenceKey) {
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			"Only non-unknown references are supported in this context!"
		);
		if (this.referenceIndex == null) {
			this.referenceIndex = CollectionUtils.createHashMap(this.end - this.start);
			for (int i = this.start; i < this.end; i++) {
				final ReferenceContract referenceContract = this.referenceContracts[i];
				if (referenceContract.exists()) {
					this.referenceIndex.put(
						new ComparableReferenceKey(referenceContract.getReferenceKey()),
						referenceContract
					);
				}
			}
		}
		return Optional.ofNullable(this.referenceIndex.get(new ComparableReferenceKey(referenceKey)));
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceContract referenceCarrier,
		@Nonnull String attributeName
	) {
		// the values of inherited attributes are simply retrieved from the reference carrier present in the initial version
		return referenceCarrier.getAttributeValues(attributeName);
	}
}
