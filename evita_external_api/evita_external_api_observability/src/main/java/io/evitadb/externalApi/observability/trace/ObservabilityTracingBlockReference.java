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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.clearMdc;
import static io.evitadb.externalApi.observability.trace.ObservabilityTracingContext.setAttributes;

/**
 * Block reference carrying OpenTelemetry {@link Span} object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class ObservabilityTracingBlockReference
	implements TracingBlockReference {

	@Nonnull private final Span span;
	@Nonnull private final Scope scope;
	@Nullable private final Supplier<SpanAttribute[]> attributeSupplier;
	@Nullable private final Runnable closeCallback;
	/**
	 * Guard ensuring {@link #end()} logic executes at most once,
	 * even when called from multiple threads concurrently.
	 */
	private final AtomicBoolean ended = new AtomicBoolean(false);
	/**
	 * Guard ensuring {@link #detachScope()} logic executes at
	 * most once. Not atomic because detachScope must always be
	 * called on the creating thread.
	 */
	private volatile boolean scopeDetached = false;

	@Nullable private Throwable error;

	public ObservabilityTracingBlockReference(
		@Nonnull Span span,
		@Nonnull Scope scope,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier,
		@Nullable Runnable closeCallback
	) {
		this.span = span;
		this.scope = scope;
		this.attributeSupplier = attributeSupplier;
		this.closeCallback = closeCallback;
	}

	@Override
	public void setError(@Nonnull Throwable error) {
		this.error = error;
	}

	/**
	 * Detaches the OpenTelemetry scope from the current thread
	 * and clears the MDC. Must be called on the same thread that
	 * created this block. The span remains open for later
	 * completion via {@link #end()}. Safe to call multiple times
	 * -- subsequent calls are no-ops.
	 */
	@Override
	public void detachScope() {
		if (this.scopeDetached) {
			return;
		}
		this.scopeDetached = true;
		this.scope.close();
		clearMdc();
	}

	/**
	 * Ends the OpenTelemetry span: records error status or OK,
	 * sets deferred attributes from the supplier, and invokes
	 * the close callback. Thread-safe -- can be called from any
	 * thread (e.g., on async completion). Safe to call multiple
	 * times -- subsequent calls are no-ops.
	 */
	@Override
	public void end() {
		if (!this.ended.compareAndSet(false, true)) {
			return;
		}

		if (this.error != null) {
			this.span.setStatus(StatusCode.ERROR);
			this.span.recordException(this.error);
		} else {
			this.span.setStatus(StatusCode.OK);
		}

		if (this.attributeSupplier != null) {
			final SpanAttribute[] finalAttributes =
				this.attributeSupplier.get();
			if (finalAttributes != null) {
				setAttributes(this.span, finalAttributes);
			}
		}

		this.span.end();

		if (this.closeCallback != null) {
			this.closeCallback.run();
		}
	}

	@Override
	public void close() {
		detachScope();
		end();
	}
}
