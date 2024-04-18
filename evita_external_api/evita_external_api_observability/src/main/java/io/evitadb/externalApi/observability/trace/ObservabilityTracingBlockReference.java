/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.trace.TracingBlockReference;
import io.evitadb.api.trace.TracingContext.SpanAttribute;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

import static io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.clearMdc;
import static io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.setAttributes;

/**
 * Block reference carrying OpenTelemetry {@link Span} object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ObservabilityTracingBlockReference implements TracingBlockReference {

	@Nonnull private final Span span;
	@Nonnull private final Scope scope;
	@Nullable private final Supplier<SpanAttribute[]> attributeSupplier;
	@Nullable private final Runnable closeCallback;

	@Nullable @Setter private Throwable error;

	@Override
	public void close() {
		if (error != null) {
			span.setStatus(StatusCode.ERROR);
			span.recordException(error);
		} else {
			span.setStatus(StatusCode.OK);
		}

		scope.close();

		if (attributeSupplier != null) {
			final SpanAttribute[] finalAttributes = attributeSupplier.get();
			if (finalAttributes != null) {
				setAttributes(span, finalAttributes);
			}
		}

		span.end();
		clearMdc();

		if (closeCallback != null) {
			closeCallback.run();
		}
	}
}
