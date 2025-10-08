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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

import javax.annotation.Nonnull;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface with default method implementation, that contains shared logic for attribute schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface GlobalAttributeSchemaMutation extends AttributeSchemaMutation, CatalogSchemaMutation {

	/**
	 * Replaces existing attribute schema with updated one but only when those schemas differ. Otherwise,
	 * the non-changed, original catalog schema is returned.
	 */
	@Nonnull
	default CatalogSchemaWithImpactOnEntitySchemas replaceAttributeIfDifferent(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull GlobalAttributeSchemaContract existingAttributeSchema,
		@Nonnull GlobalAttributeSchemaContract updatedAttributeSchema,
		@Nonnull EntitySchemaProvider entitySchemaAccessor,
		@Nonnull EntityAttributeSchemaMutation attributeSchemaMutation
	) {
		if (existingAttributeSchema.equals(updatedAttributeSchema)) {
			// we don't need to update entity schema - the associated data already contains the requested change
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		} else {
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.version() + 1,
					catalogSchema.getName(),
					catalogSchema.getNameVariants(),
					catalogSchema.getDescription(),
					catalogSchema.getCatalogEvolutionMode(),
					Stream.concat(
							catalogSchema.getAttributes().values().stream().filter(it -> !updatedAttributeSchema.getName().equals(it.getName())),
							Stream.of(updatedAttributeSchema)
						)
						.collect(
							Collectors.toMap(
								AttributeSchemaContract::getName,
								Function.identity()
							)
						),
					entitySchemaAccessor
				),
				entitySchemaAccessor
					.getEntitySchemas()
					.stream()
					.filter(it -> it.getAttributes().containsKey(existingAttributeSchema.getName()))
					.map(it -> new ModifyEntitySchemaMutation(
						it.getName(),
						attributeSchemaMutation
					))
					.toArray(ModifyEntitySchemaMutation[]::new)
			);
		}
	}

}
