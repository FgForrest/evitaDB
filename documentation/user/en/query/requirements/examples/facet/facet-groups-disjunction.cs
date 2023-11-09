EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		UserFilter(
        			FacetHaving(
        				"parameterValues",
        				EntityHaving(
        					AttributeInSet("code", "ram-memory-64")
        				)
        			)
        		)
        	),
        	Require(
        		FacetSummaryOfReference(
        			"parameterValues",
        			Impact,
        			FilterBy(
        				AttributeContains("code", "4")
        			),
        			FilterGroupBy(
        				AttributeInSet("code", "ram-memory", "rom-memory")
        			),
        			EntityFetch(
        				AttributeContent("code")
        			),
        			EntityGroupFetch(
        				AttributeContent("code")
        			)
        		),
        		FacetGroupsDisjunction(
        			"parameterValues",
        			FilterBy(
        				AttributeInSet("code", "ram-memory", "rom-memory")
        			)
        		)
        	)
        )
	)
);