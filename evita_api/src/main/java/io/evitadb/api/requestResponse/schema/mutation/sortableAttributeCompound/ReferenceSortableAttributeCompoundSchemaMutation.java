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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface with default method implementation, that contains shared logic for attribute schema modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface ReferenceSortableAttributeCompoundSchemaMutation extends SortableAttributeCompoundSchemaMutation, ReferenceSchemaMutation {

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
			return ReferenceSchema._internalBuild(
				referenceSchema.getName(),
				referenceSchema.getNameVariants(),
				referenceSchema.getDescription(),
				referenceSchema.getDeprecationNotice(),
				referenceSchema.getReferencedEntityType(),
				referenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
				referenceSchema.isReferencedEntityTypeManaged(),
				referenceSchema.getCardinality(),
				referenceSchema.getReferencedGroupType(),
				referenceSchema.isReferencedGroupTypeManaged() ? Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
				referenceSchema.isReferencedGroupTypeManaged(),
				referenceSchema.isIndexed(),
				referenceSchema.isFaceted(),
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
