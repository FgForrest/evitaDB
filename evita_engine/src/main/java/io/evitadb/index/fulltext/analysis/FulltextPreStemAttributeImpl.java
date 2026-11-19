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

package io.evitadb.index.fulltext.analysis;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

import javax.annotation.Nonnull;
import java.util.Objects;

/** Reuses a character buffer per stream and deep-copies it only when Lucene captures a token state. */
final class FulltextPreStemAttributeImpl extends AttributeImpl implements FulltextPreStemAttribute {
	/** Owned normalized characters; mutable live and saved states never share this array. */
	@Nonnull private char[] buffer = new char[16];
	/** Valid characters; zero also represents cleared or unavailable capture. */
	private int length;
	/** Whether the capture reuses the final term instead of this buffer. */
	private boolean finalTerm;

	/** Creates an initially unavailable capture. */
	FulltextPreStemAttributeImpl() {
	}

	@Override
	public void copyBuffer(@Nonnull char[] source, int offset, int length) {
		this.buffer = ArrayUtil.grow(this.buffer, length);
		System.arraycopy(source, offset, this.buffer, 0, length);
		this.length = length;
		this.finalTerm = false;
	}

	@Override
	public void useFinalTerm() {
		this.length = 0;
		this.finalTerm = true;
	}

	@Override
	public boolean isFinalTerm() {
		return this.finalTerm;
	}

	@Override
	public int length() {
		return this.length;
	}

	@Override
	public char charAt(int index) {
		return this.buffer[Objects.checkIndex(index, this.length)];
	}

	@Nonnull
	@Override
	public CharSequence subSequence(int start, int end) {
		Objects.checkFromToIndex(start, end, this.length);
		return new String(this.buffer, start, end - start);
	}

	@Nonnull
	@Override
	public String toString() {
		return new String(this.buffer, 0, this.length);
	}

	@Override
	public void clear() {
		this.length = 0;
		this.finalTerm = false;
	}

	@Override
	public void copyTo(@Nonnull AttributeImpl target) {
		final FulltextPreStemAttribute capture = (FulltextPreStemAttribute) target;
		capture.copyBuffer(this.buffer, 0, this.length);
		if (this.finalTerm) {
			capture.useFinalTerm();
		}
	}

	@Nonnull
	@Override
	public FulltextPreStemAttributeImpl clone() {
		final FulltextPreStemAttributeImpl clone = (FulltextPreStemAttributeImpl) super.clone();
		clone.buffer = this.buffer.clone();
		return clone;
	}

	@Override
	public void reflectWith(@Nonnull AttributeReflector reflector) {
		reflector.reflect(FulltextPreStemAttribute.class, "term", toString());
		reflector.reflect(FulltextPreStemAttribute.class, "finalTerm", this.finalTerm);
	}
}
