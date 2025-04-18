/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is a holder for a set of {@link EntitySchemaMutation} that affect a single entity schema within
 * the {@link CatalogSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyEntitySchemaMutation implements CombinableCatalogSchemaMutation, EntitySchemaMutation, InternalSchemaBuilderHelper, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 7843689721519035513L;
	@Getter @Nonnull private final String entityType;
	@Nonnull @Getter private final LocalEntitySchemaMutation[] schemaMutations;

	public ModifyEntitySchemaMutation(@Nonnull String entityType, @Nonnull LocalEntitySchemaMutation... schemaMutations) {
		this.entityType = entityType;
		this.schemaMutations = schemaMutations;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation && entityType.equals(modifyEntitySchemaMutation.getEntityType())) {
			final List<LocalEntitySchemaMutation> mutations = new ArrayList<>(schemaMutations.length);
			mutations.addAll(Arrays.asList(schemaMutations));
			final MutationImpact updated = addMutations(
				currentCatalogSchema,
				currentCatalogSchema.getEntitySchemaOrThrowException(entityType),
				mutations,
				modifyEntitySchemaMutation.getSchemaMutations()
			);
			if (updated != MutationImpact.NO_IMPACT) {
				final ModifyEntitySchemaMutation combinedMutation = new ModifyEntitySchemaMutation(
					entityType, mutations.toArray(LocalEntitySchemaMutation[]::new)
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
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		if (entitySchemaAccessor instanceof MutationEntitySchemaAccessor mutationEntitySchemaAccessor) {
			mutationEntitySchemaAccessor
				.getEntitySchema(entityType).map(it -> mutate(catalogSchema, it))
				.ifPresentOrElse(
					mutationEntitySchemaAccessor::addUpsertedEntitySchema,
					() -> {
						throw new GenericEvitaInternalError("Entity schema not found: " + entityType);
					}
				);
		}
		// do nothing - we alter only the entity schema
		return new CatalogSchemaWithImpactOnEntitySchemas(
			catalogSchema
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		EntitySchemaContract alteredSchema = entitySchema;
		for (EntitySchemaMutation schemaMutation : schemaMutations) {
			alteredSchema = schemaMutation.mutate(catalogSchema, alteredSchema);
		}
		return alteredSchema;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();
		context.advance();
		context.setEntityType(entityType);

		final Stream<ChangeCatalogCapture> entitySchemaCapture;
		if (predicate.test(this)) {
			entitySchemaCapture = Stream.of(
				ChangeCatalogCapture.schemaCapture(
					context,
					operation(),
					content == ChangeCaptureContent.BODY ? this : null
				)
			);
		} else {
			entitySchemaCapture = Stream.empty();
		}

		if (context.getDirection() == StreamDirection.FORWARD) {
			return Stream.concat(
				entitySchemaCapture,
				Arrays.stream(this.schemaMutations)
					.filter(predicate)
					.flatMap(m -> m.toChangeCatalogCapture(predicate, content))
			);
		} else {
			final AtomicInteger index = new AtomicInteger(this.schemaMutations.length);
			return Stream.concat(
				Stream.generate(() -> null)
					.takeWhile(x -> index.get() > 0)
					.map(x -> this.schemaMutations[index.decrementAndGet()])
					.filter(predicate)
					.flatMap(x -> x.toChangeCatalogCapture(predicate, content)),
				entitySchemaCapture
			);

		}
	}

	@Override
	public String toString() {
		return "Modify entity `" + entityType + "` schema:\n" +
			Arrays.stream(schemaMutations)
				.map(Object::toString)
				.collect(Collectors.joining(",\n"));
	}

}
