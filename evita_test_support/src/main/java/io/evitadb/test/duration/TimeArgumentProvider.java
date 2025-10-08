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

package io.evitadb.test.duration;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;

import java.util.Random;
import java.util.stream.Stream;

/**
 * This provider retrieves the minute amount from the environment variables and defaults to 1 minute if nothing
 * is found.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TimeArgumentProvider implements ArgumentsProvider {
	protected static final int SEED;

	static {
		SEED = new Random().nextInt();
	}

	@Override
	public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
		final Integer interval = context.getConfigurationParameter("interval", Integer::parseInt)
			.orElse(1);
		return Stream.of(
			Arguments.of(
				new GenerationalTestInput(
					interval, SEED
				)
			)
		);
	}

	public record GenerationalTestInput(
		int intervalInMinutes,
		int randomSeed
	) {}

}
