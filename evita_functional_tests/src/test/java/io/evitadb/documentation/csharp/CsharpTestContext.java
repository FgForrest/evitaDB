/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.documentation.csharp;

import io.evitadb.documentation.Environment;
import io.evitadb.documentation.TestContext;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Context creates new {@link CShell} instance and initializes it. In this process, C# query validator is downloaded
 * and necessary file permissions are set, so this process could be time-consuming.
 *
 * Because of that, the {@link CShell} instance is reused between tests to speed them up.
 *
 * @author Tomáš Pozler, 2023
 */
@Getter
public class CsharpTestContext implements TestContext {
	/**
	 * CShell instance used for C# code validation and fetching results.
	 */
	private final CShell cshell;
	public CsharpTestContext(@Nonnull Environment profile) {
		this.cshell = new CShell(profile);
	}
}
