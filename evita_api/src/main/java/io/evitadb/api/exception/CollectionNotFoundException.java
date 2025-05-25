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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when client tries to access a collection which doesn't exist.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CollectionNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 1445427449119582559L;

	public CollectionNotFoundException(@Nonnull String entityType) {
		super("No collection found for entity type `" + entityType + "`!");
	}

	public CollectionNotFoundException(@Nonnull Class<?> modelClass) {
		super(
			"Entity type cannot be resolved. Neither `@Entity` no `@EntityRef` " +
				"annotation was found on model class: `" + modelClass.getName() + "`!"
		);
	}

	public CollectionNotFoundException(int entityTypePrimaryKey) {
		super("No collection found for entity type with primary key `" + entityTypePrimaryKey + "`!");
	}

}
