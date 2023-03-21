@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public class EmptyDataSetAlternativeTest implements TestFileSupport, TestConstants {
	private static final String EXAMPLE_DATASET = "exampleDataset";

	@DataSet(EXAMPLE_DATASET)
	void initEvita() {
		log.info("Evita initialized ...");
	}

	@Test
	@UseDataSet(EXAMPLE_DATASET)
	void shouldWriteTest(Evita evita) {
		// here comes your test logic
		assertNotNull(evita);
	}

}