@ExtendWith(DbInstanceParameterResolver.class)
public class PrefilledDataSetWebApiTest {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWithAFewData";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(
		value = DATA_SET_WITH_A_FEW_DATA,
		openWebApi = {GraphQLProvider.CODE}
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
	void exampleTestCaseWithAssertions(GraphQLTester tester, String catalogName) {
		tester.test(catalogName)
			.document("""
				query {
					countBrand
					
					get_brand(primaryKey: 1) {
					    attributes(locale: en) {
					        code
					        name
						}
				    }
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body("errors", nullValue())
			.body("data.countBrand", equalTo(1))
			.body("data.getBrand.attributes.name", equalTo("Siemens"));
	}

}