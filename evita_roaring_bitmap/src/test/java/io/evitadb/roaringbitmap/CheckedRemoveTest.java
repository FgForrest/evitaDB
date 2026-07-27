package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Verifies `checkedRemove` keeps the bitmap valid through bitmap-to-array container downgrade.
 */
@DisplayName("PersistentRoaringBitmap checkedRemove container downgrade")
public class CheckedRemoveTest {

	@Test
	void testCheckedRemove() {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		// We add enough values so that the container becomes a
		// bitmap container.
		for (int i = 0; i < 10000; i++) {
			bitmap.add(i * 2);
		}
		// Next we remove them one by one.
		// At some point, the container should become an array container.
		for (int i = 0; i < 10000; i++) {
			bitmap.checkedRemove(i * 2);
			assertTrue(bitmap.validate());
		}
	}

}
