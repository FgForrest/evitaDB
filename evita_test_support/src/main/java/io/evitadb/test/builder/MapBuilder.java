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

package io.evitadb.test.builder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Convenient map builder that uses {@link LinkedHashMap} to preserve order of keys. It is alternative to {@link Map#of()}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class MapBuilder {

	private final Map<String, Object> map = new LinkedHashMap<>();

	public static MapBuilder map() {
		return new MapBuilder();
	};

	public MapBuilder e(@Nonnull String key, @Nullable Object value) {
		map.put(key, value);
		return this;
	}

	public Map<String, Object> build() {
		return map;
	}
}
