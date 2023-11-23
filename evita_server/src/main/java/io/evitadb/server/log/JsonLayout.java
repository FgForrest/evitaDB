/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.server.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import ch.qos.logback.core.util.CachingDateFormatter;

import javax.annotation.Nonnull;


/**
 * Logs events as JSON objects for easier digestion by log aggregators like Loki. We construct the JSON manually
 *  * so we don't slow down the logging process by using Jackson.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class JsonLayout extends LayoutBase<ILoggingEvent> {

	protected final CachingDateFormatter cachingDateFormatter = new CachingDateFormatter("yyyy-MM-dd'T'HH:mm:ss.SSSZ", null);

	protected boolean logTimestamp = false;

	public void setLogTimestamp(boolean logTimestamp) {
		this.logTimestamp = logTimestamp;
	}

	protected String escapeMessage(@Nonnull String message) {
		return message
			.replace("\r\n", "\\r\\n")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\f", "\\f")
			.replace("\b", "\\b")
			.replace("\\", "\\\\")
			.replace("\"", "\\\"");
	}
}
