/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a schema mutation cannot be applied due to validation failures or evolution mode restrictions.
 *
 * This exception is raised when attempting to alter catalog or entity schemas in ways that violate schema consistency
 * rules or exceed the permissions granted by the current {@link CatalogEvolutionMode}. Schema mutations are strictly
 * controlled to prevent accidental or unauthorized schema changes that could break existing data or client applications.
 *
 * **Common Scenarios:**
 * - Attempting to automatically create a new entity collection when {@link CatalogEvolutionMode#ADDING_ENTITY_TYPES}
 *   is not enabled
 * - Modifying schema attributes, references, or associated data definitions in ways that conflict with existing data
 * - Changing schema properties that require specific evolution modes (e.g., renaming entities, changing types)
 * - Violating schema constraints (e.g., creating duplicate attributes, invalid sortable attribute compounds)
 *
 * **Evolution Modes:**
 * Schema mutations are gated by catalog evolution modes, which control the degree of schema flexibility:
 * - `ADDING_ENTITY_TYPES`: allows creation of new entity collections
 * - `ADDING_ATTRIBUTES`: allows adding new attributes to existing entities
 * - Other modes control different aspects of schema evolution
 *
 * **Usage Context:**
 * - Schema mutation processors: validate mutations before application
 * - Schema builders: enforce evolution mode restrictions
 * - Catalog and entity schema implementations: validate structural changes
 * - Used extensively across reference, attribute, and associated data mutation classes
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidSchemaMutationException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = -570962599377067302L;

	/**
	 * Creates a new exception with a custom error message.
	 *
	 * @param message detailed error message describing why the schema mutation cannot be performed
	 */
	public InvalidSchemaMutationException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Creates a new exception indicating that an entity collection cannot be automatically created because
	 * the required evolution mode is not enabled.
	 *
	 * @param entityType the name of the entity collection that would be created
	 * @param necessaryEvolutionMode the {@link CatalogEvolutionMode} required to allow automatic creation
	 */
	public InvalidSchemaMutationException(@Nonnull String entityType, @Nonnull CatalogEvolutionMode necessaryEvolutionMode) {
		this(
			"The entity collection `" + entityType + "` doesn't exist and would be automatically created," +
				" providing that catalog schema allows `" + necessaryEvolutionMode + "`" +
				" evolution mode."
		);
	}

}
