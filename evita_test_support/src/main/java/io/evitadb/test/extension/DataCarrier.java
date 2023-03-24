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

package io.evitadb.test.extension;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * This DTO allows to pass multiple data as a return value of {@link io.evitadb.test.annotation.DataSet} function.
 * These data might but might not be labeled and can be injected into the the input parameters of test methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DataCarrier {
	private final Map<String, Object> valuesByName = new HashMap<>();
	private final Map<Class<?>, Object> valuesByType = new HashMap<>();

	public DataCarrier(Object... value) {
		for (Object valueItem : value) {
			valuesByType.put(valueItem.getClass(), valueItem);
		}
	}

	public DataCarrier(String name, Object value, String name2, Object value2) {
		this.valuesByName.put(name, value);
		this.valuesByName.put(name2, value2);

		this.valuesByType.put(value.getClass(), value);
		this.valuesByType.putIfAbsent(value2.getClass(), value2);
	}

	public DataCarrier(String name, Object value, String name2, Object value2, String name3, Object value3) {
		this.valuesByName.put(name, value);
		this.valuesByName.put(name2, value2);
		this.valuesByName.put(name3, value3);

		this.valuesByType.put(value.getClass(), value);
		this.valuesByType.putIfAbsent(value2.getClass(), value2);
		this.valuesByType.putIfAbsent(value3.getClass(), value3);
	}

	public DataCarrier(String name, Object value, String name2, Object value2, String name3, Object value3, String name4, Object value4) {
		this.valuesByName.put(name, value);
		this.valuesByName.put(name2, value2);
		this.valuesByName.put(name3, value3);
		this.valuesByName.put(name4, value4);

		this.valuesByType.put(value.getClass(), value);
		this.valuesByType.putIfAbsent(value2.getClass(), value2);
		this.valuesByType.putIfAbsent(value3.getClass(), value3);
		this.valuesByType.putIfAbsent(value4.getClass(), value4);
	}

	@Nullable
	public Object getValueByType(Class<?> valueType) {
		return ofNullable(valuesByType.get(valueType))
			.orElseGet(() ->
				valuesByType
					.entrySet()
					.stream()
					.filter(it -> valueType.isAssignableFrom(it.getKey()))
					.map(Entry::getValue)
					.findAny()
					.orElse(null)
			);
	}

	@Nullable
	public Object getValueByName(String name) {
		return valuesByName.get(name);
	}

	@Nonnull
	public Set<Entry<String, Object>> entrySet() {
		return valuesByName.entrySet();
	}

	@Nonnull
	public Collection<Object> anonymousValues() {
		return valuesByName.isEmpty() ? valuesByType.values() : Collections.emptySet();
	}

}
