/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
 * @param area         the requested area of the capture (must be provided when the site is provided)
 * @param site         the filter for the events to be sent, limits the amount of events sent to the subscriber
 *                     to only those that are relevant to the site and area
 * @param content      the requested content of the capture, by default only the header information is sent
 * @param sinceVersion specifies the initial capture point for the CDC stream, it must always provide a last
 *                     known version from the client point of view
 * @param sinceIndex   specifies the initial capture point for the CDC stream, is is optional and can be used
 *                     to specify continuation point within enclosing block of events
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeCatalogCaptureRequest(
	@Nullable CaptureArea area,
	@Nullable CaptureSite site,
	@Nonnull CaptureContent content,
	long sinceVersion,
	@Nullable Integer sinceIndex
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
		private CaptureArea area;
		private CaptureSite site;
		private CaptureContent content = CaptureContent.HEADER;
		private long sinceVersion;
		private Integer sinceIndex;

		/**
		 * Sets the area of the capture.
		 * @param area the area of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder area(@Nullable CaptureArea area) {
			this.area = area;
			return this;
		}

		/**
		 * Sets the site of the capture.
		 * @param site the site of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder site(@Nullable CaptureSite site) {
			this.site = site;
			return this;
		}

		/**
		 * Sets the content of the capture.
		 * @param content the content of the capture
		 * @return this builder
		 */
		@Nonnull
		public Builder content(@Nonnull CaptureContent content) {
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
			return new ChangeCatalogCaptureRequest(area, site, content, sinceVersion, sinceIndex);
		}
	}

}
