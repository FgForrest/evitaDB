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

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.ContainerType;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Record describing the location and form of the CDC event in the evitaDB that should be captured.
 *
 * @param entityType    the {@link EntitySchema#getName()} of the intercepted entity type
 * @param operation     the intercepted type of {@link Operation}
 * @param containerType the intercepted {@link ContainerType} of the entity data
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record SchemaSite(
	@Nullable String entityType,
	@Nullable Operation[] operation,
	@Nullable ContainerType[] containerType
) implements CaptureSite<SchemaSite> {
	public static final SchemaSite ALL = new SchemaSite(null, null, null);

	public SchemaSite {
		if (operation != null) {
			Arrays.sort(operation);
		}
		if (containerType != null) {
			Arrays.sort(containerType);
		}
	}

	@Override
	public int compareTo(@Nonnull SchemaSite other) {
		if (this == other) return 0;
		int result = this.entityType == null ?
			(other.entityType == null ? 0 : -1) :
			(other.entityType == null ? 1 : this.entityType.compareTo(other.entityType));
		if (result == 0) {
			result = this.operation == null ?
				(other.operation == null ? 0 : -1) :
				(other.operation == null ? 1 : ArrayUtils.compare(this.operation, other.operation));
		}
		if (result == 0) {
			result = this.containerType == null ?
				(other.containerType == null ? 0 : -1) :
				(other.containerType == null ? 1 : ArrayUtils.compare(this.containerType, other.containerType));
		}
		return result;
	}

	/**
	 * Creates builder object that helps you create DataSite record using builder pattern.
	 *
	 * @return new instance of {@link DataSite.Builder}
	 */
	@Nonnull
	public static SchemaSite.Builder builder() {
		return new SchemaSite.Builder();
	}

	/**
	 * Builder class for {@link SchemaSite}.
	 */
	public static class Builder {
		@Nullable private String entityType;
		@Nullable private Operation[] operation;
		@Nullable private ContainerType[] containerType;

		/**
		 * Sets the entity type.
		 *
		 * @param entityType the entity type
		 * @return this builder
		 */
		@Nonnull
		public Builder entityType(@Nullable String entityType) {
			this.entityType = entityType;
			return this;
		}

		/**
		 * Sets the operation.
		 *
		 * @param operation the operation
		 * @return this builder
		 */
		@Nonnull
		public Builder operation(@Nullable Operation... operation) {
			this.operation = operation;
			return this;
		}

		/**
		 * Sets the container type.
		 *
		 * @param containerType the container type
		 * @return this builder
		 */
		@Nonnull
		public Builder containerType(@Nullable ContainerType... containerType) {
			this.containerType = containerType;
			return this;
		}

		/**
		 * Builds the {@link DataSite} record.
		 *
		 * @return the {@link DataSite} record
		 */
		@Nonnull
		public SchemaSite build() {
			return new SchemaSite(
				this.entityType,
				this.operation,
				this.containerType
			);
		}
	}

}
