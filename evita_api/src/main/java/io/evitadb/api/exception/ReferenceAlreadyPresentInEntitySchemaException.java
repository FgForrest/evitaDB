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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when client code attempts to define a reference schema with a name that
 * conflicts with an existing reference schema in the entity after applying naming conventions.
 *
 * evitaDB supports multiple {@link NamingConvention} transformations for entity and reference
 * names (e.g., camelCase, snake_case, UPPER_CASE). Two reference schemas with different base
 * names may produce the same name when transformed to a specific naming convention, causing
 * ambiguity. This exception prevents such conflicts.
 *
 * For example, reference schemas named `product_category` and `productCategory` would both
 * produce `productCategory` in camelCase convention, triggering this exception.
 *
 * **Resolution**: The client must choose a different reference schema name that does not conflict
 * in any supported naming convention, or reuse the existing reference schema if it represents the
 * same logical relationship.
 *
 * This exception is typically raised during schema evolution when adding or modifying reference
 * schemas via {@link io.evitadb.api.requestResponse.schema.EntitySchemaEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceAlreadyPresentInEntitySchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4058501830732065207L;
	/**
	 * The existing reference schema that conflicts with the new one.
	 */
	@Getter private final ReferenceSchemaContract existingSchema;

	/**
	 * Constructs a new exception indicating a naming conflict between two reference schemas.
	 *
	 * @param existingReferenceSchema the reference schema that already exists in the entity
	 * @param updatedReferenceSchema  the reference schema being added or updated
	 * @param convention              the naming convention in which the conflict occurs
	 * @param conflictingName         the conflicting name produced by both schemas in the specified
	 *                                convention
	 */
	public ReferenceAlreadyPresentInEntitySchemaException(
		@Nonnull ReferenceSchemaContract existingReferenceSchema,
		@Nonnull ReferenceSchemaContract updatedReferenceSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
		super(
			"Reference schema `" + updatedReferenceSchema.getName() + "` and existing " +
				"reference schema `" + existingReferenceSchema.getName() + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different reference schema name."
		);
		this.existingSchema = existingReferenceSchema;
	}
}
