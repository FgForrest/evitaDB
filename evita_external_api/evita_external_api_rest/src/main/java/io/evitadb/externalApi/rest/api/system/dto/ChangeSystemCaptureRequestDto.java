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

package io.evitadb.externalApi.rest.api.system.dto;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

/**
 * REST API request DTO representing {@link io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest}.
 *
 * The optional `criteria` field carries the OR-ed list of {@link ChangeSystemCaptureCriteria}
 * entries the subscriber wants to receive. **Default-criteria divergence** vs the catalog
 * stream: when `criteria` is `null` (or absent in the JSON payload), the engine treats
 * the request as `ENGINE`-only — `HOST` events require an explicit criteria
 * element with `area = HOST`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record ChangeSystemCaptureRequestDto(
	@Nullable String sinceVersion,
	@Nullable Integer sinceIndex,
	@Nullable ChangeSystemCaptureCriteriaDto[] criteria,
	@Nullable ChangeCaptureContent content
) {

	@Nonnull
	public ChangeSystemCaptureRequest toRequest() {
		final Long sinceVersion = Optional.ofNullable(this.sinceVersion)
			.map(Long::parseLong)
			.orElse(null);
		final Integer sinceIndex = this.sinceIndex;
		final ChangeSystemCaptureCriteria[] criteria = Optional.ofNullable(this.criteria)
			.map(c -> Arrays.stream(c)
				.map(ChangeSystemCaptureCriteriaDto::toCriteria)
				.toArray(ChangeSystemCaptureCriteria[]::new))
			.orElse(null);
		final ChangeCaptureContent content = Optional.ofNullable(this.content)
			.orElse(ChangeCaptureContent.HEADER);

		return new ChangeSystemCaptureRequest(sinceVersion, sinceIndex, criteria, content);
	}

	/**
	 * REST API DTO for {@link ChangeSystemCaptureCriteria}.
	 *
	 * @param area the requested system area; `null` means "any area" within this single
	 *             criteria element (same OR-of-criteria semantics as
	 *             {@link io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria}).
	 *             Note that this is **not** the same as supplying a `null` criteria array
	 *             on the enclosing request — see the class-level divergence note.
	 */
	public record ChangeSystemCaptureCriteriaDto(
		@Nullable SystemCaptureArea area
	) {

		@Nonnull
		public ChangeSystemCaptureCriteria toCriteria() {
			return new ChangeSystemCaptureCriteria(this.area);
		}
	}
}
