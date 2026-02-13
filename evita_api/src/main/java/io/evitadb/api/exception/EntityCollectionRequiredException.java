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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when the query engine requires a specific entity collection to be identified, but the query
 * does not provide sufficient information to determine the target collection.
 *
 * This exception occurs when evitaDB needs to resolve which entity collection (entity type) to operate on, but
 * the query lacks the necessary context. A query can specify the target entity collection in two ways:
 *
 * 1. **Explicitly via {@link Collection} constraint**: The query directly names the entity collection
 * 2. **Implicitly via global attributes**: The query filters by {@link GlobalAttributeSchemaContract} attributes
 *    that allow evitaDB to infer the target collection (or query across multiple collections)
 *
 * **When this exception is thrown:**
 *
 * - Computing query results when no collection is specified
 * - Fetching entities without collection context
 * - Planning query execution when entity type cannot be determined
 * - Converting queries to external API formats (GraphQL, REST) that require explicit collection specification
 *
 * **Resolution**: Add a `collection(entityType)` constraint to the query header, or use global attribute filtering
 * that allows the engine to determine the target entity collection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityCollectionRequiredException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4070054815751751842L;

	/**
	 * Creates exception with a message describing what operation requires the collection specification.
	 *
	 * @param publicMessage describes the operation that cannot proceed without knowing the entity collection
	 */
	public EntityCollectionRequiredException(@Nonnull String publicMessage) {
		super("Collection type is required in query in order to compute " + publicMessage + "!");
	}

}
