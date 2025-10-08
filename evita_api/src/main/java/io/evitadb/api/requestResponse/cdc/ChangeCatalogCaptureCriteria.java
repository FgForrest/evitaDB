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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

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
	@Nullable CaptureSite<?> site
) implements Comparable<ChangeCatalogCaptureCriteria> {

	public ChangeCatalogCaptureCriteria {
		if (area != null) {
			switch (area) {
				case SCHEMA -> Assert.isTrue(site instanceof SchemaSite, "Schema site must be provided for schema area");
				case DATA -> Assert.isTrue(site instanceof DataSite, "Data site must be provided for data area");
				case INFRASTRUCTURE -> Assert.isTrue(site == null, "Infrastructure area must not have site defined");
			}
		}
	}

	@Override
	public int compareTo(@Nonnull ChangeCatalogCaptureCriteria other) {
		int result = this.area == null ?
			(other.area == null ? 0 : -1) :
			(other.area == null ? 1 : this.area.compareTo(other.area));

		if (result == 0) {
			if (this.site == null || other.site == null) {
				result = this.site == null ?
					other.site == null ? 0 : -1 : 1;
			} else if (this.site.getClass().equals(other.site.getClass())) {
				//noinspection rawtypes,unchecked
				return ((Comparable) this.site).compareTo(other.site);
			} else {
				return this.site.getClass().getName().compareTo(other.site.getClass().getName());
			}
		}
		return result;
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
		@Nullable private CaptureSite<?> site;

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
		 * Configures the data area for the capture request by accepting a modifier for the {@link DataSite.Builder}.
		 * The capture will consume all data changes in the catalog.
		 *
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder dataArea() {
			final DataSite.Builder builder = DataSite.builder();
			this.area = CaptureArea.DATA;
			this.site = builder.build();
			return this;
		}

		/**
		 * Configures the data area for the capture request by accepting a modifier for the {@link DataSite.Builder}.
		 *
		 * @param configurer a consumer that configures the {@link DataSite.Builder}
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder dataArea(@Nonnull Consumer<DataSite.Builder> configurer) {
			final DataSite.Builder builder = DataSite.builder();
			configurer.accept(builder);
			this.area = CaptureArea.DATA;
			this.site = builder.build();
			return this;
		}

		/**
		 * Configures the data area for the capture request by accepting a modifier for the {@link SchemaSite.Builder}.
		 * The capture will consume all schema changes in the catalog.
		 *
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder schemaArea() {
			final SchemaSite.Builder builder = SchemaSite.builder();
			this.area = CaptureArea.SCHEMA;
			this.site = builder.build();
			return this;
		}

		/**
		 * Configures the data area for the capture request by accepting a modifier for the {@link SchemaSite.Builder}.
		 *
		 * @param configurer a consumer that configures the {@link SchemaSite.Builder}
		 * @return this builder instance
		 */
		@Nonnull
		public ChangeCatalogCaptureCriteria.Builder schemaArea(@Nonnull Consumer<SchemaSite.Builder> configurer) {
			final SchemaSite.Builder builder = SchemaSite.builder();
			configurer.accept(builder);
			this.area = CaptureArea.SCHEMA;
			this.site = builder.build();
			return this;
		}

		/**
		 * Sets the site of the capture.
		 *
		 * @param site the site of the capture
		 * @return this builder
		 */
		@Nonnull
		public <T extends CaptureSite<T>> ChangeCatalogCaptureCriteria.Builder site(@Nullable T site) {
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
