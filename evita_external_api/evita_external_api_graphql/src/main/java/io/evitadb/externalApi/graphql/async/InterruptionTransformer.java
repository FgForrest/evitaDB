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

package io.evitadb.externalApi.graphql.async;

import graphql.schema.DataFetcher;
import io.evitadb.core.executor.InterruptionTransformer.InterruptionAdvice;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

import javax.annotation.Nonnull;

/**
 * ByteBuddy transformer that injects {@link InterruptionAdvice} into specific methods used during query execution
 * shared logic that checks for thread interruption. If the thread is interrupted, the method throws an
 * {@link InterruptedException} and effectively stops the query execution.
 */
public class InterruptionTransformer implements Plugin {

	@Override
	public boolean matches(TypeDescription target) {
		return true;
	}

	@Nonnull
	@Override
	public DynamicType.Builder<?> apply(
		DynamicType.Builder<?> builder,
		@Nonnull TypeDescription typeDescription,
		@Nonnull ClassFileLocator classFileLocator
	) {
		return builder.method(
				/* Data fetcher invocation */
				ElementMatchers.isOverriddenFrom(DataFetcher.class)
					.and(ElementMatchers.named("get"))
					.and(ElementMatchers.isAbstract())

			)
			.intercept(Advice.to(InterruptionAdvice.class));
	}

	@Override
	public void close() {
		// No resources to release
	}

}
