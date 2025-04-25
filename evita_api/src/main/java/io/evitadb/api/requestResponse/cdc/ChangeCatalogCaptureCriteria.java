/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Record for the criteria of the capture request allowing to limit mutations to specific area of interest an its
 * properties.
 *
 * @param area the requested area of the capture (must be provided when the site is provided)
 * @param site the filter for the events to be sent, limits the amount of events sent to the subscriber
 *             to only those that are relevant to the site and area
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record ChangeCatalogCaptureCriteria(
	@Nullable CaptureArea area,
	@Nullable CaptureSite site
) {

	public ChangeCatalogCaptureCriteria {
		if (area != null) {
			switch (area) {
				case SCHEMA -> Assert.isTrue(site instanceof SchemaSite, "Schema site must be provided for schema area");
				case DATA -> Assert.isTrue(site instanceof DataSite, "Data site must be provided for data area");
				case INFRASTRUCTURE -> throw new EvitaInvalidUsageException("Infrastructure area is not supported");
			}
		}
	}

	/**
	 * Creates builder object that helps you create criteria record using builder pattern.
	 *
	 * @return new instance of {@link ChangeCatalogCaptureCriteria.Builder}
	 */
	@Nonnull
	public static ChangeCatalogCaptureCriteria.Builder builder() {
		return new ChangeCatalogCaptureCriteria.Builder();
	}

	/**
	 * Builder class for {@link ChangeCatalogCaptureRequest}.
	 */
	public static class Builder {
		@Nullable private CaptureArea area;
		@Nullable private CaptureSite site;

		/**
		 * Sets the area of the capture.
		 *
		 * @param area the area of the capture
		 * @return this builder
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder area(@Nullable CaptureArea area) {
			this.area = area;
			return this;
		}

		/**
		 * Sets the site of the capture.
		 *
		 * @param site the site of the capture
		 * @return this builder
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder site(@Nullable CaptureSite site) {
			this.site = site;
			return this;
		}

		/**
		 * Builds the {@link ChangeCatalogCaptureCriteria} record.
		 *
		 * @return the built record
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria build() {
			return new ChangeCatalogCaptureCriteria(
				this.area,
				this.site
			);
		}

	}

}
