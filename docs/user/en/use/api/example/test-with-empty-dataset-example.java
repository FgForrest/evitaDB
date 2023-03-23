public class EmptyDataSetTest implements TestFileSupport, TestConstants {
	private Evita evita;

	@BeforeEach
	void setUp() {
		// clean test directory to start from scratch
		cleanTestDirectoryWithRethrow();
		// initialize the evitaDB server
		evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					// disable automatic session termination
					// to avoid closing sessions when you stop at breakpoint
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(-1)
						.build()
				)
				.storage(
					// point evitaDB to a test directory (temp directory)
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.build()
				)
				.cache(
					// disable cache for tests
					CacheOptions.builder()
						.enabled(false)
						.build()
				)
				.build()
		);
		// create new empty catalog for evitaDB
		evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		evita.close();
		cleanTestDirectoryWithRethrow();
	}

	@Test
	void shouldWriteTest() {
		// here comes your test logic
		assertNotNull(evita);
	}
}