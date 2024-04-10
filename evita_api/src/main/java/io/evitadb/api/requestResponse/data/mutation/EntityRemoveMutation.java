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

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * EntityRemoveMutation represents a terminal mutation when existing entity is removed in the evitaDB. The entity is
 * and all its internal data are marked as TRUE for {@link Droppable#dropped()}, stored to the storage file and
 * removed from the mem-table.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@EqualsAndHashCode
public class EntityRemoveMutation implements EntityMutation {
	@Serial private static final long serialVersionUID = 5707497055545283888L;
	/**
	 * The existing entity {@link Entity#getPrimaryKey()} allowing identification of the entity to remove.
	 */
	private final int entityPrimaryKey;
	/**
	 * The {@link EntitySchemaContract#getName()} of the entity type.
	 */
	@Nonnull
	private final String entityType;

	public EntityRemoveMutation(
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		this.entityType = entityType;
		this.entityPrimaryKey = entityPrimaryKey;
	}

	@Nonnull
	@Override
	public String getEntityType() {
		return entityType;
	}

	@Nullable
	@Override
	public Integer getEntityPrimaryKey() {
		return entityPrimaryKey;
	}

	@Nonnull
	@Override
	public EntityExistence expects() {
		return EntityExistence.MAY_EXIST;
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaMutation[]> verifyOrEvolveSchema(
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		boolean entityCollectionEmpty
	) {
		// dropping entity doesn't affect the schema
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Entity mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable Entity entity) {
		Assert.notNull(entity, "Entity must not be null in order to be removed!");
		if (entity.dropped()) {
			return entity;
		}

		return Entity.mutateEntity(entitySchema, entity, computeLocalMutationsForEntityRemoval(entity));
	}

	@Nonnull
	@Override
	public Collection<? extends LocalMutation<?, ?>> getLocalMutations() {
		return Collections.emptyList();
	}

	/**
	 * Method will collect all necessary local mutations to completely remove passed entity.
	 */
	@Nonnull
	public List<? extends LocalMutation<?, ?>> computeLocalMutationsForEntityRemoval(@Nonnull Entity entity) {
		return Stream.of(
				(entity.parentAvailable() ? entity.getParent() : OptionalInt.empty())
					.stream()
					.mapToObj(it -> new RemoveParentMutation()),
				entity.getReferences()
					.stream()
					.filter(Droppable::exists)
					.flatMap(it -> Stream.concat(
							it.getAttributeValues()
								.stream()
								.filter(Droppable::exists)
								.map(x ->
									new ReferenceAttributeMutation(
										it.getReferenceKey(),
										new RemoveAttributeMutation(x.key())
									)
								),
							Stream.of(new RemoveReferenceMutation(it.getReferenceKey()))
						)
					),
				entity.getAttributeValues()
					.stream()
					.filter(Droppable::exists)
					.map(AttributeValue::key)
					.map(RemoveAttributeMutation::new),
				entity.getAssociatedDataValues()
					.stream()
					.filter(Droppable::exists)
					.map(AssociatedDataValue::key)
					.map(RemoveAssociatedDataMutation::new),
				Stream.of(
					new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE)
				),
				(entity.pricesAvailable() ? entity.getPrices() : Collections.<PriceContract>emptyList())
					.stream()
					.filter(Droppable::exists)
					.map(it -> new RemovePriceMutation(it.priceKey()))
			)
			.flatMap(it -> it)
			.filter(Objects::nonNull)
			.toList();
	}

}
