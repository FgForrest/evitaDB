/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ConflictResolutionDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared input/output conversion logic for the nested {@link ConflictResolution} value object carried by several
 * mutation converters (catalog-create, catalog conflict-resolution modify and entity conflict-resolution modify).
 *
 * The {@link ConflictResolution} value object is not a mutation and cannot be built (nor serialized) by the reflective
 * converter path: it declares two public constructors and exposes a mutable {@link EnumSet} field, both of which the
 * reflective path cannot handle. Without the explicit serialization the reflective {@code convertToOutput} recurses into
 * the record and throws, which surfaces as a 500 when a mutation carrying a non-null conflict resolution is observed
 * through the change-capture API. Both directions are therefore implemented here explicitly and reused by every
 * converter that embeds a {@link ConflictResolution}, so the logic lives in exactly one place.
 *
 * This support lives in the external-API layer rather than on {@link ConflictResolution} itself, because the conversion
 * depends on the {@link Input}/{@link Output}/{@link ConflictResolutionDescriptor} external-API types; moving it onto the
 * {@code evita_api} value object would invert the module dependency.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictResolutionMutationConverterSupport {

	private ConflictResolutionMutationConverterSupport() {
		throw new UnsupportedOperationException("This class cannot be instantiated.");
	}

	/**
	 * Parses a nested {@link ConflictResolution} object from the raw input value. The coarse {@code policy} is required
	 * while the {@code granularity} defaults to an empty set when omitted.
	 *
	 * @param parentInput      the input the nested object was read from, used to inherit the parsing context
	 * @param rawValue         the raw nested object value to parse
	 * @param mutationName     the enclosing mutation name, used for error reporting on the nested input
	 * @param exceptionFactory the factory used to raise validation errors on the nested input
	 * @return the parsed {@link ConflictResolution}
	 */
	@Nonnull
	public static ConflictResolution parseConflictResolution(
		@Nonnull Input parentInput,
		@Nonnull Object rawValue,
		@Nonnull String mutationName,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		final Input nestedInput = Input.from(parentInput, mutationName, rawValue, exceptionFactory);
		final ConflictPolicy policy = nestedInput.getProperty(ConflictResolutionDescriptor.POLICY);
		final GranularConflictPolicy[] granularity = nestedInput.getProperty(
			ConflictResolutionDescriptor.GRANULARITY,
			new GranularConflictPolicy[0]
		);
		return new ConflictResolution(
			policy,
			granularity.length == 0
				? EnumSet.noneOf(GranularConflictPolicy.class)
				: EnumSet.copyOf(Arrays.asList(granularity))
		);
	}

	/**
	 * Pre-serializes a {@link ConflictResolution} value object into the output as a nested object. This must be done
	 * before the reflection-based {@code super.convertToOutput()} because {@link ConflictResolution} is neither a
	 * supported serialization type nor buildable through the reflective converter path. The {@code granularity} key is
	 * always emitted (as an empty array when there is no refinement) so it satisfies the required read-back. When the
	 * conflict resolution is {@code null} nothing is written and the property stays absent.
	 *
	 * @param conflictResolution the value object to serialize, or {@code null} when the schema inherits it
	 * @param output             the output to write the nested object into
	 * @param propertyName       the name of the property under which the nested object is emitted
	 */
	public static void serializeConflictResolution(
		@Nullable ConflictResolution conflictResolution,
		@Nonnull Output output,
		@Nonnull String propertyName
	) {
		if (conflictResolution != null) {
			final Map<String, Object> serialized = new LinkedHashMap<>(2);
			serialized.put(ConflictResolutionDescriptor.POLICY.name(), conflictResolution.policy());
			serialized.put(
				ConflictResolutionDescriptor.GRANULARITY.name(),
				conflictResolution.granularity().toArray(new GranularConflictPolicy[0])
			);
			output.setProperty(propertyName, serialized);
		}
	}
}
