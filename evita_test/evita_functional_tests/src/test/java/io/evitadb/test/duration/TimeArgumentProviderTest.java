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

package io.evitadb.test.duration;

import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;

import javax.annotation.Nonnull;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link TimeArgumentProvider} resolves the per-test budget from JUnit configuration parameters:
 * `intervalInSeconds` wins, the legacy minute-based `interval` applies only when it is absent, and
 * {@link TimeArgumentProvider#DEFAULT_INTERVAL_IN_SECONDS} applies when neither is set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(CONTRACT)
@Tag(TASK)
@DisplayName("TimeArgumentProvider budget resolution")
class TimeArgumentProviderTest {

	/**
	 * Builds an extension context that answers configuration-parameter lookups from the given map and refuses every
	 * other call, so the test fails loudly if the provider starts depending on anything else.
	 *
	 * @param parameters the configuration parameters the context exposes
	 * @return a context backed by `parameters`
	 */
	@Nonnull
	private static ExtensionContext contextWith(@Nonnull Map<String, String> parameters) {
		return (ExtensionContext) Proxy.newProxyInstance(
			ExtensionContext.class.getClassLoader(),
			new Class<?>[]{ExtensionContext.class},
			(proxy, method, args) -> {
				if ("getConfigurationParameter".equals(method.getName()) && args != null && args.length == 2) {
					@SuppressWarnings("unchecked") final Function<String, ?> transformer = (Function<String, ?>) args[1];
					return Optional.ofNullable(parameters.get((String) args[0])).map(transformer);
				}
				throw new UnsupportedOperationException("Unexpected call: " + method);
			}
		);
	}

	/**
	 * Runs the provider against a context exposing the given parameters and returns the single input it supplies.
	 *
	 * @param parameters the configuration parameters the context exposes
	 * @return the generational input the provider produced
	 */
	@Nonnull
	private static GenerationalTestInput provide(@Nonnull Map<String, String> parameters) {
		final List<? extends Arguments> arguments = new TimeArgumentProvider()
			.provideArguments(null, contextWith(parameters))
			.toList();
		assertEquals(1, arguments.size(), "The provider must supply exactly one input.");
		return (GenerationalTestInput) arguments.get(0).get()[0];
	}

	@Test
	@DisplayName("defaults to the short budget when no parameter is set")
	void shouldDefaultWhenNoParameterIsSet() {
		assertEquals(
			TimeArgumentProvider.DEFAULT_INTERVAL_IN_SECONDS,
			provide(Map.of()).intervalInSeconds()
		);
	}

	@Test
	@DisplayName("takes the budget in seconds when intervalInSeconds is set")
	void shouldUseSecondsParameter() {
		assertEquals(
			7,
			provide(Map.of(TimeArgumentProvider.INTERVAL_IN_SECONDS, "7")).intervalInSeconds()
		);
	}

	@Test
	@DisplayName("converts the legacy minute-based interval when intervalInSeconds is absent")
	void shouldConvertLegacyMinutesParameter() {
		assertEquals(
			120,
			provide(Map.of(TimeArgumentProvider.INTERVAL_IN_MINUTES, "2")).intervalInSeconds()
		);
	}

	@Test
	@DisplayName("prefers intervalInSeconds over the legacy minute-based interval")
	void shouldPreferSecondsOverLegacyMinutes() {
		assertEquals(
			7,
			provide(
				Map.of(
					TimeArgumentProvider.INTERVAL_IN_SECONDS, "7",
					TimeArgumentProvider.INTERVAL_IN_MINUTES, "2"
				)
			).intervalInSeconds()
		);
	}

}
