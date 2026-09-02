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

package io.evitadb.core.executor;

import io.evitadb.core.executor.InterruptionTransformer.InterruptionAdvice;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nonnull;

/**
 * Base class for the build-time ByteBuddy plugins that weave {@link InterruptionAdvice} into methods which must poll
 * the thread interrupt flag on entry. Subclasses only supply the matcher describing *which* methods those are.
 *
 * ## Why the advice is applied via `visit` rather than `intercept`
 *
 * `builder.visit(Advice.to(...).on(matcher))` rewrites the body of every **declared** method that matches, in place.
 * The alternative — `builder.method(matcher).intercept(Advice.to(...))` — instead matches *invokable* methods,
 * including inherited ones, and therefore generates a synthetic override in every subclass that merely inherits a
 * matching method. On `Formula#compute`, the hottest path in the engine, that added one extra frame per formula node
 * for no additional coverage: the check already sits on the declaring implementation. Both forms work under the
 * `byte-buddy-maven-plugin` default `REBASE` entry point; `visit` is chosen purely because it is the leaner of the
 * two.
 *
 * ## A matcher that matches nothing fails silently
 *
 * A ByteBuddy matcher that matches nothing is indistinguishable from one that works — the plugin logs
 * `Transformed N type(s)` either way. That is exactly how the original defect stayed hidden for the whole lifetime of
 * the annotation: the matcher union was built with `ElementMatchers.anyOf(...)`, whose `Object...` overload compares
 * candidates using {@link Object#equals(Object)} against the supplied values, so passing matcher instances to it
 * matched no method anywhere, in any module, ever.
 *
 * Nothing here can detect that, because the `transform` goal is incremental: when nothing recompiled it processes
 * zero types, so a "this plugin wove nothing" assertion cannot tell a dead matcher from an up-to-date build. The
 * guard therefore lives in `InterruptionAdviceWovenTest`, which asserts the poll is present in the **built**
 * class files of each module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class AbstractInterruptionTransformer implements Plugin {
	/**
	 * The advice visitor, resolved once per plugin instance because
	 * {@link #apply(DynamicType.Builder, TypeDescription, ClassFileLocator)} is invoked for every type in the module.
	 */
	@Nonnull private final AsmVisitorWrapper advice;

	/**
	 * Resolves the matcher and the advice visitor once, because
	 * {@link #apply(DynamicType.Builder, TypeDescription, ClassFileLocator)} runs for every type in the module.
	 *
	 * Note that {@link #interruptibleMethods()} is invoked from here and therefore must not depend on subclass state.
	 */
	protected AbstractInterruptionTransformer() {
		this.advice = Advice.to(InterruptionAdvice.class).on(interruptibleMethods());
	}

	/**
	 * Returns the matcher selecting the methods that must poll the thread interrupt flag on entry.
	 *
	 * Assemble a union by chaining {@link ElementMatcher.Junction#or(ElementMatcher)} — never via
	 * `ElementMatchers.anyOf(...)`, which is an equality matcher over values and silently matches nothing when handed
	 * matcher instances.
	 *
	 * The returned matcher must exclude abstract methods; they have no body to instrument.
	 *
	 * Invoked from the constructor, so implementations must not read subclass state.
	 *
	 * @return matcher applied to the declared methods of every type in the module
	 */
	@Nonnull
	protected abstract ElementMatcher.Junction<MethodDescription> interruptibleMethods();

	@Override
	public boolean matches(@Nonnull TypeDescription target) {
		return true;
	}

	@Nonnull
	@Override
	public DynamicType.Builder<?> apply(
		@Nonnull DynamicType.Builder<?> builder,
		@Nonnull TypeDescription typeDescription,
		@Nonnull ClassFileLocator classFileLocator
	) {
		return builder.visit(this.advice);
	}

	@Override
	public void close() {
		// no resources to release
	}

}
