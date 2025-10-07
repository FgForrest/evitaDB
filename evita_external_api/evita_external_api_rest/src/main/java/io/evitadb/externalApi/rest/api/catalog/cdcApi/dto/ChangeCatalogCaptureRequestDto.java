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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

/**
 * REST API request DTO representing {@link io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record ChangeCatalogCaptureRequestDto(
	@Nullable String sinceVersion,
	@Nullable Integer sinceIndex,
	@Nullable ChangeCatalogCaptureCriteriaDto[] criteria,
	@Nullable ChangeCaptureContent content
) {

	@Nonnull
	public ChangeCatalogCaptureRequest toRequest() {
		final Long sinceVersion = Optional.ofNullable(this.sinceVersion)
			.map(Long::parseLong)
			.orElse(null);
		final Integer sinceIndex = this.sinceIndex;
		final ChangeCatalogCaptureCriteria[] criteria = Optional.ofNullable(this.criteria)
			.map(c -> Arrays.stream(c)
				.map(ChangeCatalogCaptureCriteriaDto::toCriteria)
				.toArray(ChangeCatalogCaptureCriteria[]::new))
			.orElse(null);
		final ChangeCaptureContent content = Optional.ofNullable(this.content)
			.orElse(ChangeCaptureContent.HEADER);

		return new ChangeCatalogCaptureRequest(sinceVersion, sinceIndex, criteria, content);
	}
}
