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

package io.evitadb.externalApi.configuration;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum TlsMode {
	RELAXED,
	FORCE_TLS,
	FORCE_NO_TLS;

	private static final Map<String, TlsMode> lookup =
		Arrays.stream(values())
			.collect(Collectors.toMap(Enum::name, Function.identity()));

	public static TlsMode getByName(@Nullable String name) {
		if (name == null) {
			return RELAXED;
		}
		final TlsMode mode = lookup.get(name);
		return mode == null ? RELAXED : mode;
	}
}
