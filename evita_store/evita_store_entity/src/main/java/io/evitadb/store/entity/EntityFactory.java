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

package io.evitadb.store.entity;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityAttributes;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.data.structure.References;
import io.evitadb.api.requestResponse.data.structure.References.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.map.LazyHashMapDelegate;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This helper class allows to instantiate {@link Entity} from the set of {@link EntityStoragePart}.
 * Entity hides internal contents by using friendly accessors in order to discourage client code from using the internals.
 * On the other hand this fact is the reason why this class must exist and reside in the data.structure package, because
 * these internals are crucial for effectively loading the entity from the storage form.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityFactory {

	/**
	 * Creates {@link Entity} contents from the {@link EntityStoragePart}.
	 * Most of the parts may be missing in this time and may be gradually added by method:
	 * {@link #createEntityFrom(EntitySchemaContract, Entity, EntityBodyStoragePart, List, List, ReferencesStoragePart, PricesStoragePart)}
	 *
	 * This method is used for initial loading of the entity.
	 */
	@Nonnull
	public static Entity createEntityFrom(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nonnull List<AttributesStoragePart> attributesStorageContainers,
		@Nonnull List<AssociatedDataStoragePart> associatedDataStorageContainers,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nullable PricesStoragePart priceStorageContainer,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		final Map<AttributeKey, AttributeValue> attributeValues = attributesStorageContainers
			.stream()
			.flatMap(it -> Arrays.stream(it.getAttributes()))
			.collect(
				Collectors.toMap(
					AttributeValue::key,
					Function.identity(),
					(o, n) -> {
						throw new EvitaInvalidUsageException("Duplicate attribute key " + o.key());
					},
					LinkedHashMap::new
				)
			);
		return Entity._internalBuild(
			entityStorageContainer.getVersion(),
			entityStorageContainer.getPrimaryKey(), entitySchema,
			entityStorageContainer.getParent(),
			// when references storage container is present use it, otherwise init references by empty collection
			new References(
				entitySchema,
				ofNullable(referencesStorageContainer)
					.map(ReferencesStoragePart::getReferencesAsCollection)
					.orElse(Collections.emptyList()),
				entitySchema.getReferences().keySet(),
				// transformation function used to wrap references into the data chunk
				referenceChunkTransformer
			),
			// always initialize Attributes container
			new EntityAttributes(
				entitySchema,
				// fill all contents of the attributes loaded from storage (may be empty)
				attributeValues,
				new LazyHashMapDelegate<>(4)
			),
			// always initialize Associated data container
			new AssociatedData(
				entitySchema,
				// fill all contents of the associated data loaded from storage (may be empty)
				associatedDataStorageContainers
					.stream()
					.map(AssociatedDataStoragePart::getValue)
			),
			// when prices container is present - init prices and price inner record handling - otherwise use default config
			ofNullable(priceStorageContainer)
				.map(it -> it.getAsPrices(entitySchema))
				.orElseGet(() -> new Prices(entitySchema, PriceInnerRecordHandling.UNKNOWN)),
			// pass all locales known in the entity container
			entityStorageContainer.getLocales(),
			// pass entity scope
			entityStorageContainer.getScope(),
			// loaded entity is never dropped - otherwise it could not have been read
			false
		);
	}

	/**
	 * Creates {@link Entity} contents on basis of partially loaded entity passed in argument and additional set
	 * of the {@link EntityStoragePart}.
	 *
	 * This method cannot be used for initial loading of the entity but is targeted for enriching previously loaded one.
	 */
	public static Entity createEntityFrom(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Entity entity,
		@Nullable EntityBodyStoragePart entityStoragePart,
		@Nonnull List<AttributesStoragePart> attributesStorageContainers,
		@Nonnull List<AssociatedDataStoragePart> associatedDataStorageContainers,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nullable PricesStoragePart priceStorageContainer
	) {
		Assert.isPremiseValid(
			entityStoragePart == null || !entityStoragePart.isMarkedForRemoval(),
			"Entity cannot be enriched because is marked for removal!"
		);

		// first use all attributes from freshly loaded containers
		final LinkedHashMap<AttributeKey, AttributeValue> attributeValues = CollectionUtils.createLinkedHashMap(
			entity.getSchema().getAttributes().size()
		);
		for (AttributesStoragePart attributeCnt : attributesStorageContainers) {
			for (AttributeValue attributeValue : attributeCnt.getAttributes()) {
				attributeValues.put(attributeValue.key(), attributeValue);
			}
		}
		// then add all previously loaded attributes - but only when they were not freshly loaded
		for (AttributeValue attributeValue : entity.getAttributeValues()) {
			attributeValues.putIfAbsent(attributeValue.key(), attributeValue);
		}

		// first use all associated from freshly loaded containers
		final LinkedHashMap<AssociatedDataKey, AssociatedDataValue> associatedDataValues = CollectionUtils.createLinkedHashMap(
			entity.getSchema().getAssociatedData().size()
		);
		for (AssociatedDataStoragePart associatedDataCnt : associatedDataStorageContainers) {
			final AssociatedDataValue associatedDataValue = associatedDataCnt.getValue();
			associatedDataValues.put(associatedDataValue.key(), associatedDataValue);
		}
		// then add all previously loaded associated data - but only when they were not freshly loaded
		for (AssociatedDataValue associatedDataValue : entity.getAssociatedDataValues()) {
			associatedDataValues.putIfAbsent(associatedDataValue.key(), associatedDataValue);
		}

		return Entity._internalBuild(
			entity,
			ofNullable(entityStoragePart)
				.map(EntityBodyStoragePart::getVersion)
				.orElse(entity.version()),
			Objects.requireNonNull(entity.getPrimaryKey()),
			entitySchema,
			ofNullable(entityStoragePart)
				.map(EntityBodyStoragePart::getParent)
				.orElse(null),
			// when references storage container is present use it
			// otherwise use original references from previous entity contents
			new References(
				entitySchema,
				ofNullable(referencesStorageContainer)
					.map(ReferencesStoragePart::getReferencesAsCollection)
					.orElse(null),
				entity.getSchema().getReferences().keySet(),
				entity.getReferenceChunkTransformer()
			),
			// when no additional attribute containers were loaded
			attributesStorageContainers.isEmpty() ?
				// use original attributes from the entity contents
				null :
				// otherwise combine
				new EntityAttributes(
					entitySchema,
					attributeValues,
					new LazyHashMapDelegate<>(4)
				),
			// when no additional associated data containers were loaded
			associatedDataStorageContainers.isEmpty() ?
				// use original associated data from the entity contents
				null :
				// otherwise combine
				new AssociatedData(
					entitySchema,
					associatedDataValues
				),
			// when prices container is present - init prices and price inner record handling
			// otherwise use original prices from previous entity contents
			ofNullable(priceStorageContainer)
				.map(it -> it.getAsPrices(entitySchema))
				.orElse(null),
			// pass all locales known in the entity container
			ofNullable(entityStoragePart)
				.map(EntityBodyStoragePart::getLocales)
				.orElse(null),
			// pass scope known in the entity container
			ofNullable(entityStoragePart)
				.map(EntityBodyStoragePart::getScope)
				.orElseGet(entity::getScope),
			// loaded entity is never dropped - otherwise it could not have been read
			false
		);
	}

	private EntityFactory() {
	}

}
