@ExtendWith(DbInstanceParameterResolver.class)
public class PrefilledDataSetWebApiTest implements EvitaTestSupport {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWithAFewData";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(
		value = DATA_SET_WITH_A_FEW_DATA,
		openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}
	)
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
	void shouldWriteTest(EvitaClient client, String catalogName) {
		client.queryCatalog(
			catalogName,
			session -> {
				assertEquals(1, session.getEntityCollectionSize(ENTITY_BRAND));

				final SealedEntity theBrand = session.getEntity(
					ENTITY_BRAND, 1,
					attributeContent(), dataInLocales(Locale.ENGLISH)
				).orElseThrow();

				assertEquals("Siemens", theBrand.getAttribute("name"));
			}
		);
	}

}