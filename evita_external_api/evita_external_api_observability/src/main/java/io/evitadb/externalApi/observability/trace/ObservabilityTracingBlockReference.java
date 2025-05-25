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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
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
		if (this.error != null) {
			this.span.setStatus(StatusCode.ERROR);
			this.span.recordException(this.error);
		} else {
			this.span.setStatus(StatusCode.OK);
		}

		this.scope.close();

		if (this.attributeSupplier != null) {
			final SpanAttribute[] finalAttributes = this.attributeSupplier.get();
			if (finalAttributes != null) {
				setAttributes(this.span, finalAttributes);
			}
		}

		this.span.end();
		clearMdc();

		if (this.closeCallback != null) {
			this.closeCallback.run();
		}
	}
}
