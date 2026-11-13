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

package io.evitadb.core.metric.event.cache;

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One confirmed cache verification mismatch, including detections already in flight after the first shutdown.
 * Only schema identifiers and the formula class are exported; the diagnostic witness and result values stay out
 * of this event.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Name(AbstractFormulaCacheEvent.PACKAGE_NAME + ".FormulaCacheMismatch")
@Label("Formula cache mismatch")
@Description("A cached result differed from its independent recomputation.")
@ExportInvocationMetric(label = "Confirmed formula cache mismatches")
@Getter
public class FormulaCacheMismatchEvent extends AbstractFormulaCacheEvent {

	/** Catalog schema name from the verification context. */
	@Label("Catalog name")
	@Description("Catalog schema name from the verification context.")
	@ExportMetricLabel
	private final String catalogName;

	/** Entity type from the verification request. */
	@Label("Entity type")
	@Description("Entity type from the verification request.")
	@ExportMetricLabel
	private final String entityType;

	/** Formula class supplied by the verifier. */
	@Label("Formula class")
	@Description("Formula class supplied by the verifier.")
	@ExportMetricLabel
	private final String formulaClass;

	/** Creates a point-in-time event from the same identifiers used by the existing mismatch witness. */
	public FormulaCacheMismatchEvent(
		@Nonnull String catalogName, @Nullable String entityType, @Nonnull String formulaClass
	) {
		this.begin();
		this.catalogName = catalogName;
		this.entityType = entityType;
		this.formulaClass = formulaClass;
		this.end();
	}
}
