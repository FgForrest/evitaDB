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
	 * Verifies that this mutation may be applied to the entity of the given type, before its attribute is changed.
	 * Most attribute mutations depend on the attribute alone; a mutation that also depends on the rest of the
	 * catalog overrides this method rather than {@link #mutate(CatalogSchemaContract, EntitySchemaContract)}.
	 *
	 * The check receives the entity type, not the entity schema: the entity schema builder applies a run of attribute
	 * mutations to the attributes alone and builds the entity schema once for the whole run, so no entity schema
	 * holding the changes of the mutations before this one exists while it runs.
	 *
	 * @param catalogSchema the catalog schema the entity belongs to
	 * @param entityType    the type of the entity whose attribute is changed
	 * @throws InvalidSchemaMutationException when the mutation must not be applied
	 */
	default void verifyApplicableToEntity(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull String entityType) {
		// most attribute mutations depend on the attribute alone
	}

	/**
	 * Applies this mutation to the entity schema by looking up the existing attribute, applying the attribute-level
	 * mutation via {@link AttributeSchemaMutation#mutate(CatalogSchemaContract, AttributeSchemaContract, Class)},
	 * and replacing the attribute in the schema if it changed.
	 *
	 * Subclasses with custom mutation logic (e.g., type conversions wrapped in try/catch) should override this method.
	 * A mutation that does not override it changes nothing but its attribute, which is what lets the entity schema
	 * builder apply a run of such mutations without building the entity schema after each of them - an override
	 * opts the mutation out of that, a check belongs to {@link #verifyApplicableToEntity(CatalogSchemaContract, String)}.
	 */
	@Nonnull
	default EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		verifyApplicableToEntity(catalogSchema, entitySchema.getName());
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
	 *
	 * The existing attribute is taken out by its own name, so a rename moves the attribute to the new name rather
	 * than adding it next to the original. A rename is refused when another attribute of the entity holds the new
	 * name, and when a sortable attribute compound of the entity is made of the attribute: the compound would name
	 * an attribute that no longer exists, and every later upsert into the collection would fail on it.
	 *
	 * @throws InvalidSchemaMutationException when the rename is refused for one of the reasons above
	 */
	@Nonnull
	default EntitySchemaContract replaceAttributeIfDifferent(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AttributeSchemaContract existingAttributeSchema,
		@Nonnull EntityAttributeSchemaContract updatedAttributeSchema
	) {
		if (existingAttributeSchema.equals(updatedAttributeSchema)) {
			// we don't need to update entity schema - the attribute already contains the requested change
			return entitySchema;
		} else {
			final String existingName = existingAttributeSchema.getName();
			if (!existingName.equals(updatedAttributeSchema.getName())) {
				AttributeRenameVerifier.verifyRenameInEntity(
					entitySchema, existingName, updatedAttributeSchema.getName()
				);
			}
			return EntitySchema._internalBuildWithAttributes(
				entitySchema,
				entitySchema.version() + 1,
				Stream.concat(
						entitySchema.getAttributes()
							.values()
							.stream()
							.filter(it -> !existingName.equals(it.getName())),
						Stream.of(updatedAttributeSchema)
					)
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					)
			);
		}
	}

}
