/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.model;

import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * API-independent descriptor of single endpoint (query, mutation, ...) in schema-based external APIs.
 *
 * @param operation name of operation this endpoint will provide
 * @param urlPathItem how is the operation name presented in URL (if used by target API)
 * @param classifier name of classifier to locate data this endpoint will work with
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Builder
public record EndpointDescriptor(@Nonnull String operation,
								 @Nullable String urlPathItem,
                                 @Nullable String classifier,
                                 @Nonnull String description,
                                 @Nullable PropertyDataTypeDescriptor type) {

	public EndpointDescriptor {
		Assert.isPremiseValid(
			!operation.isEmpty(),
			() -> new ExternalApiInternalError("Operation of endpoint cannot be empty.")
		);
		if (classifier() != null) {
			Assert.isPremiseValid(
				!classifier.isEmpty(),
				() -> new ExternalApiInternalError("Classifier of endpoint can be missing but cannot be empty.")
			);
		}
		Assert.isPremiseValid(
			!description.isEmpty(),
			() -> new ExternalApiInternalError("Description of endpoint `" + classifier() + "` cannot be empty.")
		);
	}

	/**
	 * Returns operation name. If static classifier is specified, it is appended as suffix to operation name.
	 */
	@Nonnull
	public String operation() {
		if (classifier() != null) {
			return String.join(CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER, operation, classifier(FIELD_NAME_NAMING_CONVENTION));
		}
		return operation;
	}

	/**
	 * Returns operation name suffixed with schema name
	 */
	@Nonnull
	public String operation(@Nonnull NamedSchemaContract schema) {
		Assert.isPremiseValid(
			!hasClassifier(),
			() -> new ExternalApiInternalError("Endpoint `" + operation + "` has static classifier, cannot use dynamic one.")
		);

		return String.join(CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER, operation, schema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
	}

	@Nonnull
	public String urlPathItem() {
		Assert.isPremiseValid(
			urlPathItem != null,
			() -> new ExternalApiInternalError("URL path item of endpoint is missing.")
		);
		return urlPathItem;
	}

	@Nullable
	public String classifier(@Nonnull NamingConvention namingConvention) {
		if (classifier() == null) {
			return null;
		}
		return StringUtils.toSpecificCase(classifier(), namingConvention);
	}

	public boolean hasClassifier() {
		return classifier != null;
	}

	@Nonnull
	public String description(@Nonnull Object... args) {
		return String.format(this.description, args);
	}

	@Nullable
	public PrimitivePropertyDataTypeDescriptor primitiveType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof PrimitivePropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not primitive.")
		);
		return (PrimitivePropertyDataTypeDescriptor) type();
	}

	@Nullable
	public ObjectPropertyDataTypeDescriptor objectType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof ObjectPropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not object.")
		);
		return (ObjectPropertyDataTypeDescriptor) type();
	}

	/**
	 * Transform this generic endpoint descriptor to API-specific schema definition.
	 */
	public <T> T to(@Nonnull EndpointDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}
}
