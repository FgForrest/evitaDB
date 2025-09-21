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


import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This implementation of {@link ReflectedReferenceAttributeValueProvider} is based on array of mutations that are
 * processed during the entity upsert. It's used to handle situations where new (reflected) reference is inserted
 * to existing entity and we need to insert its counterpart to the referenced entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings("rawtypes")
class MutationAttributeValueProvider implements ReflectedReferenceAttributeValueProvider<ReferenceMutation> {
	/**
	 * The primary key of the entity that is being upserted.
	 */
	private final int entityPrimaryKey;
	/**
	 * The array of mutations that are processed during the entity upsert.
	 */
	private final CompositeObjectArray<ReferenceMutation> matchingMutations;
	/**
	 * The index of reference attributes that are processed during the entity upsert.
	 */
	private final Map<ReferenceKey, Map<AttributeKey, AttributeValue>> referenceAttributesIndex;

	/**
	 * Constructor for initializing a MutationAttributeValueProvider.
	 *
	 * @param entityPrimaryKey The primary key of the entity.
	 * @param entitySchema The schema of the entity.
	 * @param firstMutation The first mutation to process.
	 * @param startIndex The starting index index in the mutation list.
	 * @param processedMutations Optimization hash set allowing to skip already processed mutations.
	 * @param inputMutations The list of input mutations.
	 */
	public MutationAttributeValueProvider(
		int entityPrimaryKey,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceMutation<?> firstMutation,
		int startIndex,
		@Nonnull IntSet processedMutations,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations
	) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.referenceAttributesIndex = CollectionUtils.createHashMap(inputMutations.size());
		// let's collect all primary keys of the insert reference with the same name into a bitmap
		final String referenceName = firstMutation.getReferenceKey().referenceName();
		this.matchingMutations = new CompositeObjectArray<>(ReferenceMutation.class);
		this.matchingMutations.add(firstMutation);
		// we assume that references are sorted by ReferenceKey,
		// and that the mutations that relate to the same reference are next to each other
		for (int j = startIndex + 1; j < inputMutations.size(); j++) {
			final LocalMutation<?, ?> nextMutation = inputMutations.get(j);
			if (nextMutation instanceof ReferenceMutation<?> nextIrm) {
				if (referenceName.equals(nextIrm.getReferenceKey().referenceName())) {
					// we collect all mutations that relate to the same reference,
					// and are of the same type - i.e. either insert or removal
					if (firstMutation.getClass().equals(nextIrm.getClass())) {
						this.matchingMutations.add(nextIrm);
					} else if (
						// and we also build an index of upserted reference attributes for the insert mutations
						nextIrm instanceof ReferenceAttributeMutation ram &&
							ram.getAttributeMutation() instanceof UpsertAttributeMutation uam) {
						this.referenceAttributesIndex.computeIfAbsent(
								ram.getReferenceKey(),
								referenceKey -> CollectionUtils.createHashMap(16)
							)
							.put(
								ram.getAttributeKey(),
								uam.mutateLocal(entitySchema, null)
							);
					}
					// this allows enveloping process to quickly skip already processed mutations
					processedMutations.add(j);
				}
			}
		}
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
	public Stream<ReferenceMutation> getReferenceCarriers() {
		return StreamSupport.stream(this.matchingMutations.spliterator(), false);
	}

	@Nonnull
	@Override
	public Stream<ReferenceMutation> getReferenceCarriers(@Nonnull ReferenceKey genericReferenceKey) {
		return StreamSupport.stream(this.matchingMutations.spliterator(), false)
			.filter(it -> it.getReferenceKey().equalsInGeneral(genericReferenceKey));
	}

	@Nonnull
	@Override
	public Optional<ReferenceMutation> getReferenceCarrier(@Nonnull ReferenceKey referenceKey) {
		// we can use any mutation here, since this provider will always work with the reference key inside it
		return Optional.ofNullable(this.referenceAttributesIndex.containsKey(referenceKey) ? new InsertReferenceMutation(referenceKey) : null);
	}

	@Override
	public int getReferencedEntityPrimaryKey(@Nonnull ReferenceMutation referenceCarrier) {
		return referenceCarrier.getReferenceKey().primaryKey();
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey(@Nonnull ReferenceSchema referenceSchema, @Nonnull ReferenceMutation referenceCarrier) {
		return new ReferenceKey(referenceSchema.getName(), this.entityPrimaryKey, referenceCarrier.getReferenceKey().internalPrimaryKey());
	}

	@Nonnull
	@Override
	public Serializable[] getRepresentativeAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceMutation referenceCarrier
	) {
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			final Map<AttributeKey, AttributeValue> attributeValues = this.referenceAttributesIndex.get(referenceCarrier.getReferenceKey());
			final List<String> representativeAttributeNames = rad.getAttributeNames();
			final Serializable[] values = new Serializable[representativeAttributeNames.size()];
			for (int i = 0; i < representativeAttributeNames.size(); i++) {
				final AttributeValue attributeValue = attributeValues.get(new AttributeKey(representativeAttributeNames.get(i)));
				values[i] = attributeValue == null ? null : attributeValue.value();
			}
			return values;
		} else {
			return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
		}
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceMutation referenceCarrier,
		@Nonnull String attributeName
	) {
		// we retrieve the attribute values from the index built in constructor from the input list of mutations
		final Map<AttributeKey, AttributeValue> attributeValues = this.referenceAttributesIndex.get(referenceCarrier.getReferenceKey());
		return attributeValues == null ?
			Collections.emptyList() :
			attributeValues.values().stream()
				.filter(it -> it.key().attributeName().equals(attributeName))
				.collect(Collectors.toList());
	}

}
