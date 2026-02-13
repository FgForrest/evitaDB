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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface with default method implementation, that contains shared logic for attribute schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EntityAttributeSchemaMutation extends AttributeSchemaMutation, LocalEntitySchemaMutation {

	/**
	 * Applies this mutation to the entity schema by looking up the existing attribute, applying the attribute-level
	 * mutation via {@link AttributeSchemaMutation#mutate(CatalogSchemaContract, AttributeSchemaContract, Class)},
	 * and replacing the attribute in the schema if it changed.
	 *
	 * Subclasses with custom mutation logic (e.g., type conversions wrapped in try/catch) should override this method.
	 */
	@Nonnull
	default EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(getName())
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + getName() + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));

		final EntityAttributeSchemaContract updatedAttributeSchema = Objects.requireNonNull(
			mutate(catalogSchema, existingAttributeSchema, EntityAttributeSchemaContract.class)
		);
		return replaceAttributeIfDifferent(
			entitySchema, existingAttributeSchema, updatedAttributeSchema
		);
	}

	/**
	 * Replaces existing attribute schema with an updated one but only when those schemas differ. Otherwise,
	 * the non-changed, original entity schema is returned.
	 */
	@Nonnull
	default EntitySchemaContract replaceAttributeIfDifferent(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AttributeSchemaContract existingAttributeSchema,
		@Nonnull EntityAttributeSchemaContract updatedAttributeSchema
	) {
		if (existingAttributeSchema.equals(updatedAttributeSchema)) {
			// we don't need to update entity schema - the associated data already contains the requested change
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
				Stream.concat(
						entitySchema.getAttributes()
							.values()
							.stream()
							.filter(it -> !updatedAttributeSchema.getName().equals(it.getName())),
						Stream.of(updatedAttributeSchema)
					)
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

}
