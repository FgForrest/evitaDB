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
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is throw when {@link io.evitadb.api.EvitaSessionContract} is asked to return the query result wrapped into
 * as custom entity interface and it fails to do so due to an invalid interface definition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityClassInvalidException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4659727444287535443L;
	@Getter private final Class<?> modelClass;

	public EntityClassInvalidException(@Nonnull Class<?> modelClass, @Nonnull Throwable cause) {
		super(
			"Failed to wrap `SealedEntity` into class `" + modelClass + "` due to: " + cause.getMessage(),
			"Failed to wrap `SealedEntity`.",
			cause
		);
		this.modelClass = modelClass;
	}

	public EntityClassInvalidException(@Nonnull Class<?> modelClass, @Nonnull String message) {
		super(
			"Failed to wrap `SealedEntity` into class `" + modelClass + "` due to: " + message,
			"Failed to wrap `SealedEntity`."
		);
		this.modelClass = modelClass;
	}
}
