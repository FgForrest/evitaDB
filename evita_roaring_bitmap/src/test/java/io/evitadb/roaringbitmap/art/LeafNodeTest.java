package io.evitadb.roaringbitmap.art;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link LeafNode}, the ART leaf that maps a fixed-length key to a
 * container index. Ported from the upstream RoaringBitmap test suite and retained as a
 * regression guard for the vendored `io.evitadb.roaringbitmap` module.
 */
@DisplayName("LeafNode")
public class LeafNodeTest {

	@Test
	@DisplayName("clone produces an equal leaf node")
	public void testClone() {
		LeafNode leafOne = new LeafNode(new byte[]{1, 2, 3, 4, 5, 0}, 10);
		LeafNode cloned = leafOne.clone();

		Assertions.assertEquals(leafOne.toString(), cloned.toString());
	}
}
