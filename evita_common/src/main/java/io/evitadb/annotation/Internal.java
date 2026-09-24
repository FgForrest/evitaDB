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

package io.evitadb.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a member that is `public` only because Java has no narrower visibility reaching the sibling module that needs
 * it. **The contract of an annotated member may change without notice and without a deprecation cycle** - it is not
 * part of the surface evitaDB keeps stable for the people who put the library on their classpath.
 *
 * That sentence is the whole scoping rule, and it is deliberately narrow. "Low-level", "awkward to call" and
 * "you probably want something else" are not reasons to apply this annotation; without the no-notice clause it
 * spreads to everything `public` below the API surface and stops carrying information.
 *
 * **Reach for a qualified export first.** evitaDB is a set of JPMS modules and already uses qualified exports
 * (`exports some.package to some.module;`). Where a whole *package* is internal, that is the compiler-enforced
 * answer and this annotation would be strictly weaker: qualify-export the package, or move the type into one that
 * is already qualified, and write no annotation. What JPMS cannot express is *member* granularity - a package
 * genuinely exported to everyone that holds one constructor, one accessor or one nested type the engine reaches
 * across a module boundary. That gap is the only thing this annotation is for.
 *
 * **This is the opposite promise to {@link Deprecated}.** A deprecated member promises a notice period before it
 * disappears; an `@Internal` member promises none. A member that is on its way out *and* was never supported
 * carries both, and the deprecation's `since` still follows `.claude/rules/deprecation-policy.md`.
 *
 * **The `value()` is required** because a bare marker only says "don't". Naming the supported alternative is the
 * part that actually redirects the reader:
 *
 * ```java
 * {@literal @}Internal("build an unnamed referenceContent(...) - the instance-name form backs the GraphQL layer")
 * public ReferenceContent(
 * 	{@literal @}Nullable String name,
 * 	{@literal @}Nonnull ManagedReferencesBehaviour managedReferences,
 * 	...
 * ) {
 * ```
 *
 * When a whole class is internal, annotate the class rather than each of its members.
 *
 * **Enforcement.** `InternalApiUsageTest` in `evita_functional_tests` reads the compiled class files of every
 * module, collects every member carrying this annotation and fails the build when a module outside its allowlist
 * references one. A module may always reach its own internal members; reaching one from anywhere else is a
 * deliberate, reviewable edit to that allowlist rather than an accident. The retention is
 * {@link RetentionPolicy#CLASS} because that check reads bytecode: enough for the audit, no runtime cost and
 * nothing to reflect over.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.CLASS)
public @interface Internal {

	/**
	 * The supported alternative the reader should use instead, in a short phrase that names it.
	 *
	 * Write what to call, not merely that this must not be called - "use `getReferenceEntityFetch()` for the
	 * requirements a query states" redirects, "internal, do not use" does not. When there genuinely is no
	 * alternative because the member has no meaning outside the engine, say which pipeline owns it - for example
	 * "internal to the fetch pipeline" - so that a reader can tell a missing alternative from an unwritten one.
	 *
	 * @return phrase naming the supported alternative, or the pipeline that owns the member when there is none
	 */
	String value();

}
