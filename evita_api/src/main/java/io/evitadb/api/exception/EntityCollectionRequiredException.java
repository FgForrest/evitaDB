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
 * The exception is thrown when engine needs to know a specific target entity collection, but the input query doesn't
 * specify it. The query needs to either define target collection by using {@link Collection} query or filter
 * the entities by targeting {@link GlobalAttributeSchemaContract} that allows to locate appropriate (even mixed) entities
 * in {@link CatalogContract}. If none of these preconditions is fulfilled this exception is thrown.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityCollectionRequiredException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4070054815751751842L;

	public EntityCollectionRequiredException(@Nonnull String publicMessage) {
		super("Collection type is required in query in order to compute " + publicMessage + "!");
	}

}
