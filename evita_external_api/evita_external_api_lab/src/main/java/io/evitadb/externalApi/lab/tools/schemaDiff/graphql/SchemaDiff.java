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

package io.evitadb.externalApi.lab.tools.schemaDiff.graphql;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Holds all differences between two GraphQL schemas categorized by their severity.
 *
 * @param breakingChanges possible incompatible changes that may break existing clients
 * @param nonBreakingChanges compatible changes that should not break existing clients
 * @param unclassifiedChanges unknown changes that couldn't be classified
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public record SchemaDiff(@Nonnull Set<Change> breakingChanges,
                         @Nonnull Set<Change> nonBreakingChanges,
                         @Nonnull Set<Change> unclassifiedChanges) {

	/**
	 * A single change (difference) in the schema.
	 *
	 * @param type type of the change
	 * @param args arguments of the change describing what specifically has changed
	 */
	public record Change(@Nonnull ChangeType type, @Nonnull Object... args) {}

	/**
	 * Type of action of a change.
	 */
	public enum ActionType {
		ADDITION,
		REMOVAL,
		MODIFICATION,
		DEPRECATION,
		UNCLASSIFIED
	}

	/**
	 * Severity of a change.
	 */
	public enum Severity {
		/**
		 * Possible incompatible changes that may break existing clients
		 */
		BREAKING,
		/**
		 * Compatible changes that should not break existing clients
		 */
		NON_BREAKING,
		/**
		 * Unknown changes that couldn't be classified
		 */
		UNCLASSIFIED
	}

	/**
	 * Represents specific change in the schema.
	 *
	 * Inspired by https://www.apollographql.com/docs/graphos/delivery/schema-checks/#types-of-schema-changes
	 */
	@Getter
	@RequiredArgsConstructor
	public enum ChangeType {
		// Removals
		FIELD_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		TYPE_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		DIRECTIVE_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		ARG_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		TYPE_REMOVED_FROM_UNION(Severity.BREAKING, ActionType.REMOVAL),
		INPUT_FIELD_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		VALUE_REMOVED_FROM_ENUM(Severity.BREAKING, ActionType.REMOVAL),
		TYPE_REMOVED_FROM_INTERFACE(Severity.BREAKING, ActionType.REMOVAL),

		// Addition of required data
		ARG_ADDED(Severity.BREAKING, ActionType.ADDITION), // potentially breaking because it is potentially required
		NON_NULL_INPUT_FIELD_ADDED(Severity.BREAKING, ActionType.ADDITION),

		// In-place updates
		TYPE_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		DIRECTIVE_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		INPUT_FIELD_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		FIELD_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		FIELD_CHANGED_TYPE(Severity.BREAKING, ActionType.MODIFICATION),
		INPUT_FIELD_CHANGED_TYPE(Severity.BREAKING, ActionType.MODIFICATION),
		TYPE_CHANGED_KIND(Severity.BREAKING, ActionType.MODIFICATION),
		ARG_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		ARG_CHANGED_TYPE(Severity.BREAKING, ActionType.MODIFICATION),
		VALUE_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		APPLIED_DIRECTIVE_ADDED(ActionType.ADDITION),
		APPLIED_DIRECTIVE_REMOVED(Severity.BREAKING, ActionType.REMOVAL),
		APPLIED_DIRECTIVE_ARG_RENAMED(Severity.BREAKING, ActionType.MODIFICATION),
		APPLIED_DIRECTIVE_ARG_REMOVED(Severity.BREAKING, ActionType.REMOVAL),

		// Defaults
		INPUT_FIELD_DEFAULT_VALUE_CHANGE(Severity.BREAKING, ActionType.MODIFICATION),
		ARG_DEFAULT_VALUE_CHANGE(Severity.BREAKING, ActionType.MODIFICATION),
		APPLIED_DIRECTIVE_ARG_VALUE_CHANGED(Severity.BREAKING, ActionType.MODIFICATION),

		// Schema additions
		FIELD_ADDED(ActionType.ADDITION),
		TYPE_ADDED(ActionType.ADDITION),
		DIRECTIVE_ADDED(ActionType.ADDITION),
		VALUE_ADDED_TO_ENUM(ActionType.ADDITION),
		TYPE_ADDED_TO_UNION(ActionType.ADDITION),
		TYPE_ADDED_TO_INTERFACE(ActionType.ADDITION),
		OPTIONAL_ARG_ADDED(ActionType.ADDITION),
		NULLABLE_FIELD_ADDED_TO_INPUT_OBJECT(ActionType.ADDITION),

		// Deprecations
		FIELD_DEPRECATED(ActionType.DEPRECATION),
		FIELD_DEPRECATION_REMOVED(ActionType.DEPRECATION),
		FIELD_DEPRECATED_REASON_CHANGE(ActionType.DEPRECATION),
		ENUM_DEPRECATED(ActionType.DEPRECATION),
		ENUM_DEPRECATION_REMOVED(ActionType.DEPRECATION),
		ENUM_DEPRECATED_REASON_CHANGE(ActionType.DEPRECATION),

		// Descriptions
		TYPE_DESCRIPTION_CHANGE(ActionType.MODIFICATION),
		FIELD_DESCRIPTION_CHANGE(ActionType.MODIFICATION),
		ENUM_VALUE_DESCRIPTION_CHANGE(ActionType.MODIFICATION),
		ARG_DESCRIPTION_CHANGE(ActionType.MODIFICATION),

		// Unknown unsupported changes
		UNCLASSIFIED(Severity.UNCLASSIFIED, ActionType.UNCLASSIFIED);

		private final Severity severity;
		private final ActionType actionType;

		ChangeType(@Nonnull ActionType actionType) {
			this(Severity.NON_BREAKING, actionType);
		}
	}
}
