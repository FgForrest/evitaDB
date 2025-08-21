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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a new file is opened for isolated WAL storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".IsolatedWalFileOpened")
@Description("Event fired when a new file is opened for isolated WAL storage.")
@Label("Isolated WAL file opened")
@ExportInvocationMetric(label = "Opened files for isolated WAL storage.")
public class IsolatedWalFileOpenedEvent extends AbstractTransactionEvent {

	public IsolatedWalFileOpenedEvent() {
		super(null);
	}

	public IsolatedWalFileOpenedEvent(@Nonnull String catalogName) {
		super(catalogName);
	}

}
