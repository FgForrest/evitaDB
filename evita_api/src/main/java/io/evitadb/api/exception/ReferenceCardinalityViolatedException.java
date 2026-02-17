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
 * Exception thrown when an entity upsert operation violates the cardinality constraints defined
 * by {@link ReferenceSchemaContract#getCardinality()} for one or more references.
 *
 * Reference cardinality specifies how many instances of a reference are allowed on an entity:
 *
 * - **ZERO_OR_ONE**: At most one reference instance (0 or 1)
 * - **EXACTLY_ONE**: Exactly one reference instance required (1)
 * - **ZERO_OR_MORE**: Any number of references allowed (0, 1, 2, ...)
 * - **ONE_OR_MORE**: At least one reference instance required (1, 2, ...)
 *
 * This exception is raised during entity mutation validation when the number of references being
 * set does not satisfy the cardinality constraint. For example:
 *
 * - Setting two references when cardinality is ZERO_OR_ONE
 * - Removing all references when cardinality is EXACTLY_ONE or ONE_OR_MORE
 * - Having duplicate references when duplicates are not allowed by the schema
 *
 * The exception contains a collection of {@link CardinalityViolation} records, each describing a
 * specific violation including the reference name, expected cardinality, actual count, and whether
 * duplicates are involved.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceCardinalityViolatedException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -2457165966707036900L;

	/**
	 * Represents a single violation of reference cardinality constraints, capturing all details
	 * needed to explain the validation failure.
	 *
	 * @param referenceName the name of the reference whose cardinality was violated
	 * @param cardinality   the expected cardinality constraint from the schema
	 * @param realCount     the actual number of reference instances found on the entity
	 * @param duplicate     whether the violation involves duplicate references (multiple references
	 *                      to the same entity when duplicates are not allowed)
	 */
	public record CardinalityViolation(
		@Nonnull String referenceName,
		@Nonnull Cardinality cardinality,
		int realCount,
		boolean duplicate
	) {
	}

	/**
	 * Constructs a new exception describing one or more cardinality violations for an entity.
	 *
	 * @param entityName the type name of the entity with cardinality violations
	 * @param violations collection of violations, one for each reference that failed validation
	 */
	public ReferenceCardinalityViolatedException(
		@Nonnull String entityName, @Nonnull Collection<CardinalityViolation> violations) {
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
