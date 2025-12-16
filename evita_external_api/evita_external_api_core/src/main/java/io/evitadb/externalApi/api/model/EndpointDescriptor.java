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

package io.evitadb.externalApi.api.model;

import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_PART_NAMING_CONVENTION;

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

		if (classifier != null) {
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
		return operation((String) null);
	}

	/**
	 * Returns operation name. If static classifier is specified, it is appended as suffix to operation name.
	 */
	@Nonnull
	public String operation(@Nullable String suffix) {
		if (hasClassifier()) {
			return constructOperationName(classifier(PROPERTY_NAME_NAMING_CONVENTION), suffix);
		} else {
			Assert.isPremiseValid(
				!this.operation.contains(OPERATION_NAME_WILDCARD),
				() -> new ExternalApiInternalError("Operation `" + this.operation + "` contains wildcard, and thus needs classifier")
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
			() -> new ExternalApiInternalError("Endpoint `" + this.operation + "` has static classifier, cannot use dynamic one.")
		);

		return constructOperationName(schema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION), suffix);
	}

	/**
	 * Returns operation name with possibly custom suffix
	 */
	@Nonnull
	private String constructOperationName(@Nullable String customSuffix) {
		if (customSuffix != null) {
			return this.operation + StringUtils.toSpecificCase(customSuffix, PROPERTY_NAME_PART_NAMING_CONVENTION);
		}
		return this.operation;
	}

	/**
	 * Returns operation name with custom classifier and possibly custom suffix
	 */
	@Nonnull
	private String constructOperationName(@Nonnull String customClassifier, @Nullable String customSuffix) {
		if (this.operation.contains(OPERATION_NAME_WILDCARD)) {
			Assert.isPremiseValid(
				customSuffix == null,
				() -> new ExternalApiInternalError("Custom operation suffix is supported only when no implicit suffix is defined.")
			);
			final String[] operationParts = this.operation.split("\\" + OPERATION_NAME_WILDCARD);
			return operationParts[0] +
				StringUtils.toSpecificCase(customClassifier, PROPERTY_NAME_PART_NAMING_CONVENTION) +
				StringUtils.toSpecificCase(operationParts[1], PROPERTY_NAME_PART_NAMING_CONVENTION);
		} else {
			String operation = this.operation + StringUtils.toSpecificCase(customClassifier, PROPERTY_NAME_PART_NAMING_CONVENTION);
			if (customSuffix != null) {
				operation += StringUtils.toSpecificCase(customSuffix, PROPERTY_NAME_PART_NAMING_CONVENTION);
			}
			return operation;
		}
	}

	@Nonnull
	public String urlPathItem() {
		Assert.isPremiseValid(
			this.urlPathItem != null,
			() -> new ExternalApiInternalError("URL path item of endpoint is missing.")
		);
		return this.urlPathItem;
	}

	/**
	 * Returns classifier in specified naming convention. If classifier is not defined, throw error.
	 */
	@Nonnull
	public String classifier(@Nonnull NamingConvention namingConvention) {
		if (classifier() == null) {
			throw new NullPointerException("Classifier is not defined.");
		}
		return StringUtils.toSpecificCase(classifier(), namingConvention);
	}

	public boolean hasClassifier() {
		return this.classifier != null;
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
	public TypePropertyDataTypeDescriptor objectType() {
		if (type() == null) {
			return null;
		}
		Assert.isPremiseValid(
			type() instanceof TypePropertyDataTypeDescriptor,
			() -> new ExternalApiInternalError("Type is not object.")
		);
		return (TypePropertyDataTypeDescriptor) type();
	}

	/**
	 * Transform this generic endpoint descriptor to API-specific schema definition.
	 */
	public <T> T to(@Nonnull EndpointDescriptorTransformer<T> transformer) {
		return transformer.apply(this);
	}
}
