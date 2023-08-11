/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;

import javax.annotation.Nullable;

/**
 * Record describing the location and form of the CDC event in the evitaDB that should be captured.
 *
 * @param entityType the {@link EntitySchema#getName()} of the intercepted entity type
 * @param operation the intercepted type of {@link Operation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record SchemaSite(
	@Nullable String entityType,
	@Nullable Operation[] operation
) implements CaptureSite {
	public static final SchemaSite ALL = new SchemaSite(null, Operation.values());

	public SchemaSite(@Nullable Operation... operation) {
		this(null, operation);
	}
}
