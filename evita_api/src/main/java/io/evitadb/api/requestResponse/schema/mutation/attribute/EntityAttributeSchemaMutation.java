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
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;

import javax.annotation.Nonnull;
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
	 * Replaces existing attribute schema with updated one but only when those schemas differ. Otherwise,
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
		} else if (entitySchema instanceof EntitySchema theEntitySchema) {
			return EntitySchema._internalBuild(
				theEntitySchema.version() + 1,
				theEntitySchema.getName(),
				theEntitySchema.getNameVariants(),
				theEntitySchema.getDescription(),
				theEntitySchema.getDeprecationNotice(),
				theEntitySchema.isWithGeneratedPrimaryKey(),
				theEntitySchema.isWithHierarchy(),
				theEntitySchema.getHierarchyIndexedInScopes(),
				theEntitySchema.isWithPrice(),
				theEntitySchema.getPriceIndexedInScopes(),
				theEntitySchema.getIndexedPricePlaces(),
				theEntitySchema.getLocales(),
				theEntitySchema.getCurrencies(),
				Stream.concat(
						theEntitySchema.getAttributes().values().stream().filter(it -> !updatedAttributeSchema.getName().equals(it.getName())),
						Stream.of(updatedAttributeSchema)
					)
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				theEntitySchema.getAssociatedData(),
				theEntitySchema.getReferences(),
				theEntitySchema.getEvolutionMode(),
				theEntitySchema.getSortableAttributeCompounds()
			);
		} else {
			throw new InvalidSchemaMutationException(
				"Unsupported entity schema type: " + entitySchema.getClass().getName()
			);
		}
	}

}
