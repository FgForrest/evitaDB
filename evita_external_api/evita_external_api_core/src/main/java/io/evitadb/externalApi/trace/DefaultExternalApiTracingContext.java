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

package io.evitadb.externalApi.trace;

import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Default NOOP implementation of {@link ExternalApiTracingContext}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class DefaultExternalApiTracingContext implements ExternalApiTracingContext<Object> {
	public final static DefaultExternalApiTracingContext INSTANCE = new DefaultExternalApiTracingContext();

	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		// do nothing
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Runnable runnable) {
		runnable.run();
	}

	@Nullable
	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}
}
