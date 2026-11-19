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

package io.evitadb.index.mutation.local;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures reference-histogram withdrawal diagnostics, including those logged by the transaction pool.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class HistogramWithdrawalLogCapture {
	/** Prevents instantiation of the log capture utility. */
	private HistogramWithdrawalLogCapture() {
	}

	/**
	 * Runs an action and returns the error messages about the named histogram.
	 *
	 * @param histogram the histogram name the messages must carry
	 * @param action the action to run
	 * @return the matching messages in logging order
	 */
	@Nonnull
	public static List<String> captureReports(@Nonnull String histogram, @Nonnull Runnable action) {
		final String histogramFragment = "histogram `" + histogram + "`";
		return captureEvents(ReferenceIndexMutator.class, action).stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.map(ILoggingEvent::getFormattedMessage)
			.filter(message -> message.contains(histogramFragment))
			.toList();
	}

	/**
	 * Runs an action with a list appender, then detaches it and returns a snapshot of the captured events.
	 *
	 * @param loggerType the class whose logger to capture
	 * @param action the action to run
	 * @return the events in logging order
	 */
	@Nonnull
	static List<ILoggingEvent> captureEvents(@Nonnull Class<?> loggerType, @Nonnull Runnable action) {
		final Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(@Nonnull ILoggingEvent event) {
				// the event reads its message, thread name and MDC lazily - fix them on the thread that logged it
				event.prepareForDeferredProcessing();
				super.append(event);
			}
		};
		appender.list = new CopyOnWriteArrayList<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		return List.copyOf(appender.list);
	}
}
