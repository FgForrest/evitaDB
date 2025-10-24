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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Exception is thrown when the {@link ReferenceSchemaContract#getCardinality()} is violated when upserting an entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceCardinalityViolatedException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -2457165966707036900L;

	public record CardinalityViolation(
		@Nonnull String referenceName,
		@Nonnull Cardinality cardinality,
		int realCount,
		boolean duplicate
	) {}

	public ReferenceCardinalityViolatedException(@Nonnull String entityName, @Nonnull Collection<CardinalityViolation> violations) {
		super(constructErrorMessage(entityName, violations));
	}

	/**
	 * Constructs an error message detailing the violations of expected reference cardinalities for a given entity.
	 *
	 * @param entityName the name of the entity where the cardinality violations occurred
	 * @param violations a collection of {@link CardinalityViolation} objects describing the violated references,
	 *                   expected cardinalities, actual occurrences, and whether duplicates are present
	 * @return a string describing the violations in a human-readable format
	 */
	@Nonnull
	private static String constructErrorMessage(
		@Nonnull String entityName,
		@Nonnull Collection<CardinalityViolation> violations
	) {
		return "Expected reference cardinalities are violated in entity `" + entityName + "`: " +
			violations.stream()
				.map(
					it -> "reference `" + it.referenceName() +
						"` is expected to be `" + it.cardinality() +
						"` - but entity contains " + it.realCount() + " " +
						(it.duplicate ? "duplicated " : "") + "references"
				)
				.collect(Collectors.joining(", ")) + ".";
	}
}
