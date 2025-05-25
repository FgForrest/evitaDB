/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test.extension;

import io.evitadb.utils.Assert;

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

	public static Tuple tuple(@Nonnull String name, @Nonnull Object value) {
		return new Tuple(name, value);
	}

	public DataCarrier(Object... value) {
		for (Object valueItem : value) {
			this.valuesByType.put(valueItem.getClass(), valueItem);
		}
	}

	public DataCarrier(Tuple... entry) {
		for (Tuple tuple : entry) {
			this.valuesByName.put(tuple.name(), tuple.value());
			this.valuesByType.put(tuple.value().getClass(), tuple.value());
		}
	}

	public DataCarrier(@Nonnull Set<Entry<String, Object>> entrySet) {
		entrySet.forEach(entry -> {
			this.valuesByName.put(entry.getKey(), entry.getValue());
			this.valuesByType.putIfAbsent(entry.getValue().getClass(), entry.getValue());
		});
	}

	public DataCarrier(String name, Object value) {
		this.valuesByName.put(name, value);
		this.valuesByType.put(value.getClass(), value);
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

	@Nonnull
	public DataCarrier put(@Nonnull String name, @Nonnull Object value) {
		Assert.isPremiseValid(!this.valuesByName.containsKey(name), () -> "Value with name `" + name + "` already exists!");
		this.valuesByName.put(name, value);
		if (!this.valuesByType.containsKey(value.getClass())) {
			this.valuesByType.put(value.getClass(), value);
		}
		return this;
	}

	@Nullable
	public Object getValueByType(Class<?> valueType) {
		return ofNullable(this.valuesByType.get(valueType))
			.orElseGet(() ->
				this.valuesByType
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
		return this.valuesByName.get(name);
	}

	@Nonnull
	public Set<Entry<String, Object>> entrySet() {
		return this.valuesByName.entrySet();
	}

	@Nonnull
	public Collection<Object> anonymousValues() {
		return this.valuesByName.isEmpty() ? this.valuesByType.values() : Collections.emptySet();
	}

	public record Tuple(@Nonnull String name, @Nonnull Object value) {}

}
