@ExtendWith(DbInstanceParameterResolver.class)
public class PrefilledDataSetWebApiTest {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWithAFewData";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(
		value = DATA_SET_WITH_A_FEW_DATA,
		openWebApi = {RestProvider.CODE}
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
	void exampleTestCaseWithAssertions(RestTester tester, String catalogName) {
		tester.test(catalogName)
			.urlPathSuffix("/collections")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e("entityCount", true)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("count", equalTo(List.of(1)));

		tester.test(catalogName)
			.urlPathSuffix("/brand/get/1")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e("attributeContentAll", true)
				.e("dataInLocales", "en")
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("attributes.localized.en.name", equalTo("Siemens"));
	}

}