/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.catalog.cdcApi.dto;

import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.dataType.ContainerType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * REST API DTO for {@link io.evitadb.api.requestResponse.cdc.DataSite}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record DataSiteDto(
	@Nullable String entityType,
	@Nullable Integer entityPrimaryKey,
	@Nullable Operation[] operation,
	@Nullable ContainerType[] containerType,
	@Nullable String[] containerName
) implements CaptureSiteDto {

	@Nonnull
	@Override
	public CaptureSite<?> toSite() {
		return new DataSite(
			this.entityType,
			this.entityPrimaryKey,
			this.operation,
			this.containerType,
			this.containerName
		);
	}
}
