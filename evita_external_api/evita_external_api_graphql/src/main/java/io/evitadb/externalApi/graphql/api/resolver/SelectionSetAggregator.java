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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Wrapper for {@link DataFetchingFieldSelectionSet} that filters original fields only to fields for specific entity DTO
 * object type. On top of that, it can operate on multiple sets at once.
 * This is useful for building Evita query requirements from GraphQL fields containing different fields for multiple
 * entity types (e.g. in unknown entity query).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SelectionSetAggregator {

	private static final String TYPENAME_FIELD = "__typename";
	private static final String FIELD_NAME_PATTERN_WILDCARD = "*";
	private static final List<SelectedField> EMPTY_LIST = List.of();

	@Nullable private final DataFetchingFieldSelectionSet originalSelectionSet;
	@Nullable private final List<DataFetchingFieldSelectionSet> originalSelectionSets;

	/**
	 * Creates wrapper without filtering of fields. Functions same as original {@link DataFetchingFieldSelectionSet}.
	 */
	public static SelectionSetAggregator from(@Nonnull DataFetchingFieldSelectionSet originalSelectionSet) {
		return new SelectionSetAggregator(originalSelectionSet, null);
	}

	/**
	 * Creates wrapper without filtering of fields. Functions same as original {@link DataFetchingFieldSelectionSet}.
	 */
	public static SelectionSetAggregator from(@Nonnull List<DataFetchingFieldSelectionSet> originalSelectionSets) {
		return new SelectionSetAggregator(null, originalSelectionSets);
	}

	/**
	 * Whether there is at least one field that matches the given pattern. The pattern can be either exact field name or
	 * field name with `*` wildcard at the end to match all fields starting with the pattern.
	 */
	public boolean containsImmediate(@Nonnull String fieldNamePattern) {
		if (originalSelectionSet != null) {
			return containsImmediate(fieldNamePattern, originalSelectionSet);
		} else {
			return containsImmediate(fieldNamePattern, originalSelectionSets);
		}
	}

	/**
	 * Returns all immediate fields.
	 */
	@Nonnull
	public List<SelectedField> getImmediateFields() {
		if (originalSelectionSet != null) {
			return getImmediateFields(originalSelectionSet);
		} else {
			return getImmediateFields(originalSelectionSets);
		}
	}

	/**
	 * Returns all immediate fields with given name.
	 */
	@Nonnull
	public List<SelectedField> getImmediateFields(@Nonnull String fieldName) {
		if (originalSelectionSet != null) {
			return getImmediateFields(fieldName, originalSelectionSet);
		} else {
			return getImmediateFields(fieldName, originalSelectionSets);
		}
	}

	/**
	 * Returns all immediate fields with given name.
	 */
	@Nonnull
	public List<SelectedField> getImmediateFields(@Nonnull Set<String> fieldNames) {
		if (originalSelectionSet != null) {
			return getImmediateFields(fieldNames, originalSelectionSet);
		} else {
			return getImmediateFields(fieldNames, originalSelectionSets);
		}
	}

	/**
	 * Whether there are no fields.
	 */
	public boolean isEmpty() {
		if (originalSelectionSet != null) {
			return isEmpty(originalSelectionSet);
		} else {
			return isEmpty(originalSelectionSets);
		}
	}


	/**
	 * Whether there is at least one field that matches the given pattern. The pattern can be either exact field name or
	 * field name with `*` wildcard at the end to match all fields starting with the pattern.
	 */
	public static boolean containsImmediate(@Nonnull String fieldNamePattern, @Nonnull DataFetchingFieldSelectionSet selectionSet) {
		final List<SelectedField> immediateFields = selectionSet.getImmediateFields();
		if (fieldNamePattern.endsWith(FIELD_NAME_PATTERN_WILDCARD)) {
			final String normalizedPattern = fieldNamePattern.substring(0, fieldNamePattern.length() - 1);
			for (SelectedField field : immediateFields) {
				if (field.getName().startsWith(normalizedPattern)) {
					return true;
				}
			}
		} else {
			for (SelectedField field : immediateFields) {
				if (field.getName().equals(fieldNamePattern)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether there is at least one field that matches the given pattern. The pattern can be either exact field name or
	 * field name with `*` wildcard at the end to match all fields starting with the pattern.
	 */
	public static boolean containsImmediate(@Nonnull String fieldNamePattern, @Nonnull List<DataFetchingFieldSelectionSet> selectionSets) {
		if (selectionSets.isEmpty()) {
			return false;
        } else if (selectionSets.size() == 1) {
			// this should not be really used, there is different variant of same method for single set, but just in case
			// someone forgets, this should prevent going with full list iteration
			return containsImmediate(fieldNamePattern, selectionSets.get(0));
		} else {
			if (fieldNamePattern.endsWith(FIELD_NAME_PATTERN_WILDCARD)) {
				final String normalizedPattern = fieldNamePattern.substring(0, fieldNamePattern.length() - 1);
				for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
					for (SelectedField field : selectionSet.getImmediateFields()) {
						if (field.getName().startsWith(normalizedPattern)) {
							return true;
						}
					}
				}
			} else {
				for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
					for (SelectedField field : selectionSet.getImmediateFields()) {
						if (field.getName().equals(fieldNamePattern)) {
							return true;
						}
					}
				}
			}
			return false;
		}
	}

	/**
	 * Returns all immediate fields.
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull DataFetchingFieldSelectionSet selectionSet) {
		final List<SelectedField> matchingFields = new ArrayList<>(selectionSet.getImmediateFields().size());
		for (SelectedField field : selectionSet.getImmediateFields()) {
			if (!field.getName().equals(TYPENAME_FIELD)) {
				matchingFields.add(field);
			}
		}
		return Collections.unmodifiableList(matchingFields);
	}

	/**
	 * Returns all immediate fields.
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull List<DataFetchingFieldSelectionSet> selectionSets) {
		final List<SelectedField> matchingFields = new LinkedList<>();
		for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
			for (SelectedField field : selectionSet.getImmediateFields()) {
				if (!field.getName().equals(TYPENAME_FIELD)) {
					matchingFields.add(field);
				}
			}
		}
		return Collections.unmodifiableList(matchingFields);
	}

	/**
	 * Returns all immediate fields with given name.
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull String fieldName, @Nonnull DataFetchingFieldSelectionSet selectionSet) {
		final List<SelectedField> matchingFields = new LinkedList<>();
		for (SelectedField field : selectionSet.getImmediateFields()) {
			if (field.getName().equals(fieldName)) {
				matchingFields.add(field);
			}
		}
		return Collections.unmodifiableList(matchingFields);
	}

	/**
	 * Returns all immediate fields with given name.s
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull Set<String> fieldNames, @Nonnull DataFetchingFieldSelectionSet selectionSet) {
		final List<SelectedField> matchingFields = new LinkedList<>();
		for (SelectedField field : selectionSet.getImmediateFields()) {
			if (fieldNames.contains(field.getName())) {
				matchingFields.add(field);
			}
		}
		return Collections.unmodifiableList(matchingFields);
	}

	/**
	 * Returns all immediate fields with given name.
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull String fieldName, @Nonnull List<DataFetchingFieldSelectionSet> selectionSets) {
		// this categorization is there just to get a little bit better performance for simple cases
		if (selectionSets.isEmpty()) {
			return EMPTY_LIST;
		} else if (selectionSets.size() == 1) {
			// this should not be really used, there is different variant of same method for single set, but just in case
			// someone forgets, this should prevent going with full list iteration
			return getImmediateFields(fieldName, selectionSets.get(0));
		} else {
			final List<SelectedField> matchingFields = new LinkedList<>();
			for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
				for (SelectedField field : selectionSet.getImmediateFields()) {
					if (field.getName().equals(fieldName)) {
						matchingFields.add(field);
					}
				}
			}
			return Collections.unmodifiableList(matchingFields);
		}
	}

	/**
	 * Returns all immediate fields with given name.
	 */
	@Nonnull
	public static List<SelectedField> getImmediateFields(@Nonnull Set<String> fieldNames, @Nonnull List<DataFetchingFieldSelectionSet> selectionSets) {
		// this categorization is there just to get a little bit better performance for simple cases
		if (selectionSets.isEmpty()) {
			return EMPTY_LIST;
		} else if (selectionSets.size() == 1) {
			// this should not be really used, there is different variant of same method for single set, but just in case
			// someone forgets, this should prevent going with full list iteration
			return getImmediateFields(fieldNames, selectionSets.get(0));
		} else {
			final List<SelectedField> matchingFields = new LinkedList<>();
			for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
				for (SelectedField field : selectionSet.getImmediateFields()) {
					if (fieldNames.contains(field.getName())) {
						matchingFields.add(field);
					}
				}
			}
			return Collections.unmodifiableList(matchingFields);
		}
	}

	/**
	 * Whether there are no fields.
	 */
	public static boolean isEmpty(@Nonnull DataFetchingFieldSelectionSet selectionSet) {
		return selectionSet.getImmediateFields().isEmpty();
	}

	/**
	 * Whether there are no fields.
	 */
	public static boolean isEmpty(@Nonnull List<DataFetchingFieldSelectionSet> selectionSets) {
		if (selectionSets.isEmpty()) {
			return true;
		} else if (selectionSets.size() == 1) {
			// this should not be really used, there is different variant of same method for single set, but just in case
			// someone forgets, this should prevent going with full list iteration
			return isEmpty(selectionSets.get(0));
		} else {
			for (DataFetchingFieldSelectionSet selectionSet : selectionSets) {
				if (!isEmpty(selectionSet)) {
					return false;
				}
			}
			return true;
		}
	}

}
