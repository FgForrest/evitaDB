/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.spi.analysis;

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Describes a named analysis parameter for validation and generated typed API inputs.
 *
 * @param name parameter name
 * @param type accepted Java value type
 * @param required whether omission is invalid
 * @param description human-readable purpose and restrictions
 * @param resourceKind the kind of resource named by this string or string-array parameter, or `null` for plain values;
 *                     a compiled step may read only resources explicitly named by its own resource parameters, and an
 *                     index-side one contributes the content of the resources it names to the fingerprint, never
 *                     their names
 */
public record AnalysisStepParameterDescriptor(
	@Nonnull String name,
	@Nonnull Class<? extends Serializable> type,
	boolean required,
	@Nonnull String description,
	@Nullable FulltextResourceKind resourceKind
) {
	/**
	 * Refuses resource metadata on a parameter that cannot hold resource names.
	 *
	 * @param name parameter name
	 * @param type accepted Java value type
	 * @param required whether omission is invalid
	 * @param description human-readable purpose and restrictions
	 * @param resourceKind the resource kind named by the value, or `null` for a plain value
	 * @throws EvitaInvalidUsageException when a resource kind is declared for a type other than `String` or `String[]`
	 */
	public AnalysisStepParameterDescriptor {
		Assert.isTrue(
			resourceKind == null || type == String.class || type == String[].class,
			"A resource parameter must have type String or String[]."
		);
	}

	/**
	 * Describes a parameter whose value does not name resources.
	 *
	 * @param name parameter name
	 * @param type accepted Java value type
	 * @param required whether omission is invalid
	 * @param description human-readable purpose and restrictions
	 */
	public AnalysisStepParameterDescriptor(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		boolean required,
		@Nonnull String description
	) {
		this(name, type, required, description, null);
	}
}
