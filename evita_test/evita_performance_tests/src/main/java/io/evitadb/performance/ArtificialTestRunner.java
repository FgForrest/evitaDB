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

package io.evitadb.performance;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Entry point of the benchmark uber-jar.
 *
 * With no arguments it runs the curated external-API suite below. With arguments it hands over verbatim to
 * {@link org.openjdk.jmh.Main}, so the jar behaves like any other JMH uber-jar:
 *
 * ```
 * java -jar benchmarks.jar                          # curated external-API suite
 * java -jar benchmarks.jar CommitThroughputBenchmark -t 8 -f 1
 * ```
 *
 * The delegation matters. This class used to be wired in as the jar's `Main-Class` while ignoring `args`
 * entirely, so the perfectly reasonable `java -jar benchmarks.jar SomeBenchmark` quietly ran the whole
 * external-API suite instead of the requested benchmark — costing a long run and producing results for
 * something nobody asked for, with no diagnostic pointing at the cause.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ArtificialTestRunner {

	/**
	 * JVM arguments every forked benchmark JVM needs.
	 *
	 * Byte Buddy generates classes reflectively while an Evita instance boots and cannot do so on JDK 17+ without
	 * these packages opened. They have to be applied to the **forked** JVM: JMH starts it with `-cp`, so neither the
	 * flags of the launching process nor the uber-jar's `Add-Opens` manifest entry reach the process that actually
	 * runs the benchmark. Missing them, state setup fails with "Cannot define class using reflection" and JMH prints
	 * an empty result table rather than reporting an error - a failure that reads like "the benchmark produced no
	 * score" instead of "the benchmark never ran".
	 */
	private static final String[] FORK_JVM_ARGS = {
		"--add-opens", "java.base/java.lang=ALL-UNNAMED",
		"--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
		"--add-opens", "java.base/java.math=ALL-UNNAMED",
		"--add-opens", "java.base/java.util=ALL-UNNAMED"
	};

	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			// A caller who passes arguments wants JMH's own command line, not the curated suite. The parsed command
			// line is used as the parent of a builder that appends the fork arguments, so every benchmark launched
			// through this jar gets them without each benchmark class having to declare its own `@Fork(jvmArgsAppend)`.
			new Runner(
				new OptionsBuilder()
					.parent(new CommandLineOptions(args))
					.jvmArgsAppend(FORK_JVM_ARGS)
					.build()
			).run();
			return;
		}

		System.out.println(
			"No arguments given - running the curated external-API benchmark suite.\n" +
				"Pass a benchmark name (and any JMH options) to run something specific, e.g.:\n" +
				"    java -jar benchmarks.jar CommitThroughputBenchmark -t 8 -f 1\n"
		);

		Options opt = new OptionsBuilder()
			.include("io.evitadb.performance.externalApi.*")
			.threads(6)
			.forks(1)
			.jvmArgsAppend(FORK_JVM_ARGS)
			.warmupTime(TimeValue.seconds(60))
			.measurementTime(TimeValue.seconds(60))
			.warmupIterations(1)
			.measurementIterations(1)
			.resultFormat(ResultFormatType.JSON)
			.result("result.json")
			.build();

		new Runner(opt).run();
	}

}
