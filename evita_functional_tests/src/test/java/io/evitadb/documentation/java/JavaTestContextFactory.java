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

package io.evitadb.documentation.java;

import io.evitadb.documentation.Environment;
import io.evitadb.documentation.TestContextFactory;
import org.junit.jupiter.api.DynamicTest;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Factory for {@link JavaTestContext}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class JavaTestContextFactory implements TestContextFactory<JavaTestContext> {
	/**
	 * Memorized reference to the {@link JavaTestContext} that is shared for all Java dynamic tests in the same document.
	 */
	private final AtomicReference<JavaTestContext> testContextRef = new AtomicReference<>();

	@Override
	public DynamicTest getInitTest(@Nonnull Environment profile) {
		return dynamicTest(
			"Init JShell (" + profile + ")",
			() -> this.testContextRef.set(new JavaTestContext(profile))
		);
	}

	@Override
	public DynamicTest getTearDownTest(@Nonnull Environment profile) {
		return dynamicTest(
			"Destroy JShell (" + profile + ")",
			() -> getContext().tearDown()
		);
	}

	@Nonnull
	@Override
	public JavaTestContext getContext() {
		return Objects.requireNonNull(this.testContextRef.get());
	}
}
