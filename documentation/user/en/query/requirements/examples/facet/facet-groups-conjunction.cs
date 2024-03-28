EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		UserFilter(
        			FacetHaving(
        				"groups",
        				EntityHaving(
        					AttributeInSet("code", "sale")
        				)
        			)
        		)
        	),
        	Require(
        		FacetSummaryOfReference(
        			"groups",
        			Impact,
        			EntityFetch(
        				AttributeContent("code")
        			)
        		),
        		FacetGroupsConjunction("groups")
        	)
        )
	)
);