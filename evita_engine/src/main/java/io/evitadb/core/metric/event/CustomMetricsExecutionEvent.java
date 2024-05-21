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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.metric.event;

import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Event;

/**
 * Common predecessor for all custom events which are to be published as metrics via Prometheus or used for JFR logging.
 *
 * All custom events has to inherit from this class!
 *
 * Event classes and all fields that should be compatible with above-mentioned mechanisms have to be annotated with
 * {@link jdk.jfr.Name} and {@link jdk.jfr.Label}. For Prometheus metrics, fields have to be additionally be decorated
 * with {@link ExportMetric} annotation.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public abstract class CustomMetricsExecutionEvent extends Event {
}
