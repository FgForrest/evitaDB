EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	Require(
        		ReferenceSummaryOfReference(
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
        		FacetGroupsNegation(
        			"parameterValues",
        			FilterBy(
        				AttributeInSet("code", "ram-memory")
        			)
        		)
        	)
        )
	)
);