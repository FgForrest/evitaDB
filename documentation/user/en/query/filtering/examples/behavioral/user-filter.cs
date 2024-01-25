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
        			FacetHaving(
        				"brand",
        				EntityHaving(
        					AttributeInSet("code", "amazon")
        				)
        			)
        		)
        	),
        	Require(
        		FacetSummaryOfReference(
        			"brand",
        			Impact,
        			EntityFetch(
        				AttributeContent("code")
        			)
        		)
        	)
        )
	)
);