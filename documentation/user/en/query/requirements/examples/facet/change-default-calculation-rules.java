final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					referenceSummaryOfReference(
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