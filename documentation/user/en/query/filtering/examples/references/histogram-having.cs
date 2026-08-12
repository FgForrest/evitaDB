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
        				"parameterValues",
        				"intervalParameterValues",
        				200m,
        				400m,
        				GroupHaving(
        					AttributeEquals("code", "weight")
        				)
        			),
        			HistogramHaving(
        				"parameterValues",
        				"intervalParameterValues",
        				6m,
        				10m,
        				GroupHaving(
        					AttributeEquals("code", "thickness")
        				)
        			)
        		)
        	),
        	Require(
        		Page(1, 5),
        		EntityFetch(
        			AttributeContent("code")
        		),
        		ReferenceSummaryOfReferenceWithHistograms(
        			"parameterValues",
        			None,
        			EntityFetch(
        				AttributeContent("code")
        			),
        			EntityGroupFetch(
        				AttributeContent("code")
        			),
        			HistogramStatistics(20, Standard, "intervalParameterValues")
        		)
        	)
        )
	)
);