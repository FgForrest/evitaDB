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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mutation is a holder for a set of {@link EntitySchemaMutation} that affect a single entity schema within
 * the {@link CatalogSchemaContract}.
 *
 * TOBEDONE JNO - write tests
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyEntitySchemaMutation implements CombinableCatalogSchemaMutation, EntitySchemaMutation, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = 7843689721519035513L;
	@Getter @Nonnull private final String entityType;
	@Nonnull @Getter private final EntitySchemaMutation[] schemaMutations;

	public ModifyEntitySchemaMutation(@Nonnull String entityType, @Nonnull EntitySchemaMutation... schemaMutations) {
		this.entityType = entityType;
		this.schemaMutations = schemaMutations;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation && entityType.equals(modifyEntitySchemaMutation.getEntityType())) {
			final List<EntitySchemaMutation> mutations = Arrays.asList(schemaMutations);
			final MutationImpact updated = addMutations(
				currentCatalogSchema,
				currentCatalogSchema.getEntitySchemaOrThrowException(entityType),
				mutations,
				modifyEntitySchemaMutation.getSchemaMutations()
			);
			if (updated != MutationImpact.NO_IMPACT) {
				final ModifyEntitySchemaMutation combinedMutation = new ModifyEntitySchemaMutation(
					entityType, mutations.toArray(EntitySchemaMutation[]::new)
				);
				return new MutationCombinationResult<>(null, combinedMutation);
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema) {
		// do nothing - we alter only the entity schema
		return catalogSchema;
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		EntitySchemaContract alteredSchema = entitySchema;
		for (EntitySchemaMutation schemaMutation : schemaMutations) {
			alteredSchema = schemaMutation.mutate(catalogSchema, alteredSchema);
		}
		return alteredSchema;
	}

	@Override
	public String toString() {
		return "Modify entity `" + entityType + "` schema:\n" +
			Arrays.stream(schemaMutations)
				.map(Object::toString)
				.collect(Collectors.joining(",\n"));
	}

}
