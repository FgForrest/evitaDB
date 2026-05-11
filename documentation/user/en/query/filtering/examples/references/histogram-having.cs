EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
		Query(
			Collection("Product"),
			FilterBy(
				HierarchyWithin(
					"categories",
					AttributeEquals("code", "e-readers")
				),
				UserFilter(
					HistogramHaving(
						"parameterValues", "intervalParameterValues",
						200, 400,
						EntityHaving(AttributeEquals("code", "weight"))
					),
					HistogramHaving(
						"parameterValues", "intervalParameterValues",
						6, 10,
						EntityHaving(AttributeEquals("code", "thickness"))
					)
				)
			),
			Require(
				Page(1, 5),
				EntityFetch(AttributeContent("code")),
				ReferenceSummaryOfReferenceWithHistograms(
					"parameterValues",
					FacetStatisticsDepth.Counts,
					EntityFetch(AttributeContent("code")),
					EntityGroupFetch(AttributeContent("code")),
					HistogramStatistics(20, "intervalParameterValues")
				)
			)
		)
	)
);
