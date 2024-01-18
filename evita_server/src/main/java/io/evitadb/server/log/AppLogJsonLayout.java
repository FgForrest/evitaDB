/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import ch.qos.logback.core.util.CachingDateFormatter;
import io.evitadb.api.ClientContext;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;


/**
 * Logs events as JSON objects for easier digestion by log aggregators like Loki. We construct the JSON manually
 * so we don't slow down the logging process by using Jackson.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class AppLogJsonLayout extends LayoutBase<ILoggingEvent> {

	private static final String[] ESCAPED_CHARS = new String[] { "\r\n", "\n", "\r", "\f", "\b", "\\", "\"" };
	private static final String[] REPLACEMENTS_FOR_ESCAPED_CHARS = new String[] { "\\r\\n", "\\n", "\\r", "\\f", "\\b", "\\\\", "\\\"" };

	private final CachingDateFormatter cachingDateFormatter = new CachingDateFormatter("yyyy-MM-dd'T'HH:mm:ss.SSSZ", null);

	private boolean logTimestamp = true;

	public void setLogTimestamp(boolean logTimestamp) {
		this.logTimestamp = logTimestamp;
	}

	@Override
	public String doLayout(ILoggingEvent event) {
		final StringBuilder buf = new StringBuilder(128);

		buf.append("{");

		if (logTimestamp) {
			buf.append("\"timestamp\":\"");
			buf.append(cachingDateFormatter.format(event.getTimeStamp()));
			buf.append("\"");

			buf.append(",");
		}

		buf.append("\"level\":\"");
		buf.append(event.getLevel().toString());
		buf.append("\"");

		buf.append(",");

		buf.append("\"message\":\"");
		buf.append(escapeMessage(event.getFormattedMessage()));
		buf.append("\"");

		buf.append(",");

		final String clientId = event.getMDCPropertyMap().get(ClientContext.MDC_CLIENT_ID_PROPERTY);
		buf.append("\"client_id\":");
		if (clientId == null) {
			buf.append("null");
		} else {
			buf.append("\"");
			buf.append(clientId);
			buf.append("\"");
		}

		buf.append(",");

		final String requestId = event.getMDCPropertyMap().get(ClientContext.MDC_REQUEST_ID_PROPERTY);
		buf.append("\"request_id\":");
		if (requestId == null) {
			buf.append("null");
		} else {
			buf.append("\"");
			buf.append(requestId);
			buf.append("\"");
		}

		buf.append("}");
		buf.append(CoreConstants.LINE_SEPARATOR);

		return buf.toString();
	}

	private String escapeMessage(@Nonnull String message) {
		return StringUtils.replaceEach(message, ESCAPED_CHARS, REPLACEMENTS_FOR_ESCAPED_CHARS);
	}
}
