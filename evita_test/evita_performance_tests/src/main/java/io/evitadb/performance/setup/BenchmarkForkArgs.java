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

package io.evitadb.performance.setup;

import org.openjdk.jmh.annotations.Fork;

/**
 * JVM arguments that every benchmark booting an embedded evitaDB instance must pass to its forked JVM.
 *
 * evitaDB uses Byte Buddy to generate classes reflectively while an instance boots, which JDK 17+ refuses unless
 * these packages are opened. The flags have to reach the **forked** JVM specifically: JMH starts it with `-cp`, so
 * neither the launching process's own command line nor the uber-jar's `Add-Opens` manifest entry applies to the
 * process that actually runs the benchmark.
 *
 * When they are missing, benchmark state setup fails with `UnsupportedOperationException: Cannot define class using
 * reflection`, and JMH reports that as a completed run with an empty result table rather than as an error - so the
 * run looks like it merely produced no score, and the cause is nowhere near the symptom.
 *
 * Declare them on the benchmark class as:
 *
 * ```java
 * @Fork(jvmArgsAppend = {
 *     BenchmarkForkArgs.OPEN_LANG, BenchmarkForkArgs.ALL_UNNAMED,
 *     ...
 * })
 * ```
 *
 * Annotation values must be compile-time constants, which is why this is a set of individual `String` constants
 * rather than a single `String[]` - an array constant cannot be referenced from an annotation.
 *
 * Note {@link Fork#value()} defaults to `-1` ("unset"), so a class that only needs the JVM arguments should NOT
 * also specify `value` - doing so would silently change how many forks that benchmark runs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface BenchmarkForkArgs {

	/**
	 * The `--add-opens` flag itself; each opened package is passed as this flag followed by its descriptor.
	 */
	String ADD_OPENS = "--add-opens";
	/**
	 * Byte Buddy defines generated classes through `java.lang.ClassLoader` internals.
	 */
	String OPEN_LANG = "java.base/java.lang=ALL-UNNAMED";
	/**
	 * Method handles used by the generated accessors.
	 */
	String OPEN_LANG_INVOKE = "java.base/java.lang.invoke=ALL-UNNAMED";
	/**
	 * `BigDecimal` internals touched by the price and attribute serializers.
	 */
	String OPEN_MATH = "java.base/java.math=ALL-UNNAMED";
	/**
	 * Collection internals touched during Kryo deserialization.
	 */
	String OPEN_UTIL = "java.base/java.util=ALL-UNNAMED";

}
