/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.schema.mutation;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Internal utility interface providing support for conditional mutation creation based on the difference between two
 * schema versions. This interface is used by "create" mutations (e.g., {@link CreateAttributeSchemaMutation},
 * {@link CreateReferenceSchemaMutation}) to generate delta mutations that represent only the differences between a
 * newly created schema and an existing schema.
 *
 * **Purpose**
 *
 * When a schema is created via builder API and compared against an existing schema, we need to generate mutations that
 * represent only the properties that differ. This interface provides a reusable pattern for comparing property values
 * between two schema versions and conditionally creating a mutation only when the values differ.
 *
 * **Usage Pattern**
 *
 * Implementing classes call {@link #makeMutationIfDifferent(Class, Object, Object, Function, Function)} for each
 * property that should be compared. The method retrieves the property value from both the created version and the
 * existing version, compares them, and creates a mutation only if they differ. This is commonly used in methods like
 * {@link CreateAttributeSchemaMutation#makeMutationArguments(Object, Object)}.
 *
 * **Array Comparison**
 *
 * The method includes special handling for array properties. When both the new value and existing value are arrays,
 * the method uses {@link java.util.Arrays#equals(Object[], Object[])} for deep equality comparison rather than
 * {@link Object#equals(Object)}.
 *
 * **Implementors**
 *
 * This interface is implemented by create mutation classes:
 *
 * - {@link CreateAttributeSchemaMutation}
 * - {@link CreateGlobalAttributeSchemaMutation}
 * - {@link CreateReferenceSchemaMutation}
 * - {@link CreateAssociatedDataSchemaMutation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CreateMutation {

	/**
	 * Compares a property value retrieved from the created schema version and the existing schema version using the
	 * provided property retriever function. If the values are different, creates a mutation using the mutation creator
	 * function. If the values are equal, returns null (no mutation needed).
	 *
	 * This method handles both simple object properties and array properties. For arrays, it uses
	 * {@link java.util.Arrays#equals(Object[], Object[])} for deep equality comparison.
	 *
	 * @param mutationType      the class type of the schema being compared (e.g., `AttributeSchemaContract.class`);
	 *                          used to avoid type ambiguity in method signatures
	 * @param createdVersion    the newly created schema version from which properties are retrieved for comparison
	 * @param existingVersion   the current (existing) schema version from which properties are retrieved for
	 *                          comparison
	 * @param propertyRetriever a function that extracts the property value from a schema version (e.g.,
	 *                          `AttributeSchemaContract::isFilterable`)
	 * @param mutationCreator   a function that creates a mutation from the new property value (e.g.,
	 *                          `SetAttributeSchemaFilterableMutation::new`)
	 * @param <T>               the type of the property value being compared (e.g., `Boolean`, `String[]`)
	 * @param <S>               the type of the schema being compared (e.g., `AttributeSchemaContract`)
	 * @param <V>               the type of the mutation to be created (e.g., `SetAttributeSchemaFilterableMutation`)
	 * @return a mutation of type `V` if the property values differ, or null if they are equal
	 */
	@Nullable
	default <T, S, V> V makeMutationIfDifferent(
		@Nonnull Class<S> mutationType,
		@Nonnull S createdVersion,
		@Nonnull S existingVersion,
		@Nonnull Function<S, T> propertyRetriever,
		@Nonnull Function<T, V> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		final T existingValue = propertyRetriever.apply(existingVersion);
		if (newValue instanceof Object[] && existingValue instanceof Object[]) {
			return Arrays.equals((Object[]) newValue, (Object[]) existingValue) ? null : mutationCreator.apply(
				newValue);
		} else {
			return Objects.equals(existingValue, newValue) ? null : mutationCreator.apply(newValue);
		}
	}

}
