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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract class, that contains shared logic for reference schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@AllArgsConstructor
abstract class AbstractModifyReferenceDataSchemaMutation implements ReferenceSchemaMutation {
	@Serial private static final long serialVersionUID = 3160594356938000407L;
	@Getter @Nonnull protected final String name;

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
			// we don't need to update entity schema - the associated data already contains the requested change
			return entitySchema;
		} else {
			if (entitySchema instanceof EntitySchema theEntitySchema) {
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
					Stream.concat(
							theEntitySchema.getReferences().values().stream().filter(it -> !existingReferenceSchema.getName().equals(it.getName())),
							Stream.of(updatedReferenceSchema)
						)
						.collect(
							Collectors.toMap(
								ReferenceSchemaContract::getName,
								Function.identity()
							)
						),
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

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

}
