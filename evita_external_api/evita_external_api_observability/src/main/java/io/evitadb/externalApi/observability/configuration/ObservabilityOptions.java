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

package io.evitadb.externalApi.observability.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.externalApi.observability.metric.PrometheusLabelNames;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Observability API specific configuration.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ObservabilityOptions extends AbstractApiOptions implements ApiWithSpecificPrefix {
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_OBSERVABILITY_PORT = 5555;
	private static final String BASE_OBSERVABILITY_PATH = "observability";

	/**
	 * Query label names that can never be exported to Prometheus, regardless of {@link #exportedQueryLabels}, because
	 * they are inherently high-cardinality (per-request or per-client). These mirror the reserved traffic-recording
	 * labels ({@link TrafficRecordingEngine}); listing any of them in {@code exportedQueryLabels} fails fast at
	 * startup. evitaDB otherwise attaches no meaning to label names - the exportable set is entirely operator-defined.
	 */
	private static final Set<String> FORBIDDEN_QUERY_LABELS = Set.of(
		TrafficRecordingEngine.LABEL_TRACE_ID,
		TrafficRecordingEngine.LABEL_CLIENT_ID,
		TrafficRecordingEngine.LABEL_IP_ADDRESS,
		TrafficRecordingEngine.LABEL_URI
	);
	/**
	 * {@link #FORBIDDEN_QUERY_LABELS} rendered as a stable, alphabetically-sorted comma-separated list, so validation
	 * error messages are deterministic (the {@link Set#of set} itself has no defined iteration order).
	 */
	private static final String FORBIDDEN_QUERY_LABELS_DISPLAY = String.join(", ", new TreeSet<>(FORBIDDEN_QUERY_LABELS));

	/**
	 * Controls the prefix Metrics API will react on.
	 * Default value is `metrics`.
	 */
	@Getter private final String prefix;
	@Getter private final TracingConfig tracing;

	@Getter @Nullable private final List<String> allowedEvents;

	/**
	 * Operator-defined list of query label names (the `label` query head constraint) whose values are surfaced as
	 * Prometheus dimensions on query metrics. The names are arbitrary - evitaDB reserves none - so each name maps to a
	 * dimension named after its Prometheus-sanitized form (see {@link PrometheusLabelNames}).
	 *
	 * Unlike {@link #allowedEvents}, an unset or empty list means nothing is exported (the safe default) - not
	 * "everything" - because query labels are arbitrary client-supplied data and an unbounded label would blow up
	 * Prometheus time-series cardinality. Opting a label in here is the operator's explicit assertion that its values
	 * are bounded. Names in {@link #FORBIDDEN_QUERY_LABELS}, or two names collapsing to the same sanitized dimension,
	 * are rejected at startup.
	 */
	@Getter @Nullable private final List<String> exportedQueryLabels;

	public ObservabilityOptions() {
		super(true, "0.0.0.0:" + DEFAULT_OBSERVABILITY_PORT, null, null, null, null);
		this.prefix = BASE_OBSERVABILITY_PATH;
		this.tracing = new TracingConfig();
		this.allowedEvents = null;
		this.exportedQueryLabels = null;
	}

	public ObservabilityOptions(@Nonnull String host) {
		super(true, host, null, null, null, null);
		this.prefix = BASE_OBSERVABILITY_PATH;
		this.tracing = new TracingConfig();
		this.allowedEvents = null;
		this.exportedQueryLabels = null;
	}

	@JsonCreator
	public ObservabilityOptions(@Nullable @JsonProperty("enabled") Boolean enabled,
	                            @Nonnull @JsonProperty("host") String host,
	                            @Nullable @JsonProperty("exposeOn") String exposeOn,
	                            @Nullable @JsonProperty("tlsMode") String tlsMode,
	                            @Nullable @JsonProperty("keepAlive") Boolean keepAlive,
	                            @Nullable @JsonProperty("prefix") String prefix,
	                            @Nullable @JsonProperty("tracing") TracingConfig tracing,
	                            @Nullable @JsonProperty("allowedEvents") List<String> allowedEvents,
	                            @Nullable @JsonProperty("exportedQueryLabels") List<String> exportedQueryLabels,
	                            @Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration);
		this.prefix = Optional.ofNullable(prefix).orElse(BASE_OBSERVABILITY_PATH);
		this.tracing = Optional.ofNullable(tracing).orElse(new TracingConfig());
		this.allowedEvents = allowedEvents;
		if (exportedQueryLabels != null) {
			for (final String label : exportedQueryLabels) {
				// a bare `-` item in the YAML list yields a null element; reject it up front with a clear message
				// rather than letting it NPE inside the forbidden-name check or dimension sanitization
				Assert.isTrue(
					label != null,
					"A `null` query label name was found in `exportedQueryLabels` configuration - " +
						"check for empty list items (a bare `-`) in the YAML."
				);
				Assert.isTrue(
					!FORBIDDEN_QUERY_LABELS.contains(label),
					() -> "Query label `" + label + "` cannot be exported to Prometheus via `exportedQueryLabels` - it " +
						"is a reserved high-cardinality label (one of: " + FORBIDDEN_QUERY_LABELS_DISPLAY + ")."
				);
			}
			// reject two labels collapsing onto the same Prometheus dimension; a clash with a built-in dimension can
			// only be caught later, at metric registration, where the event's fixed dimensions are known
			PrometheusLabelNames.assignDimensions(exportedQueryLabels, Set.of());
		}
		this.exportedQueryLabels = exportedQueryLabels;
	}
}
