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

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Record for the criteria of a system capture request, allowing the subscriber to limit
 * the captured events to a single {@link SystemCaptureArea}.
 *
 * **No site axis (yet).** Unlike {@link ChangeCatalogCaptureCriteria}, the system stream
 * has no sub-filter axis: there is currently no way to slice an `ENGINE` flow by a
 * specific mutation kind, nor a `HOST` flow by a specific catalog name.
 * The slot is intentionally reserved — when such filtering becomes useful we expect
 * to add a `site` field here, mirroring the catalog criteria. When a future site axis
 * is added, the gRPC converter (`ChangeCaptureConverter#toGrpcChangeSystemCaptureCriteria`),
 * REST DTO (`ChangeSystemCaptureRequestDto.ChangeSystemCaptureCriteriaDto`), and GraphQL
 * parser (`OnSystemChangeCaptureSubscribingDataFetcher#parseCriteria`) must all be updated
 * in lockstep.
 *
 * **Default-criteria divergence** (also documented on {@link ChangeSystemCaptureRequest}
 * and {@link SystemCaptureArea}): when a {@link ChangeSystemCaptureRequest}'s `criteria`
 * is `null`, the subscriber receives the `ENGINE` area only — `HOST` requires
 * an explicit criteria entry. The catalog stream defaults to all areas; the system
 * stream defaults to engine-only because `HOST` here means host-local,
 * non-replicable, live-tail-only events that existing clients have not opted in to.
 *
 * @param area the requested system area; `null` means "any area" within an explicit
 *             criteria element (same OR-of-criteria semantics as
 *             {@link ChangeCatalogCaptureCriteria}). Note that this is **not** the
 *             same as supplying a `null` criteria array on the request — see the
 *             divergence note above.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ChangeSystemCaptureCriteria(
	@Nullable SystemCaptureArea area
) implements Comparable<ChangeSystemCaptureCriteria> {

	@Override
	public int compareTo(@Nonnull ChangeSystemCaptureCriteria other) {
		if (this.area == null) {
			return other.area == null ? 0 : -1;
		}
		if (other.area == null) {
			return 1;
		}
		return this.area.compareTo(other.area);
	}

	/**
	 * Creates builder object that helps you create criteria record using builder pattern.
	 *
	 * @return new instance of {@link ChangeSystemCaptureCriteria.Builder}
	 */
	@Nonnull
	public static ChangeSystemCaptureCriteria.Builder builder() {
		return new ChangeSystemCaptureCriteria.Builder();
	}

	/**
	 * Builder class for {@link ChangeSystemCaptureCriteria}.
	 */
	public static class Builder {
		@Nullable private SystemCaptureArea area;

		/**
		 * Sets the area of the capture.
		 *
		 * @param area the area of the capture
		 * @return this builder
		 */
		@Nonnull
		public ChangeSystemCaptureCriteria.Builder area(@Nullable SystemCaptureArea area) {
			this.area = area;
			return this;
		}

		/**
		 * Configures the engine area for the capture request — durable, WAL-replicated
		 * engine-level mutations.
		 *
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeSystemCaptureCriteria.Builder engineArea() {
			this.area = SystemCaptureArea.ENGINE;
			return this;
		}

		/**
		 * Configures the host area for the capture request — host-local,
		 * non-replicable, live-tail-only host events.
		 *
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeSystemCaptureCriteria.Builder hostArea() {
			this.area = SystemCaptureArea.HOST;
			return this;
		}

		/**
		 * Builds the {@link ChangeSystemCaptureCriteria} record.
		 *
		 * @return the built record
		 */
		@Nonnull
		public ChangeSystemCaptureCriteria build() {
			return new ChangeSystemCaptureCriteria(this.area);
		}

	}

}
