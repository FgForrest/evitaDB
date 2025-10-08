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

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * REST API DTO for {@link io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record ChangeCatalogCaptureCriteriaDto(
	@Nullable CaptureArea area,
	@Nullable CaptureSiteDto site
) {

	@Nonnull
	public ChangeCatalogCaptureCriteria toCriteria() {
		final CaptureSite<?> site = Optional.ofNullable(this.site)
			.map(CaptureSiteDto::toSite)
			.orElse(null);

		return new ChangeCatalogCaptureCriteria(this.area, site);
	}
}
