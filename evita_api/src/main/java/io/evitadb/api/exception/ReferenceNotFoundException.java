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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

import static java.util.Optional.ofNullable;

/**
 * Exception is thrown when there is attempt to filter by a non-existing reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -8969284548331815445L;

	public ReferenceNotFoundException(@Nonnull String referenceName) {
		super("Reference with name `" + referenceName + "` cannot be located when entity type is not known.");
	}

	public ReferenceNotFoundException(@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema) {
		super("Reference with name `" + referenceName + "` is not present in schema of `" + entitySchema.getName() + "` entity.");
	}

	public ReferenceNotFoundException(@Nonnull String referenceName, int referencedEntityId, @Nonnull EntityContract entity) {
		super("Reference with name `" + referenceName + "` to entity with id `" + referencedEntityId + "` " +
			"is not present in the entity `" + entity.getType() + "` with " +
			ofNullable(entity.getPrimaryKey())
				.map(it -> "primary key `" + entity.getPrimaryKey() + "`")
				.orElse("not yet assigned primary key") + "."
		);
	}

}
