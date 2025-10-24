@ExtendWith(EvitaParameterResolver.class)
public class PrefilledDataSetAndCustomDataTest {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWithAFewDataAndCustomObjects";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(DATA_SET_WITH_A_FEW_DATA)
	DataCarrier setUpData(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND);
		final String testBrandName = "Siemens";
		final EntityReferenceContract brandReference = session
			.createNewEntity(ENTITY_BRAND)
			.setAttribute(
				"name", Locale.ENGLISH, testBrandName
			)
			.upsertVia(session);
		final SealedEntity brand = session.getEntity(
			ENTITY_BRAND, brandReference.getPrimaryKey(),
			entityFetchAllContent()
		).orElseThrow();

		return new DataCarrier(
			"brand", brand,
			"expectedBrandName", testBrandName
		);
	}

	@Test
	@UseDataSet(DATA_SET_WITH_A_FEW_DATA)
	void exampleTestCaseWithAssertions(
		EvitaSessionContract session,
		SealedEntity brand,
		String expectedBrandName
	) {
		assertEquals(1, session.getEntityCollectionSize(ENTITY_BRAND));

		final SealedEntity theBrand = session.getEntity(
			ENTITY_BRAND, 1,
			attributeContentAll(), dataInLocales(Locale.ENGLISH)
		).orElseThrow();

		assertEquals(expectedBrandName, theBrand.getAttribute("name"));
		assertEquals(brand, theBrand);
	}

}