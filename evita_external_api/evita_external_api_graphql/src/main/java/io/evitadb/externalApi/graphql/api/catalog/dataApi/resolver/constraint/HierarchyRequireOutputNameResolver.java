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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.language.Field;
import graphql.schema.SelectedField;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Resolves output name of each hierarchy from field path.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HierarchyRequireOutputNameResolver {

	@Nonnull
	public static String resolve(@Nonnull SelectedField field) {
		// todo lho try to implement full path before name to allow hierarchies across duplicate hierarchy fields
		if (field.getAlias() == null) {
			// default output name
			return field.getName();
		}
		return field.getAlias();
	}

	@Nonnull
	public static String resolve(@Nonnull Field field) {
		// todo lho try to implement full path before name to allow hierarchies across duplicate hierarchy fields
		if (field.getAlias() == null) {
			// default output name
			return field.getName();
		}
		return field.getAlias();
	}
}
