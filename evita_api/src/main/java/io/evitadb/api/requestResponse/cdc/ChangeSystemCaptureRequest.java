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

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Record describing the capture request for the {@link ChangeCapturePublisher} of {@link ChangeSystemCapture}s.
 * The request contains the recipe for the messages that the subscriber is interested in, and that are sent to it by
 * {@link ChangeCapturePublisher}.
 *
 * @param sinceVersion specifies the initial capture point (catalog version) for the CDC stream, if not specified
 *                     it is assumed to begin at the most recent / greatest available version
 * @param sinceIndex   specifies the initial capture point for the CDC stream, it is optional and can be used
 *                     to specify continuation point within an enclosing block of events
 * @param criteria     the criteria of the capture, OR-ed semantics — matching any of them is sufficient.
 *                     **Default-criteria divergence vs {@link ChangeCatalogCaptureRequest}.** When `criteria`
 *                     is `null`, the system stream defaults to {@link SystemCaptureArea#ENGINE} **only** —
 *                     {@link SystemCaptureArea#HOST} is **not** included in the default flow. This
 *                     differs from the catalog stream, where a `null` criteria captures all areas.
 *                     The reason: `HOST` events here are host-local, non-replicable, live-tail-only
 *                     {@link HostSystemEvent}s; subscribers that have not opted in must keep receiving exactly
 *                     the engine-mutation flow they already see, so the stream shape does not change silently
 *                     between versions. Subscribers that want host events must explicitly request
 *                     {@link SystemCaptureArea#HOST} via {@link ChangeSystemCaptureCriteria}.
 * @param content      the requested content of the capture, by default, only the header information is sent
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeSystemCaptureRequest(
	@Nullable Long sinceVersion,
	@Nullable Integer sinceIndex,
	@Nullable ChangeSystemCaptureCriteria[] criteria,
	@Nonnull ChangeCaptureContent content
) implements ChangeCaptureRequest {

	/**
	 * Creates builder object that helps you create ChangeSystemCaptureRequest record using builder pattern.
	 * @return new instance of {@link ChangeSystemCaptureRequest.Builder}
	 */
	@Nonnull
	public static ChangeSystemCaptureRequest.Builder builder() {
		return new ChangeSystemCaptureRequest.Builder();
	}

	/**
	 * Builder class for {@link ChangeSystemCaptureRequest}.
	 */
	public static class Builder {
		@Nullable private Long sinceVersion;
		@Nullable private Integer sinceIndex;
		@Nullable private ChangeSystemCaptureCriteria[] criteria;
		@Nonnull private ChangeCaptureContent content = ChangeCaptureContent.HEADER;

		/**
		 * Sets the criteria of the capture.
		 *
		 * @param criteria the criteria of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder criteria(@Nonnull ChangeSystemCaptureCriteria... criteria) {
			this.criteria = criteria;
			return this;
		}

		/**
		 * Convenience: configures the capture to include the {@link SystemCaptureArea#ENGINE}
		 * area only. Equivalent to passing a single criteria with `area = ENGINE`.
		 *
		 * @return this builder
		 */
		@Nonnull
		public Builder engineArea() {
			this.criteria = new ChangeSystemCaptureCriteria[] {
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE)
			};
			return this;
		}

		/**
		 * Convenience: configures the capture to include the {@link SystemCaptureArea#HOST}
		 * area only. Equivalent to passing a single criteria with `area = HOST`.
		 * Required to receive {@link HostSystemEvent}s — they are not delivered under the
		 * default null-criteria flow.
		 *
		 * @return this builder
		 */
		@Nonnull
		public Builder hostArea() {
			this.criteria = new ChangeSystemCaptureCriteria[] {
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			};
			return this;
		}

		/**
		 * Sets the content of the capture.
		 * @param content the content of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder content(@Nonnull ChangeCaptureContent content) {
			this.content = content;
			return this;
		}

		/**
		 * Sets the sinceVersion of the capture.
		 * @param sinceVersion the sinceVersion of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder sinceVersion(long sinceVersion) {
			this.sinceVersion = sinceVersion;
			return this;
		}

		/**
		 * Sets the sinceIndex of the capture.
		 * @param sinceIndex the sinceIndex of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder sinceIndex(int sinceIndex) {
			this.sinceIndex = sinceIndex;
			return this;
		}

		/**
		 * Builds the {@link ChangeSystemCaptureRequest} object.
		 * @return the built object
		 */
		@Nonnull
		public ChangeSystemCaptureRequest build() {
			return new ChangeSystemCaptureRequest(
				this.sinceVersion,
				this.sinceIndex,
				this.criteria,
				this.content
			);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final ChangeSystemCaptureRequest that)) return false;

		return Objects.equals(this.sinceVersion, that.sinceVersion)
			&& Objects.equals(this.sinceIndex, that.sinceIndex)
			&& this.content == that.content
			&& Arrays.equals(this.criteria, that.criteria);
	}

	@Override
	public int hashCode() {
		int result = Objects.hashCode(this.sinceVersion);
		result = 31 * result + Objects.hashCode(this.sinceIndex);
		result = 31 * result + Arrays.hashCode(this.criteria);
		result = 31 * result + this.content.hashCode();
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "ChangeSystemCaptureRequest{" +
			"sinceVersion=" + this.sinceVersion +
			", sinceIndex=" + this.sinceIndex +
			", criteria=" + Arrays.toString(this.criteria) +
			", content=" + this.content +
			'}';
	}

}
