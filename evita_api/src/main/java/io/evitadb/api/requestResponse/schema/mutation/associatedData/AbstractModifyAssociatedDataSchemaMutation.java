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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.AssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
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
 * Abstract class, that contains shared logic for associated data schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@AllArgsConstructor
abstract class AbstractModifyAssociatedDataSchemaMutation implements LocalEntitySchemaMutation, AssociatedDataSchemaMutation {
	@Serial private static final long serialVersionUID = -4384492921045013953L;
	@Getter @Nonnull protected final String name;

	/**
	 * Replaces existing associated data schema with updated one but only when those schemas differ. Otherwise,
	 * the non-changed, original entity schema is returned.
	 */
	@Nonnull
	protected EntitySchemaContract replaceAssociatedDataIfDifferent(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedDataSchemaContract existingAssociatedDataSchema,
		@Nonnull AssociatedDataSchemaContract updatedAssociatedDataSchema
	) {
		if (existingAssociatedDataSchema.equals(updatedAssociatedDataSchema)) {
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
				entitySchema.isWithPrice(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				Stream.concat(
						entitySchema.getAssociatedData().values().stream().filter(it -> !updatedAssociatedDataSchema.getName().equals(it.getName())),
						Stream.of(updatedAssociatedDataSchema)
					)
					.collect(
						Collectors.toMap(
							AssociatedDataSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

}
