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

package io.evitadb.externalApi.utils.path.routing.cache;

import java.lang.reflect.Constructor;
import java.util.AbstractCollection;
import java.util.Deque;

/**
 * A concurrent deque that allows direct item removal without traversal.
 *
 * @author Jason T. Greene
 */
public abstract  class ConcurrentDirectDeque<E> extends AbstractCollection<E> implements Deque<E>, java.io.Serializable {
	private static final Constructor<? extends ConcurrentDirectDeque> CONSTRUCTOR;

	static {
		boolean fast = false;
		try {
			new FastConcurrentDirectDeque();
			fast = true;
		} catch (Throwable t) {
		}

		Class<? extends ConcurrentDirectDeque> klazz = fast ? FastConcurrentDirectDeque.class : PortableConcurrentDirectDeque.class;
		try {
			CONSTRUCTOR = klazz.getConstructor();
		} catch (NoSuchMethodException e) {
			throw new NoSuchMethodError(e.getMessage());
		}
	}

	public static <K> ConcurrentDirectDeque<K> newInstance() {
		try {
			return CONSTRUCTOR.newInstance();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public abstract Object offerFirstAndReturnToken(E e);

	public abstract Object offerLastAndReturnToken(E e);

	public abstract void removeToken(Object token);
}

