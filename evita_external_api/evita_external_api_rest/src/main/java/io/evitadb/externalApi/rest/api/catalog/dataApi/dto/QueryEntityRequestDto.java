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

package io.evitadb.externalApi.rest.api.catalog.dataApi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * DTO used to get root query constraints from request to get list of entities.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Builder
@Jacksonized
public class QueryEntityRequestDto {

	private final JsonNode head;
	private final JsonNode filterBy;
	private final JsonNode orderBy;
	private final JsonNode require;

	@Nonnull
	public Optional<JsonNode> getHead() {
		return getContainer(this.head);
	}

	@Nonnull
	public Optional<JsonNode> getFilterBy() {
		return getContainer(this.filterBy);
	}

	@Nonnull
	public Optional<JsonNode> getOrderBy() {
		return getContainer(this.orderBy);
	}

	@Nonnull
	public Optional<JsonNode> getRequire() {
		return getContainer(this.require);
	}

	@Nonnull
	private static Optional<JsonNode> getContainer(@Nullable JsonNode container) {
		if (container == null || container instanceof NullNode) {
			return Optional.empty();
		}
		return Optional.of(container);
	}
}
