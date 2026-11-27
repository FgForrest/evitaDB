/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.lab.configuration.LabOptions;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SECURITY;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.CollectionUtils.property;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that embedded callers and JSON creators share endpoint-specific TLS defaults and strict validation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Endpoint TLS options")
@Tag(EXTERNAL_API)
@Tag(SECURITY)
class EndpointTlsOptionsTest {
	/**
	 * Documented TLS default of every endpoint, in the order the tests visit them. The values are written out instead
	 * of being read from the options classes, so a changed default fails the tests rather than moving the expectation.
	 */
	private static final Map<String, TlsMode> ENDPOINT_DEFAULTS = createLinkedHashMap(
		property("graphQL", TlsMode.FORCE_TLS),
		property("rest", TlsMode.FORCE_TLS),
		property("gRPC", TlsMode.FORCE_TLS),
		property("lab", TlsMode.FORCE_TLS),
		property("system", TlsMode.FORCE_NO_TLS),
		property("observability", TlsMode.FORCE_NO_TLS)
	);

	/**
	 * Supplies every endpoint name with its documented TLS default.
	 *
	 * @return endpoint name and the expected default
	 */
	@Nonnull
	static Stream<Arguments> endpointDefaults() {
		return ENDPOINT_DEFAULTS.entrySet().stream().map(it -> Arguments.of(it.getKey(), it.getValue()));
	}

	/** Verifies all no-argument and host-only constructors used by embedded servers. */
	@ParameterizedTest
	@DisplayName("Should use the documented TLS default in programmatic constructors")
	@MethodSource("endpointDefaults")
	void shouldDefaultProgrammaticConstructors(@Nonnull String endpoint, @Nonnull TlsMode expected) {
		final AbstractApiOptions[] options = switch (endpoint) {
			case "graphQL" -> new AbstractApiOptions[] {new GraphQLOptions(), new GraphQLOptions("localhost:5555")};
			case "rest" -> new AbstractApiOptions[] {new RestOptions(), new RestOptions("localhost:5555")};
			case "gRPC" -> new AbstractApiOptions[] {new GrpcOptions(), new GrpcOptions("localhost:5555")};
			case "lab" -> new AbstractApiOptions[] {new LabOptions(), new LabOptions("localhost:5555")};
			case "system" -> new AbstractApiOptions[] {new SystemOptions(), new SystemOptions("localhost:5555")};
			case "observability" -> new AbstractApiOptions[] {
				new ObservabilityOptions(), new ObservabilityOptions("localhost:5555")};
			default -> throw new IllegalArgumentException(endpoint);
		};
		assertAll(
			() -> assertEquals(expected, options[0].getTlsMode(), endpoint + " no-arg TLS default"),
			() -> assertEquals(expected, options[1].getTlsMode(), endpoint + " host-only TLS default")
		);
	}

	/** Verifies creator defaults and every supported explicit name for each endpoint. */
	@ParameterizedTest
	@DisplayName("Should resolve null to the endpoint default and preserve explicit TLS modes")
	@NullSource
	@ValueSource(strings = {"FORCE_TLS", "FORCE_NO_TLS", "RELAXED"})
	void shouldResolveCreatorTlsModes(@Nullable String mode) {
		for (final Entry<String, TlsMode> endpoint : ENDPOINT_DEFAULTS.entrySet()) {
			final TlsMode expected = mode == null ? endpoint.getValue() : TlsMode.valueOf(mode);
			assertEquals(
				expected, createOptions(endpoint.getKey(), mode).getTlsMode(),
				endpoint.getKey() + " creator mode=" + mode
			);
		}
	}

	/** Verifies that empty strings, whitespace, wrong case and misspelt names fail with useful diagnostics. */
	@ParameterizedTest
	@DisplayName("Should reject every non-null TLS value outside the accepted names")
	@ValueSource(strings = {"", " ", "force_no_tls", "FORCE-TLS", "typo"})
	void shouldRejectInvalidTlsModes(@Nonnull String mode) {
		for (final String endpoint : ENDPOINT_DEFAULTS.keySet()) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class, () -> createOptions(endpoint, mode), endpoint + " mode='" + mode + "'"
			);
			assertTrue(exception.getMessage().contains("'" + mode + "'"), exception.getMessage());
			assertTrue(
				exception.getMessage().toLowerCase(Locale.ROOT).contains(endpoint.toLowerCase(Locale.ROOT)),
				exception.getMessage()
			);
			for (final TlsMode accepted : TlsMode.values()) {
				assertTrue(exception.getMessage().contains(accepted.name()), exception.getMessage());
			}
		}
	}

	/**
	 * Creates real endpoint options through the constructor Jackson uses.
	 *
	 * @param endpoint endpoint whose options are being created
	 * @param mode TLS mode passed to the options
	 * @return options for the requested endpoint
	 * @throws IllegalArgumentException if the endpoint is unknown
	 */
	@Nonnull
	private static AbstractApiOptions createOptions(@Nonnull String endpoint, @Nullable String mode) {
		return switch (endpoint) {
			case "graphQL" -> new GraphQLOptions(null, ":5555", null, mode, null, null, null, null, null, null, null);
			case "rest" -> new RestOptions(null, ":5555", null, mode, null, null, null);
			case "gRPC" -> new GrpcOptions(null, ":5555", null, mode, null, null, null, null, null);
			case "lab" -> new LabOptions(null, ":5555", null, mode, null, null, null, null);
			case "system" -> new SystemOptions(null, ":5555", null, mode, null, null, null);
			case "observability" -> new ObservabilityOptions(
				null, ":5555", null, mode, null, null, null, null, null, null, null, null
			);
			default -> throw new IllegalArgumentException(endpoint);
		};
	}
}
