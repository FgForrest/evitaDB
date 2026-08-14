/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.executor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose body must poll the thread interrupt flag on entry, so that cancelling the owning task or
 * query can unwind it promptly instead of letting it run to completion. Build-time ByteBuddy weaving
 * (`AbstractInterruptionTransformer`) injects that poll into every method this annotation selects - see that
 * class for the weaving mechanics and the matcher-union pitfall it guards against.
 *
 * ## The annotation must sit on the concrete implementation
 *
 * The weaving rewrites only **declared** methods of the type being processed; inherited methods are left alone.
 * Combined with the fact that Java does not inherit method annotations at all (`@Inherited` governs class-level
 * annotations only, never method-level ones), this annotation has to be repeated on every override that needs the
 * poll:
 *
 * - Placing it on an abstract method - whether declared on an interface or an abstract class - weaves nothing:
 *   there is no body to instrument, and the matcher that selects `@Interruptible` methods explicitly excludes
 *   abstract methods.
 * - Placing it on a base implementation does not carry over to a subclass that overrides the method without
 *   repeating the annotation - that override is silently left unwoven, with no compiler or build warning.
 *
 * `EvitaSession#getCatalogSchema` carries this annotation on exactly that basis: the method it implements is
 * declared on `EvitaSessionContract`, and only the concrete override in `EvitaSession` is ever woven.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface Interruptible {
}
