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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface with default method implementation, that contains shared logic for attribute schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface ReferenceSortableAttributeCompoundSchemaMutation extends SortableAttributeCompoundSchemaMutation, ReferenceSchemaMutator {

	/**
	 * Retrieves the sortable attribute compound schema of a given attribute name from a reference schema.
	 *
	 * @param referenceSchema       The reference schema to retrieve the sortable attribute compound schema from.
	 * @param attributeCompoundName The name of the sortable attribute compound.
	 * @return The sortable attribute compound schema of the specified sortable attribute compound name, or null if not found.
	 */
	@Nonnull
	default Optional<SortableAttributeCompoundSchemaContract> getReferenceSortableAttributeCompoundSchema(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeCompoundName
	) {
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			final Optional<SortableAttributeCompoundSchemaContract> result = reflectedReferenceSchema.getDeclaredSortableAttributeCompound(attributeCompoundName);
			if (result.isEmpty() && reflectedReferenceSchema.isReflectedReferenceAvailable()) {
				Assert.isTrue(
					reflectedReferenceSchema.getSortableAttributeCompound(attributeCompoundName).isEmpty(),
					() -> new InvalidSchemaMutationException(
						"Sortable attribute compound inherited from original reference `" + reflectedReferenceSchema.getReflectedReferenceName() +
							"` in entity type `" + reflectedReferenceSchema.getReferencedEntityType() + "` " +
							"cannot be modified directly via. reflected reference schema!"
					)
				);
			}
			return result;
		} else {
			return referenceSchema.getSortableAttributeCompound(attributeCompoundName);
		}
	}

	/**
	 * Retrieves the sortable attribute compound schema of a given attribute name from a reference schema.
	 *
	 * @param entitySchema    The entity schema where the reference schema is present.
	 * @param referenceSchema       The reference schema to retrieve the sortable attribute compound schema from.
	 * @param attributeCompoundName The name of the sortable attribute compound.
	 * @return The sortable attribute compound schema of the specified sortable attribute compound name, or null if not found.
	 */
	@Nonnull
	default SortableAttributeCompoundSchemaContract getReferenceSortableAttributeCompoundSchemaOrThrow(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeCompoundName
	) {
		return getReferenceSortableAttributeCompoundSchema(referenceSchema, attributeCompoundName)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The sortable attribute compound `" + attributeCompoundName + "` is not defined in entity `" + entitySchema.getName() +
					"` schema for reference with name `" + referenceSchema.getName() + "`!"
			));
	}

	/**
	 * Replaces existing sortable attribute compound schema with updated one but only when those schemas differ.
	 * Otherwise, the non-changed, original reference schema is returned.
	 */
	@Nonnull
	default ReferenceSchemaContract replaceSortableAttributeCompoundIfDifferent(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract existingSchema,
		@Nonnull SortableAttributeCompoundSchemaContract updatedSchema
	) {
		if (existingSchema.equals(updatedSchema)) {
			// we don't need to update entity schema - the associated data already contains the requested change
			return referenceSchema;
		} else {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				return reflectedReferenceSchema
					.withDeclaredSortableAttributeCompounds(
						Stream.concat(
								reflectedReferenceSchema.getDeclaredSortableAttributeCompounds().values().stream().filter(it -> !updatedSchema.getName().equals(it.getName())),
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
				return ReferenceSchema._internalBuild(
					referenceSchema.getName(),
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					referenceSchema.getReferenceIndexTypeInScopes(),
					referenceSchema.getFacetedInScopes(),
					referenceSchema.getAttributes(),
					Stream.concat(
							referenceSchema.getSortableAttributeCompounds().values().stream().filter(it -> !updatedSchema.getName().equals(it.getName())),
							Stream.of(updatedSchema)
						)
						.collect(
							Collectors.toMap(
								SortableAttributeCompoundSchemaContract::getName,
								Function.identity()
							)
						)
				);
			}
		}
	}

}
