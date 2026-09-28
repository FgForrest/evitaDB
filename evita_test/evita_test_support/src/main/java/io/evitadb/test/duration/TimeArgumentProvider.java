/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;

import java.util.Random;
import java.util.stream.Stream;

/**
 * Supplies the {@link GenerationalTestInput} of every time-bounded generative test: how long the test keeps
 * generating and which random seed drives it.
 *
 * The budget defaults to {@value #DEFAULT_INTERVAL_IN_SECONDS} seconds per test. It is deliberately short because
 * it is multiplied by the number of generative tests - well over two hundred in the long-running module - and that
 * sum, divided by the number of test classes running in parallel, is the floor of the weekly long-running CI job.
 * Each run draws a fresh seed, so breadth comes from running every week rather than from running long once. A
 * deeper local soak raises the budget through the JUnit configuration parameter {@value #INTERVAL_IN_SECONDS},
 * set as a system property of the test JVM (in the long-running module that is
 * `-DlongRunningArgLine='-Xmx5g -DintervalInSeconds=600'`, because its surefire fork ignores `-DargLine`), or
 * through the older minute-based {@value #INTERVAL_IN_MINUTES}, which is honoured when the seconds parameter is
 * absent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TimeArgumentProvider implements ArgumentsProvider {
	/**
	 * JUnit configuration parameter carrying the per-test budget in seconds.
	 */
	public static final String INTERVAL_IN_SECONDS = "intervalInSeconds";
	/**
	 * Legacy JUnit configuration parameter carrying the per-test budget in whole minutes; used only when
	 * {@link #INTERVAL_IN_SECONDS} is not set.
	 */
	public static final String INTERVAL_IN_MINUTES = "interval";
	/**
	 * Budget of a generative test when no configuration parameter overrides it.
	 */
	public static final int DEFAULT_INTERVAL_IN_SECONDS = 30;
	protected static final int SEED;

	static {
		// allow reproducing a specific failing seed via `-Dtest.seed=NN`
		final Integer overridden = Integer.getInteger("test.seed");
		SEED = overridden != null ? overridden : new Random().nextInt();
	}

	@Override
	public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
		final int intervalInSeconds = context.getConfigurationParameter(INTERVAL_IN_SECONDS, Integer::parseInt)
			.or(
				() -> context.getConfigurationParameter(INTERVAL_IN_MINUTES, Integer::parseInt)
					.map(minutes -> minutes * 60)
			)
			.orElse(DEFAULT_INTERVAL_IN_SECONDS);
		return Stream.of(
			Arguments.of(
				new GenerationalTestInput(
					intervalInSeconds, SEED
				)
			)
		);
	}

	/**
	 * Input of one time-bounded generative test.
	 *
	 * @param intervalInSeconds how long the test keeps generating, in seconds; at least one generation always runs
	 * @param randomSeed        the seed of the test's random generator, reported on failure for reproduction
	 */
	public record GenerationalTestInput(
		int intervalInSeconds,
		int randomSeed
	) {}

}
