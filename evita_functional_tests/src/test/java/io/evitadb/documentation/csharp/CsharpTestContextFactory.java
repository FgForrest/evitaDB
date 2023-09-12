/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.documentation.csharp;

import io.evitadb.documentation.TestContextFactory;
import org.junit.jupiter.api.DynamicTest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Factory for {@link CsharpTestContext}.
 *
 * @author Tomáš Pozler, 2023
 */
public class CsharpTestContextFactory implements TestContextFactory<CsharpTestContext> {
	/**
	 * Memorized reference to the {@link CsharpTestContext} that is shared for all C# dynamic tests in the same document.
	 */
	private final AtomicReference<CsharpTestContext> testContextRef = new AtomicReference<>();
	@Nullable
	@Override
	public DynamicTest getInitTest() {
		return dynamicTest(
			"Init C# query validator",
			() -> testContextRef.set(new CsharpTestContext())
		);
	}

	@Nullable
	@Override
	public DynamicTest getTearDownTest() {
		return dynamicTest(
			"Destroy C# query validator",
			() -> getContext().getCshell().close()
		);
	}

	@Nonnull
	@Override
	public CsharpTestContext getContext() {
		return Objects.requireNonNull(testContextRef.get());
	}
}
