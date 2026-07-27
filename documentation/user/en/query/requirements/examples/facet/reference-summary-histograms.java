final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "e-readers")
					),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					page(1, 5),
					entityFetch(attributeContent("code")),
					referenceSummaryOfReferenceWithHistograms(
						"parameterValues",
						FacetStatisticsDepth.COUNTS,
						entityFetch(attributeContent("code")),
						entityGroupFetch(attributeContent("code")),
						histogramStatistics(20, "intervalParameterValues")
					)
				)
			)
		);
	}
);
