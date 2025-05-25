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

import io.evitadb.exception.GenericEvitaInternalError;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Convenient list builder. It is alternative to {@link List#of()}. It is directly compatible with {@link MapBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ListBuilder {

	private final OutputType outputType;
	private final List<Object> list = new LinkedList<>();

	@Nonnull
	public static ListBuilder list() {
		return new ListBuilder(OutputType.LIST);
	}

	@Nonnull
	public static ListBuilder array() {
		return new ListBuilder(OutputType.ARRAY);
	}

	@Nonnull
	public ListBuilder i(@Nonnull Object element) {
		if (element instanceof MapBuilder mapBuilder) {
			this.list.add(mapBuilder.build());
		} else if (element instanceof ListBuilder listBuilder) {
			this.list.add(listBuilder.build());
		} else {
			this.list.add(element);
		}
		return this;
	}

	@Nonnull
	public Object build() {
		if (this.outputType == OutputType.LIST) {
			return Collections.unmodifiableList(this.list);
		} else if (this.outputType == OutputType.ARRAY) {
			return this.list.toArray();
		} else {
			throw new GenericEvitaInternalError("Unsupported output type `" + this.outputType + "`.");
		}
	}


	private enum OutputType {
		LIST, ARRAY
	}
}
