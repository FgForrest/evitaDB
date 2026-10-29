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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Server extension point for an analysis operation, discovered once through `ServiceLoader`.
 * Implementations must be thread-safe and expose stable names and immutable parameter descriptors.
 */
public interface AnalysisStepFactory {
	/**
	 * Identifies this provider in persisted step descriptions. Names must remain stable across server releases;
	 * duplicate names are refused during registry discovery.
	 *
	 * @return nonblank provider name used by schema declarations
	 */
	@Nonnull
	String getName();

	/**
	 * Describes the accepted parameter names, Java value types, required flags and user-facing restrictions.
	 * External APIs use the same metadata to build typed inputs, so the returned descriptors must be immutable.
	 *
	 * @return complete parameter metadata, empty for a parameterless operation
	 */
	@Nonnull
	List<AnalysisStepParameterDescriptor> getParameters();

	/**
	 * Validates parameter names, types and values without creating an operation.
	 *
	 * @param parameters declared parameters
	 * @throws EvitaInvalidUsageException with a comprehensible explanation of invalid parameters
	 */
	void validate(@Nonnull Map<String, Serializable> parameters) throws EvitaInvalidUsageException;
}
