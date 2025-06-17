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

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Mutations implementing this interface are top-level mutations that group all {@link LocalMutation} that target same
 * entity. This mutation atomically updates single {@link Entity} in evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public non-sealed interface EntityMutation extends CatalogBoundMutation {

	/**
	 * Default implementation of the entity builder retrieves all local mutations, filters those that implement
	 * {@link SchemaEvolvingLocalMutation} and calls back their implementation of
	 * {@link SchemaEvolvingLocalMutation#verifyOrEvolveSchema(CatalogSchemaContract, EntitySchemaBuilder)}.
	 *
	 * Because there may be multiple mutations that target the same schema settings, concept of skipToken is introduced
	 * that allows to perform only first verification / evolution of the entity schema and skip others very quickly.
	 *
	 * @param catalogSchema to check entity against
	 */
	@Nonnull
	static Optional<LocalEntitySchemaMutation[]> verifyOrEvolveSchema(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		@Nonnull Collection<? extends LocalMutation<?, ?>> localMutations
	) throws InvalidMutationException {
		final Set<Serializable> skipList = new HashSet<>(16);
		final EntitySchemaBuilder entitySchemaBuilder = entitySchema.openForWrite();
		for (LocalMutation<?, ?> localMutation : localMutations) {
			if (localMutation instanceof final SchemaEvolvingLocalMutation<?, ?> evolvingMutation) {
				final Serializable skipToken = evolvingMutation.getSkipToken(catalogSchema, entitySchemaBuilder);
				// grouping token allows us to skip duplicate schema verifications / evolutions
				// there may be several mutations that targets same entity "field"
				// so there is no sense to verify them repeatedly
				if (!skipList.contains(skipToken)) {
					evolvingMutation.verifyOrEvolveSchema(catalogSchema, entitySchemaBuilder);
					skipList.add(skipToken);
				}
			}
		}
		return entitySchemaBuilder.toMutation()
			.map(ModifyEntitySchemaMutation::getSchemaMutations);
	}

	/**
	 * Returns {@link Entity#getType()} of the entity this mutation targets.
	 */
	@Nonnull
	String getEntityType();

	/**
	 * Returns {@link Entity#getPrimaryKey()} of the entity this mutation targets.
	 *
	 * @return may return NULL only in case mutation can setup brand new entity
	 */
	@Nullable
	Integer getEntityPrimaryKey();

	/**
	 * Returns enum value represents expectations whether the entity should already exist.
	 */
	@Nonnull
	EntityExistence expects();

	/**
	 * Entity mutation is expected to apply changes in the schema that are required by {@link SchemaEvolvingLocalMutation}
	 * present in this entity mutation.
	 *
	 * @param catalogSchema to check entity against
	 * @param entitySchema to check entity against
	 * @param entityCollectionEmpty TRUE if entire collection is empty
	 */
	@Nonnull
	Optional<LocalEntitySchemaMutation[]> verifyOrEvolveSchema(
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		boolean entityCollectionEmpty
	);

	/**
	 * Returns entity with applied mutation.
	 */
	@Nonnull
	Entity mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable Entity entity);

	/**
	 * Returns collection of all local mutations that modify this entity.
	 */
	@Nonnull
	List<? extends LocalMutation<?, ?>> getLocalMutations();

	/**
	 * Contains set of all possible expected states for the entity.
	 */
	enum EntityExistence {
		MUST_NOT_EXIST,
		MAY_EXIST,
		MUST_EXIST
	}
}
