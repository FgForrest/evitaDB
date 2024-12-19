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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;

/**
 * Exception is thrown when entity is required for the operation, but it doesn't currently exist.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7500395432304631043L;

	public EntityMissingException(@Nonnull String entityType, int[] entityPrimaryKey, @Nullable String additionalMessage) {
		super(
			entityPrimaryKey.length == 1 ?
				"Entity `" + entityType + "` with primary key " + entityPrimaryKey[0] +
				" are not currently present in the database." +
				(additionalMessage != null ? " " + additionalMessage : "") :
				"Entities of type `" + entityType + "` with primary keys: " + Arrays.toString(entityPrimaryKey) +
				" are not currently present in the database." +
				(additionalMessage != null ? " " + additionalMessage : "")
		);
	}
}
