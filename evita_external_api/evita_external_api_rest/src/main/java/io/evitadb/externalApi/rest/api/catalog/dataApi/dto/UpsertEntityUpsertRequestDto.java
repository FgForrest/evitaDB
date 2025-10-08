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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.NullNode;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityExistenceDeserializer;
import lombok.Builder;
import lombok.Setter;
import lombok.extern.jackson.Jacksonized;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * DTO used for entity upsert.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Builder
@Jacksonized
public class UpsertEntityUpsertRequestDto {

	@Setter
	private Integer primaryKey;
	@JsonDeserialize(using = EntityExistenceDeserializer.class)
	private final EntityExistence entityExistence;
	private final JsonNode mutations;
	private final JsonNode require;

	@Nonnull
	public Optional<Integer> getPrimaryKey() {
		return Optional.ofNullable(this.primaryKey);
	}

	@Nonnull
	public Optional<EntityExistence> getEntityExistence() {
		return Optional.ofNullable(this.entityExistence);
	}

	@Nonnull
	public Optional<JsonNode> getMutations() {
		if (this.mutations == null || this.mutations instanceof NullNode) {
			return Optional.empty();
		}
		return Optional.of(this.mutations);
	}

	@Nonnull
	public Optional<JsonNode> getRequire() {
		if (this.require == null || this.require instanceof NullNode) {
			return Optional.empty();
		}
		return Optional.of(this.require);
	}
}
