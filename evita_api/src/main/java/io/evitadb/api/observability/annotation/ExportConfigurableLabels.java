/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.observability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a `String` field of a JFR event as a bag of arbitrary, client-supplied labels that may be selectively
 * surfaced as Prometheus dimensions.
 *
 * Unlike {@link ExportMetricLabel} - which pins one dimension per annotated field, with a name known at compile
 * time - this annotation does not itself add any dimension. The set of dimensions it contributes is decided at
 * runtime by the observability API's `exportedQueryLabels` configuration: for each label name the operator opts in,
 * the metric handler adds one dimension whose value is looked up (by that name) from this field's bag. evitaDB does
 * not know or reserve any specific label name - the names come entirely from configuration.
 *
 * The bag is encoded as pairs `name=value` joined by a comma, e.g. `job_name=feed,tenant=acme` (the same encoding
 * the annotated field's own description documents for human consumption). When `exportedQueryLabels` is unset or
 * empty - the safe default - this field contributes no dimensions at all and is never parsed.
 *
 * Because label values are arbitrary client data, exporting an unbounded label as a Prometheus dimension would blow
 * up time-series cardinality; opting a label in via configuration is therefore an explicit statement by the operator
 * that its values are bounded. See `ObservabilityOptions#getExportedQueryLabels()` in the observability external API
 * module for the configuration contract (including the reserved names that can never be exported).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see ExportMetricLabel
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportConfigurableLabels {
}
