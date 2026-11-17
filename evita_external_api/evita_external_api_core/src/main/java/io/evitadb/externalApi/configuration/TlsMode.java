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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TLS mode provides all supported methods for TLS handling in internal web server.
 * Unencrypted protocols are recommended to be used only in internal network that can be considered as safe.
 */
public enum TlsMode {
	/**
	 * Both TLS and non-TLS (unencrypted) protocols are allowed. Selection is up to the client.
	 */
	RELAXED,
	/**
	 * Server enforces TLS protocol only.
	 */
	FORCE_TLS,
	/**
	 * Server enforces non-TLS protocol only.
	 */
	FORCE_NO_TLS;

	private static final Map<String, TlsMode> lookup =
		Arrays.stream(values())
			.collect(Collectors.toMap(Enum::name, Function.identity()));

	/**
	 * Resolves an exact TLS mode name, using the endpoint default only when no name is supplied.
	 *
	 * @param name configured mode name, or null to use the endpoint default
	 * @param defaultMode documented default of the endpoint
	 * @param endpoint endpoint description used in configuration diagnostics
	 * @return the configured mode or the endpoint default
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when `name` is not null and not the exact,
	 *         case-sensitive name of a constant, the empty string included; the message names the value, the
	 *         endpoint and the accepted names
	 */
	@Nonnull
	public static TlsMode getByName(
		@Nullable String name, @Nonnull TlsMode defaultMode, @Nonnull String endpoint
	) {
		if (name == null) {
			return defaultMode;
		}
		final TlsMode mode = lookup.get(name);
		Assert.isTrue(
			mode != null,
			() -> "Invalid tlsMode '" + name + "' for " + endpoint +
				". Accepted values: FORCE_TLS, FORCE_NO_TLS, RELAXED."
		);
		return mode;
	}
}
