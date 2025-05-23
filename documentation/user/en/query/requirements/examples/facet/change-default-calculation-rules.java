final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					facetSummaryOfReference(
						"parameterValues",
						IMPACT,
						entityFetch(
							attributeContent("code")
						)
					),
					facetCalculationRules(CONJUNCTION, EXCLUSIVITY)
				)
			)
		);
	}
);
