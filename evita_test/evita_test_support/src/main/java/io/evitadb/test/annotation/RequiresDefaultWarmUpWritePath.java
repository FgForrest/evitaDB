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

package io.evitadb.test.annotation;

import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a test writes entities into a catalog in the `WARMING_UP` state and therefore needs the warm-up write
 * path in its DEFAULT configuration — per-entity atomicity switched off, exactly as production has it until the
 * mechanism's throughput measurement decides otherwise.
 *
 * **Why a test has to say this at all.** Per-entity atomicity of warm-up writes is gated by a process-wide static flag
 * (`WarmUpSavepoint#isEnabled()`, seeded from a system property). The savepoint fuzz suites have to switch it on to
 * exercise the mechanism, and test classes in the long-running module run CONCURRENTLY inside one JVM — so without
 * coordination a bulk load in one class would silently pick up the flag another class flipped, and fail (or pass) for a
 * reason that has nothing to do with what it tests.
 *
 * The coordination is a JUnit resource lock: this annotation takes {@link #RESOURCE} in
 * {@link ResourceAccessMode#READ} mode, the fuzz suites take it in {@link ResourceAccessMode#READ_WRITE}, so the two
 * never overlap. Read mode is shared, so annotating a class costs it nothing against its peers — several annotated
 * classes still run concurrently with one another, and with everything unannotated.
 *
 * **When to add it.** To any test in the long-running module that upserts or removes entities while its catalog is
 * still warming up — which is every test that bulk-loads a dataset before going live. It is harmless on a test that
 * turns out not to need it, and its absence on one that does is a race, so prefer adding it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ)
public @interface RequiresDefaultWarmUpWritePath {

	/**
	 * Name of the JUnit exclusive resource standing for the process-wide warm-up atomicity flag. Held in
	 * {@link ResourceAccessMode#READ} by everything that needs the flag left alone, and in
	 * {@link ResourceAccessMode#READ_WRITE} by the savepoint fuzz suites that flip it.
	 */
	String RESOURCE = "evitadb.warmUpAtomicity.enabled";

}
