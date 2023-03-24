@ExtendWith(DbInstanceParameterResolver.class)
public class PrefilledDataSetTest implements EvitaTestSupport {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWithAFewData";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(DATA_SET_WITH_A_FEW_DATA)
	void setUpData(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND);
		session.createNewEntity(ENTITY_BRAND)
			.setAttribute(
				"name", Locale.ENGLISH, "Siemens"
			)
			.upsertVia(session);
	}

	@Test
	@UseDataSet(DATA_SET_WITH_A_FEW_DATA)
	void shouldWriteTest(EvitaSessionContract session) {
		assertEquals(1, session.getEntityCollectionSize(ENTITY_BRAND));

		final SealedEntity theBrand = session.getEntity(
			ENTITY_BRAND, 1,
			attributeContent(), dataInLocales(Locale.ENGLISH)
		).orElseThrow();

		assertEquals("Siemens", theBrand.getAttribute("name"));
	}

}