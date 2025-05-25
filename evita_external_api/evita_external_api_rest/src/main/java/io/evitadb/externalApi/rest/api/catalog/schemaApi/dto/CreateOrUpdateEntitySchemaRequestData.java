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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * DTO used for entity schema creation and update.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Builder
@Jacksonized
public class CreateOrUpdateEntitySchemaRequestData {

	private final JsonNode mutations;

	@Nonnull
	public Optional<JsonNode> getMutations() {
		if (this.mutations == null || this.mutations instanceof NullNode) {
			return Optional.empty();
		}
		return Optional.of(this.mutations);
	}
}
