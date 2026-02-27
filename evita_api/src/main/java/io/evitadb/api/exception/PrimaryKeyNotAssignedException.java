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


import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to retrieve the primary key of an entity that has not been persisted yet.
 *
 * In evitaDB, entities can be created and manipulated in memory before being stored. When an entity is first
 * created using a builder without an explicitly assigned primary key, the primary key remains `null` until the
 * entity is successfully persisted to the database. The database assigns a unique primary key during the first
 * upsert operation.
 *
 * This exception is thrown when client code calls {@link io.evitadb.api.requestResponse.data.EntityClassifier#getPrimaryKeyNotNull()}
 * on an entity that has not yet been assigned a primary key.
 *
 * **Typical Scenario:**
 * ```java
 * Entity newEntity = session.createNewEntity("Product")
 *     .setAttribute("name", "New Product")
 *     .toInstance();
 *
 * // This will throw PrimaryKeyNotAssignedException:
 * int pk = newEntity.getPrimaryKeyNotNull();
 *
 * // Must persist first to get a primary key:
 * EntityReference ref = session.upsertEntity(newEntity);
 * int assignedPk = ref.getPrimaryKey();  // Now has a value
 * ```
 *
 * **Usage Context:**
 * - {@link io.evitadb.api.requestResponse.data.EntityClassifier#getPrimaryKeyNotNull()}: throws this exception
 *   when primary key is `null`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PrimaryKeyNotAssignedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2077933335811704930L;

	/**
	 * Creates a new exception indicating that an entity's primary key has not been assigned.
	 *
	 * @param entityType the type (name) of the entity that lacks a primary key
	 */
	public PrimaryKeyNotAssignedException(@Nonnull String entityType) {
		super("Primary key for entity `" + entityType + "` has not been assigned yet. Please store the entity first.");
	}

}
