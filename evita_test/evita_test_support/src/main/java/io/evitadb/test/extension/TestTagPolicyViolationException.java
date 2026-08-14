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

package io.evitadb.test.extension;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown by {@link TestTagPolicyFilter} in strict mode when at least one discovered test method
 * does not carry the required layer + capability tag pair.
 *
 * The exception is deliberately raised from JUnit Platform's post-discovery filtering phase, which
 * is the only listener-style extension point whose exceptions the platform propagates to the build
 * tool — see the class-level documentation of {@link TestTagPolicyFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TestTagPolicyViolationException extends IllegalStateException {
	@Serial private static final long serialVersionUID = 4386112905947286781L;

	public TestTagPolicyViolationException(@Nonnull String message) {
		super(message);
	}

}
