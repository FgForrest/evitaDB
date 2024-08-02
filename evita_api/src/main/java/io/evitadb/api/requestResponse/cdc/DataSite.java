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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.ContainerType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Record describing the location and form of the CDC event in the evitaDB that should be captured.
 *
 * @param entityType       the {@link EntitySchema#getName()} of the intercepted entity type
 * @param entityPrimaryKey the {@link EntityContract#getPrimaryKey()} of the intercepted entity
 * @param operation        the intercepted type of {@link Operation}
 * @param containerType    the intercepted {@link ContainerType} of the entity data
 * @param containerName   the intercepted name of the classifier
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record DataSite(
	@Nullable String entityType,
	@Nullable Integer entityPrimaryKey,
	@Nullable Operation[] operation,
	@Nullable ContainerType[] containerType,
	@Nullable String[] containerName
) implements CaptureSite {

	public static final CaptureSite ALL = new DataSite(null, null, null, null, null);

	/**
	 * Creates builder object that helps you create DataSite record using builder pattern.
	 * @return new instance of {@link Builder}
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder class for {@link DataSite}.
	 */
	public static class Builder {
		private String entityType;
		private Integer entityPrimaryKey;
		private Operation[] operation;
		private ContainerType[] containerType;
		private String[] containerName;

		/**
		 * Sets the entity type.
		 * @param entityType the entity type
		 * @return this builder
		 */
		@Nonnull
		public Builder entityType(@Nullable String entityType) {
			this.entityType = entityType;
			return this;
		}

		/**
		 * Sets the entity primary key.
		 * @param entityPrimaryKey the entity primary key
		 * @return this builder
		 */
		@Nonnull
		public Builder entityPrimaryKey(int entityPrimaryKey) {
			this.entityPrimaryKey = entityPrimaryKey;
			return this;
		}

		/**
		 * Sets the operation.
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
		 * @param containerType the container type
		 * @return this builder
		 */
		@Nonnull
		public Builder containerType(@Nullable ContainerType... containerType) {
			this.containerType = containerType;
			return this;
		}

		/**
		 * Sets the classifier name.
		 * @param containerName the classifier name
		 * @return this builder
		 */
		@Nonnull
		public Builder containerName(@Nullable String... containerName) {
			this.containerName = containerName;
			return this;
		}

		/**
		 * Builds the {@link DataSite} record.
		 * @return the {@link DataSite} record
		 */
		@Nonnull
		public DataSite build() {
			return new DataSite(entityType, entityPrimaryKey, operation, containerType, containerName);
		}

	}

}
