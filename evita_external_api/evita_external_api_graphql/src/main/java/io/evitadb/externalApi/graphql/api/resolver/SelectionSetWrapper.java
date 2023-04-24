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

package io.evitadb.externalApi.graphql.api.resolver;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Predicate;

/**
 * Wrapper for {@link DataFetchingFieldSelectionSet} that filters original fields only to fields for specific entity DTO
 * object type. On top of that, it can operate on multiple sets at once.
 * This is useful for building Evita query requirements from GraphQL fields containing different fields for multiple
 * entity types (e.g. in unknown entity query).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SelectionSetWrapper {

	private static final String TYPENAME_FIELD = "__typename";

	@Nonnull
	private final List<DataFetchingFieldSelectionSet> originalSelectionSets;
	@Nonnull
	private final Predicate<SelectedField> fieldPredicate;

	/**
	 * Creates wrapper without filtering of fields. Functions same as original {@link DataFetchingFieldSelectionSet}.
	 */
	public static SelectionSetWrapper from(@Nonnull DataFetchingFieldSelectionSet originalSelectionSet) {
		return new SelectionSetWrapper(
			List.of(originalSelectionSet),
			field -> true
		);
	}

	/**
	 * Creates wrapper without filtering of fields. Functions same as original {@link DataFetchingFieldSelectionSet}.
	 */
	public static SelectionSetWrapper from(@Nonnull List<DataFetchingFieldSelectionSet> originalSelectionSets) {
		return new SelectionSetWrapper(
			originalSelectionSets,
			field -> true
		);
	}

	/**
	 * Creates wrapper with filtering of fields of original {@link DataFetchingFieldSelectionSet} to fields for
	 * {@code entityDtoObjectTypeName}.
	 */
	public static SelectionSetWrapper from(@Nonnull DataFetchingFieldSelectionSet originalSelectionSet, @Nonnull String entityDtoObjectTypeName) {
		return new SelectionSetWrapper(
			List.of(originalSelectionSet),
			field -> field.getObjectTypeNames().contains(entityDtoObjectTypeName)
		);
	}

	/**
	 * Creates wrapper with filtering of fields of original {@link DataFetchingFieldSelectionSet} to fields for
	 * {@code entityDtoObjectTypeName}.
	 */
	public static SelectionSetWrapper from(@Nonnull List<DataFetchingFieldSelectionSet> originalSelectionSets, @Nonnull String entityDtoObjectTypeName) {
		return new SelectionSetWrapper(
			originalSelectionSets,
			field -> field.getObjectTypeNames().contains(entityDtoObjectTypeName)
		);
	}

	/**
	 * Same as {@link DataFetchingFieldSelectionSet#contains(String)} only with filtering logic.
	 */
	public boolean contains(@Nonnull String fieldGlobPattern) {
		return originalSelectionSets
			.stream()
			.flatMap(ss -> ss.getFields(fieldGlobPattern).stream())
			.filter(f -> !f.getName().equals(TYPENAME_FIELD))
			.anyMatch(fieldPredicate);
	}

	/**
	 * Same as {@link DataFetchingFieldSelectionSet#containsAnyOf(String, String...) only with filtering logic.
	 */
	public boolean containsAnyOf(@Nonnull String fieldGlobPattern, @Nonnull String... fieldGlobPatterns) {
		return originalSelectionSets
			.stream()
			.flatMap(ss -> ss.getFields(fieldGlobPattern, fieldGlobPatterns).stream())
			.filter(f -> !f.getName().equals(TYPENAME_FIELD))
			.anyMatch(fieldPredicate);
	}

	/**
	 * Same as {@link DataFetchingFieldSelectionSet#getFields()} only with filtering logic.
	 */
	public List<SelectedField> getFields(@Nonnull String fieldGlobPattern, @Nonnull String... fieldGlobPatterns) {
		return originalSelectionSets
			.stream()
			.flatMap(ss -> ss.getFields(fieldGlobPattern, fieldGlobPatterns).stream())
			.filter(f -> !f.getName().equals(TYPENAME_FIELD))
			.filter(fieldPredicate)
			.toList();
	}

	/**
	 * Whether there are no fields.
	 */
	public boolean isEmpty() {
		return originalSelectionSets.isEmpty() ||
			originalSelectionSets.stream().mapToLong(ss -> ss.getFields().size()).sum() == 0;
	}
}
