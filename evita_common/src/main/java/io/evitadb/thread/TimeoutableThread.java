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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.thread;

import lombok.Getter;
import lombok.Setter;

/**
 * Extension of base {@link Thread} which has information if underlying execution timed out (is running longer than
 * the timeout specifies).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class TimeoutableThread extends Thread {

	/**
	 * Execution start time in nanoseconds.
	 */
	@Getter @Setter private Long startTime;

	public TimeoutableThread() {
		super();
	}

	public TimeoutableThread(Runnable target) {
		super(target);
	}

	public TimeoutableThread(ThreadGroup group, Runnable target) {
		super(group, target);
	}

	public TimeoutableThread(String name) {
		super(name);
	}

	public TimeoutableThread(ThreadGroup group, String name) {
		super(group, name);
	}

	public TimeoutableThread(Runnable target, String name) {
		super(target, name);
	}

	public TimeoutableThread(ThreadGroup group, Runnable target, String name) {
		super(group, target, name);
	}

	public TimeoutableThread(ThreadGroup group, Runnable target, String name, long stackSize) {
		super(group, target, name, stackSize);
	}

	public TimeoutableThread(ThreadGroup group, Runnable target, String name, long stackSize, boolean inheritThreadLocals) {
		super(group, target, name, stackSize, inheritThreadLocals);
	}

	/**
	 * Checks if this thread is running longer than given timeout.
	 *
	 * @param timeout timeout in nano seconds
	 */
	public boolean isTimedOut(long timeout) {
		return startTime != null &&
			(System.nanoTime() - startTime) > timeout;
	}
}
