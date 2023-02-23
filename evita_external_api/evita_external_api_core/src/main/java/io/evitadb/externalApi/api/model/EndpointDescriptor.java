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

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * API-independent descriptor of single endpoint (query, mutation, ...) in schema-based external APIs.
 *
 * @param operation name of operation this endpoint will provide
 * @param classifier name of classifier to locate data this endpoint will work with
 * @param description can be parametrized with {@link String#format(String, Object...)} parameters
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Builder
public record EndpointDescriptor(@Nonnull String operation,
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

	@Nonnull
	public String operation(@Nonnull NamingConvention namingConvention) {
		return StringUtils.toSpecificCase(operation(), namingConvention);
	}

	@Nullable
	public String classifier(@Nonnull NamingConvention namingConvention) {
		if (classifier() == null) {
			return null;
		}
		return StringUtils.toSpecificCase(classifier(), namingConvention);
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
