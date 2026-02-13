/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.api.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.regex.Pattern;

/**
 * API-independent descriptor of a type.
 *
 * @see ObjectDescriptor
 * @see UnionDescriptor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface TypeDescriptor {

	String NAME_WILDCARD_PLACEHOLDER = "*";
	Pattern NAME_WILDCARD_PLACEHOLDER_PATTERN = Pattern.compile("\\" + NAME_WILDCARD_PLACEHOLDER);

	String name();

	@Nonnull
	String name(@Nonnull Object... schema);

	boolean isNameStatic();

	@Nullable
	String description(@Nonnull Object... args);
}
