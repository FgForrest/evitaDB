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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This interface marks all implementations that alter the {@link EntitySchemaContract#getSortableAttributeCompounds()}.
 * The implementations can either modify the entire {@link EntitySchemaContract} or partially only a single
 * {@link SortableAttributeCompoundSchemaContract} of it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface SortableAttributeCompoundSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the sortable attribute compound the mutation is targeting.
	 */
	@Nonnull
	String getName();

	/**
	 * Method applies the mutation operation on the sortable attribute compound schema in the input and returns modified
	 * version as its return value. The create operation works with NULL input value and produces non-NULL result,
	 * the remove operation produces the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * @param entitySchema owner entity schema that could be used in validations and error messages
	 * @param referenceSchema owner reference schema that could be used in validations and error messages
	 * @param sortableAttributeCompoundSchema current version of the schema as an input to mutate
	 */
	@Nullable
	SortableAttributeCompoundSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	);

	/**
	 * Replaces existing sortable attribute compound schema with updated one but only when those schemas differ. Otherwise,
	 * the non-changed, original entity schema is returned.
	 */
	@Nonnull
	default EntitySchemaContract replaceSortableAttributeCompoundIfDifferent(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull SortableAttributeCompoundSchemaContract existingSchema,
		@Nonnull SortableAttributeCompoundSchemaContract updatedSchema
	) {
		if (existingSchema.equals(updatedSchema)) {
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
				theEntitySchema.getAttributes(),
				theEntitySchema.getAssociatedData(),
				theEntitySchema.getReferences(),
				theEntitySchema.getEvolutionMode(),
				Stream.concat(
						theEntitySchema.getSortableAttributeCompounds().values().stream().filter(it -> !updatedSchema.getName().equals(it.getName())),
						Stream.of(updatedSchema)
					)
					.collect(
						Collectors.toMap(
							SortableAttributeCompoundSchemaContract::getName,
							Function.identity()
						)
					)
			);
		} else {
			throw new InvalidSchemaMutationException(
				"Unsupported entity schema type: " + entitySchema.getClass().getName()
			);
		}
	}

}
