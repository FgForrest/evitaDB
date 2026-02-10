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

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when attempting to define an associated data schema whose name, when converted to a specific
 * naming convention, conflicts with an existing associated data schema name in the same entity schema.
 *
 * evitaDB supports multiple naming conventions (camelCase, snake_case, UPPER_CASE, etc.) and automatically
 * generates names in all conventions from a canonical name. This exception is thrown when two different
 * canonical names produce the same name in at least one naming convention, which would make them
 * indistinguishable in that convention.
 *
 * **When this is thrown:**
 * - During entity schema evolution when adding/modifying associated data definitions
 * - When two associated data names map to the same name in a specific naming convention
 * - Thrown by `InternalEntitySchemaBuilder` during schema validation
 *
 * **Example conflict:**
 * - Canonical name `user-data` produces `userData` in camelCase
 * - Canonical name `userData` also produces `userData` in camelCase
 * - These two would be indistinguishable when accessed via camelCase convention
 *
 * **Resolution:**
 * - Choose a different canonical name that doesn't conflict in any naming convention
 * - Check all naming convention variants of your proposed name against existing schemas
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AssociatedDataAlreadyPresentInEntitySchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -7318863955275156452L;
	/**
	 * The existing associated data schema that conflicts with the newly defined one.
	 */
	@Getter private final AssociatedDataSchemaContract existingSchema;

	/**
	 * Creates exception detailing which two associated data schemas have conflicting names in a specific
	 * naming convention.
	 */
	public AssociatedDataAlreadyPresentInEntitySchemaException(
		@Nonnull AssociatedDataSchemaContract existingAssociatedData,
		@Nonnull AssociatedDataSchemaContract updatedAssociatedData,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName) {
		super(
			"Associated data `" + updatedAssociatedData.getName() + "` and " +
				"existing associated data `" + existingAssociatedData.getName() + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different associated data name."
		);
		this.existingSchema = existingAssociatedData;
	}
}
