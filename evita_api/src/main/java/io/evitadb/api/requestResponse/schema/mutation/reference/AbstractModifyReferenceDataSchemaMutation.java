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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for reference-schema mutations that **modify** (rather than create or
 * remove) an existing reference schema within an entity schema.
 *
 * Responsibilities:
 * - provides the `replaceReferenceSchema()` utility that swaps a single reference schema
 *   inside an entity schema while preserving all other references
 * - defaults `operation()` to `Operation.UPSERT` so subclasses only override when needed
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
abstract class AbstractModifyReferenceDataSchemaMutation extends AbstractReferenceDataSchemaMutation
	implements ReferenceSchemaMutation {
	@Serial private static final long serialVersionUID = 3160594356938000407L;

	/**
	 * Creates a new mutation targeting the reference schema identified by its name.
	 *
	 * @param name the name of the reference schema to modify
	 */
	AbstractModifyReferenceDataSchemaMutation(@Nonnull String name) {
		super(name);
	}

	/**
	 * All modify-type reference mutations default to `UPSERT` because they update an
	 * existing reference schema rather than creating or removing one.
	 */
	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	/**
	 * Looks up the existing reference schema by `this.name`, applies the mutation via
	 * `mutate(entitySchema, referenceSchema)`, and replaces the reference in the entity schema.
	 *
	 * If the reference does not exist, throws {@link InvalidSchemaMutationException}.
	 *
	 * Subclasses may override this method if they need different behavior (e.g. passing
	 * `ConsistencyChecks.SKIP` to the reference-level mutation).
	 */
	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(this.name);
		if (existingReferenceSchema.isEmpty()) {
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` is not defined in entity `" +
					entitySchema.getName() + "` schema!"
			);
		} else {
			final ReferenceSchemaContract theSchema = existingReferenceSchema.get();
			final ReferenceSchemaContract updatedSchema = mutate(entitySchema, theSchema);
			Assert.isPremiseValid(updatedSchema != null, "Updated reference schema is not expected to be null!");
			return replaceReferenceSchema(entitySchema, theSchema, updatedSchema);
		}
	}

	/**
	 * Replaces existing reference schema with updated one but only when those schemas differ. Otherwise,
	 * the non-changed, original entity schema is returned.
	 */
	@Nonnull
	protected EntitySchemaContract replaceReferenceSchema(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract existingReferenceSchema,
		@Nonnull ReferenceSchemaContract updatedReferenceSchema
	) {
		if (existingReferenceSchema.equals(updatedReferenceSchema)) {
			// we don't need to update entity schema - the reference schema already contains the requested change
			return entitySchema;
		} else {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.getHierarchyIndexedInScopes(),
				entitySchema.isWithPrice(),
				entitySchema.getPriceIndexedInScopes(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				Stream.concat(
						entitySchema
							.getReferences()
							.values()
							.stream()
							.filter(it -> !existingReferenceSchema.getName().equals(it.getName())),
						Stream.of(updatedReferenceSchema)
					)
					.collect(
						Collectors.toMap(
							ReferenceSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

}
