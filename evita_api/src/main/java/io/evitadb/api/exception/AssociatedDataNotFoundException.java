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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when attempting to access associated data by name that is not defined in the entity schema.
 *
 * This exception occurs when code tries to retrieve associated data using a name that doesn't exist in
 * the entity schema's associated data definitions. This is a validation error that prevents runtime
 * issues from using undefined associated data names in queries or data access methods.
 *
 * **When this is thrown:**
 * - Calling `getAssociatedData()` or `getAssociatedDataValue()` with a non-existent name
 * - Using `associatedDataContent` query constraint with undefined associated data names
 * - Accessing associated data via proxy interfaces with invalid names
 * - Thrown by `AssociatedData`, `AssociatedDataContentTranslator`, and related data access classes
 *
 * **Resolution:**
 * - Check the entity schema for valid associated data names
 * - Define the associated data in the entity schema before attempting to use it
 * - Verify the spelling and case of the associated data name
 * - Use schema introspection methods to discover available associated data names
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AssociatedDataNotFoundException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = 4499637767749821410L;

	/**
	 * Creates exception for associated data that doesn't exist in the specified entity schema.
	 */
	public AssociatedDataNotFoundException(@Nonnull String associatedDataName, @Nonnull EntitySchemaContract entitySchema) {
		super("Associated data with name `" + associatedDataName + "` is not present in schema of `" + entitySchema.getName() + "` entity.");
	}

}
