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

/**
 * Record describing the capture request for the {@link ChangeCapturePublisher} of {@link ChangeCatalogCapture}s.
 * The request contains the recipe for the messages that the subscriber is interested in, and that are sent to it by
 * {@link ChangeCapturePublisher}.
 *
 *
 * @param sinceVersion specifies the initial capture point (catalog version) for the CDC stream, if not specified
 *                     it is assumed to begin at the most recent / oldest available version
 * @param sinceIndex   specifies the initial capture point for the CDC stream, it is optional and can be used
 *                     to specify continuation point within an enclosing block of events
 * @param criteria     the criteria of the capture, if not specified - all changes are captured, if multiple are specified
 *                     matching any of them is sufficient (OR)
 * @param content      the requested content of the capture, by default, only the header information is sent
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeCatalogCaptureRequest(
	@Nullable Long sinceVersion,
	@Nullable Integer sinceIndex,
	@Nullable ChangeCatalogCaptureCriteria[] criteria,
	@Nonnull ChangeCaptureContent content
) implements ChangeCaptureRequest {

	/**
	 * Creates builder object that helps you create DataSite record using builder pattern.
	 * @return new instance of {@link DataSite.Builder}
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest.Builder builder() {
		return new ChangeCatalogCaptureRequest.Builder();
	}

	/**
	 * Builder class for {@link ChangeCatalogCaptureRequest}.
	 */
	public static class Builder {
		private Long sinceVersion;
		private Integer sinceIndex;
		private ChangeCatalogCaptureCriteria[] criteria;
		private ChangeCaptureContent content = ChangeCaptureContent.HEADER;

		/**
		 * Sets the criteria of the capture.
		 * @param criteria the criteria of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder criteria(@Nonnull ChangeCatalogCaptureCriteria... criteria) {
			this.criteria = criteria;
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
		 * Builds the {@link ChangeCatalogCaptureRequest} object.
		 * @return the built object
		 */
		@Nonnull
		public ChangeCatalogCaptureRequest build() {
			return new ChangeCatalogCaptureRequest(
				this.sinceVersion,
				this.sinceIndex,
				this.criteria,
				this.content
			);
		}
	}

}
