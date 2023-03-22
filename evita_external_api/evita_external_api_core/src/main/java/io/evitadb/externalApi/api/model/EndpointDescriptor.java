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
import io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * API-independent descriptor of single endpoint (query, mutation, ...) in schema-based external APIs.
 *
 * @param operation name of operation this endpoint will provide. Supported is either prefix or prefix + suffix separated by wildcard
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

	private static final String OPERATION_NAME_WILDCARD = "*";

	public EndpointDescriptor {
		Assert.isPremiseValid(
			!operation.isEmpty(),
			() -> new ExternalApiInternalError("Operation of endpoint cannot be empty.")
		);
		Assert.isPremiseValid(
			!operation.startsWith(OPERATION_NAME_WILDCARD),
			() -> new ExternalApiInternalError("Operation name cannot start with wildcard.")
		);
		Assert.isPremiseValid(
			!operation.endsWith(OPERATION_NAME_WILDCARD),
			() -> new ExternalApiInternalError("Operation name cannot end with wildcard.")
		);
		Assert.isPremiseValid(
			operation.indexOf(OPERATION_NAME_WILDCARD) == operation.lastIndexOf(OPERATION_NAME_WILDCARD),
			() -> new ExternalApiInternalError("Operation name supports only one wildcard.")
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
		if (hasClassifier()) {
			return constructOperationName(classifier(FIELD_NAME_NAMING_CONVENTION), null);
		} else {
			Assert.isPremiseValid(
				!operation.contains(OPERATION_NAME_WILDCARD),
				() -> new ExternalApiInternalError("Operation `" + operation + "` contains wildcard, and thus needs classifier")
			);
		}
		return operation;
	}

	/**
	 * Returns operation name. If static classifier is specified, it is appended as suffix to operation name.
	 */
	@Nonnull
	public String operation(@Nonnull String suffix) {
		if (hasClassifier()) {
			return constructOperationName(classifier(FIELD_NAME_NAMING_CONVENTION), suffix);
		} else {
			Assert.isPremiseValid(
				!operation.contains(OPERATION_NAME_WILDCARD),
				() -> new ExternalApiInternalError("Operation `" + operation + "` contains wildcard, and thus needs classifier")
			);
		}
		return constructOperationName(suffix);
	}


	/**
	 * Returns operation name suffixed with schema name
	 */
	@Nonnull
	public String operation(@Nonnull NamedSchemaContract schema) {
		return operation(schema, null);
	}

	/**
	 * Returns operation name suffixed with schema name and custom suffix
	 */
	@Nonnull
	public String operation(@Nonnull NamedSchemaContract schema, @Nullable String suffix) {
		Assert.isPremiseValid(
			!hasClassifier(),
			() -> new ExternalApiInternalError("Endpoint `" + operation + "` has static classifier, cannot use dynamic one.")
		);

		return constructOperationName(schema.getNameVariant(FIELD_NAME_NAMING_CONVENTION), suffix);
	}

	/**
	 * Returns operation name with possibly custom suffix
	 */
	@Nonnull
	private String constructOperationName(@Nullable String customSuffix) {
		if (customSuffix != null) {
			return operation + CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER + customSuffix;
		}
		return operation;
	}

	/**
	 * Returns operation name with custom classifier and possibly custom suffix
	 */
	@Nonnull
	private String constructOperationName(@Nonnull String customClassifier, @Nullable String customSuffix) {
		if (operation.contains(OPERATION_NAME_WILDCARD)) {
			Assert.isPremiseValid(
				customSuffix == null,
				() -> new ExternalApiInternalError("Custom operation suffix is supported only when no implicit suffix is defined.")
			);
			final String[] operationParts = operation.split("\\" + OPERATION_NAME_WILDCARD);
			return operationParts[0] + CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER + customClassifier + CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER + operationParts[1];
		} else {
			String operation = this.operation + CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER + customClassifier;
			if (customSuffix != null) {
				operation += CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER + customSuffix;
			}
			return operation;
		}
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
