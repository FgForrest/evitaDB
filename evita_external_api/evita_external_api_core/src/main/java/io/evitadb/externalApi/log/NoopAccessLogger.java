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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.log;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Access log receiver that discards and access log.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class NoopAccessLogger implements Logger {
	@Override
	public String getName() {
		return "NoopAccessLogger";
	}

	@Override
	public LoggingEventBuilder makeLoggingEventBuilder(Level level) {
		return Logger.super.makeLoggingEventBuilder(level);
	}

	@Override
	public boolean isTraceEnabled() {
		return false;
	}

	@Override
	public void trace(String s) {

	}

	@Override
	public void trace(String s, Object o) {

	}

	@Override
	public void trace(String s, Object o, Object o1) {

	}

	@Override
	public void trace(String s, Object... objects) {

	}

	@Override
	public void trace(String s, Throwable throwable) {

	}

	@Override
	public boolean isTraceEnabled(Marker marker) {
		return false;
	}

	@Override
	public void trace(Marker marker, String s) {

	}

	@Override
	public void trace(Marker marker, String s, Object o) {

	}

	@Override
	public void trace(Marker marker, String s, Object o, Object o1) {

	}

	@Override
	public void trace(Marker marker, String s, Object... objects) {

	}

	@Override
	public void trace(Marker marker, String s, Throwable throwable) {

	}

	@Override
	public boolean isDebugEnabled() {
		return false;
	}

	@Override
	public void debug(String s) {

	}

	@Override
	public void debug(String s, Object o) {

	}

	@Override
	public void debug(String s, Object o, Object o1) {

	}

	@Override
	public void debug(String s, Object... objects) {

	}

	@Override
	public void debug(String s, Throwable throwable) {

	}

	@Override
	public boolean isDebugEnabled(Marker marker) {
		return false;
	}

	@Override
	public void debug(Marker marker, String s) {

	}

	@Override
	public void debug(Marker marker, String s, Object o) {

	}

	@Override
	public void debug(Marker marker, String s, Object o, Object o1) {

	}

	@Override
	public void debug(Marker marker, String s, Object... objects) {

	}

	@Override
	public void debug(Marker marker, String s, Throwable throwable) {

	}

	@Override
	public boolean isInfoEnabled() {
		return false;
	}

	@Override
	public void info(String s) {

	}

	@Override
	public void info(String s, Object o) {

	}

	@Override
	public void info(String s, Object o, Object o1) {

	}

	@Override
	public void info(String s, Object... objects) {

	}

	@Override
	public void info(String s, Throwable throwable) {

	}

	@Override
	public boolean isInfoEnabled(Marker marker) {
		return false;
	}

	@Override
	public void info(Marker marker, String s) {

	}

	@Override
	public void info(Marker marker, String s, Object o) {

	}

	@Override
	public void info(Marker marker, String s, Object o, Object o1) {

	}

	@Override
	public void info(Marker marker, String s, Object... objects) {

	}

	@Override
	public void info(Marker marker, String s, Throwable throwable) {

	}

	@Override
	public boolean isWarnEnabled() {
		return false;
	}

	@Override
	public void warn(String s) {

	}

	@Override
	public void warn(String s, Object o) {

	}

	@Override
	public void warn(String s, Object... objects) {

	}

	@Override
	public void warn(String s, Object o, Object o1) {

	}

	@Override
	public void warn(String s, Throwable throwable) {

	}

	@Override
	public boolean isWarnEnabled(Marker marker) {
		return false;
	}

	@Override
	public void warn(Marker marker, String s) {

	}

	@Override
	public void warn(Marker marker, String s, Object o) {

	}

	@Override
	public void warn(Marker marker, String s, Object o, Object o1) {

	}

	@Override
	public void warn(Marker marker, String s, Object... objects) {

	}

	@Override
	public void warn(Marker marker, String s, Throwable throwable) {

	}

	@Override
	public boolean isErrorEnabled() {
		return false;
	}

	@Override
	public void error(String s) {

	}

	@Override
	public void error(String s, Object o) {

	}

	@Override
	public void error(String s, Object o, Object o1) {

	}

	@Override
	public void error(String s, Object... objects) {

	}

	@Override
	public void error(String s, Throwable throwable) {

	}

	@Override
	public boolean isErrorEnabled(Marker marker) {
		return false;
	}

	@Override
	public void error(Marker marker, String s) {

	}

	@Override
	public void error(Marker marker, String s, Object o) {

	}

	@Override
	public void error(Marker marker, String s, Object o, Object o1) {

	}

	@Override
	public void error(Marker marker, String s, Object... objects) {

	}

	@Override
	public void error(Marker marker, String s, Throwable throwable) {

	}
}
