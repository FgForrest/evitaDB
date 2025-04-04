/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Convenient list builder. It is alternative to {@link List#of()}. It is directly compatible with {@link MapBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ListBuilder {

	private final List<Object> list = new LinkedList<>();

	@Nonnull
	public static ListBuilder list() {
		return new ListBuilder();
	}

	@Nonnull
	public ListBuilder i(@Nonnull Object element) {
		if (element instanceof MapBuilder mapBuilder) {
			list.add(mapBuilder.build());
		} else if (element instanceof ListBuilder listBuilder) {
			list.add(listBuilder.build());
		} else {
			list.add(element);
		}
		return this;
	}

	@Nonnull
	public List<Object> build() {
		return Collections.unmodifiableList(list);
	}
}
