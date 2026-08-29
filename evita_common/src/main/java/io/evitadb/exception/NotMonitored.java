/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Throwable} (typically an {@link EvitaInternalError} subtype) that is used purely for
 * control flow / signalling rather than to report a genuine fault. evitaDB's observability
 * error-monitoring agent (`ErrorMonitoringAgent`) intercepts every constructor of the monitored
 * exception hierarchies and reports it as an error metric (`io_evitadb_errors_total` and siblings);
 * annotating a type with this marker excludes it from that interception, so a benign,
 * expected-and-handled condition does not masquerade as a serious engine error.
 *
 * A prime example is the traffic recorder's memory-shortage signal, which merely means a single
 * intercepted statement will not be captured - a situation the traffic engine is designed to cope
 * with, and which is tracked properly through dedicated traffic-recorder metrics instead.
 *
 * Retention is {@link RetentionPolicy#RUNTIME}, and for the evitaDB hierarchies it must be. The agent
 * instruments only the two *roots* of those hierarchies - which is what makes it count each error
 * exactly once whatever its depth - so this marker, sitting on a concrete subtype, is not visible
 * while instrumenting. It is read reflectively at runtime instead, before any counter moves, so
 * `CLASS` retention would compile and then silently stop opting anything out. (`VirtualMachineError`
 * subtypes are still matched per concrete type, and there the marker is read from the class-file type
 * pool at premain time as before.)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface NotMonitored {
}
