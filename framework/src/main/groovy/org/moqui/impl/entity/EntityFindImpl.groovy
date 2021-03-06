/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.sql.ResultSet
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp

import net.sf.ehcache.Element

import org.apache.commons.collections.set.ListOrderedSet
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityException

import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.CacheImpl
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ListCondition

class EntityFindImpl implements EntityFind {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityFindImpl.class)

    protected final EntityFacadeImpl efi

    protected String entityName
    protected EntityDefinition entityDef = null
    protected EntityDynamicViewImpl dynamicView = null

    protected Map<String, Object> simpleAndMap = null
    protected EntityConditionImplBase whereEntityCondition = null
    protected EntityConditionImplBase havingEntityCondition = null

    /** This is always a ListOrderedSet so that we can get the results in a consistent order */
    protected ListOrderedSet fieldsToSelect = null
    protected List<String> orderByFields = null

    protected Boolean useCache = null

    protected boolean distinct = false
    protected Integer offset = null
    protected Integer limit = null
    protected boolean forUpdate = false

    protected int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY
    protected Integer fetchSize = null
    protected Integer maxRows = null

    EntityFindImpl(EntityFacadeImpl efi, String entityName) {
        this.efi = efi
        this.entityName = entityName
    }

    /** @see org.moqui.entity.EntityFind#entity(String) */
    EntityFind entity(String entityName) { this.entityName = entityName; return this }

    /** @see org.moqui.entity.EntityFind#getEntity() */
    String getEntity() { return this.entityName }

    /** @see org.moqui.entity.EntityFind#makeEntityDynamicView() */
    EntityDynamicView makeEntityDynamicView() {
        if (this.dynamicView) return this.dynamicView
        this.dynamicView = new EntityDynamicViewImpl(this)
        return this.dynamicView
    }

    // ======================== Conditions (Where and Having) =================

    /** @see org.moqui.entity.EntityFind#condition(String, Object) */
    EntityFind condition(String fieldName, Object value) {
        if (!this.simpleAndMap) this.simpleAndMap = new HashMap()
        this.simpleAndMap.put(fieldName, value)
        return this
    }

    EntityFind condition(String fieldName, EntityCondition.ComparisonOperator operator, Object value) {
        condition(efi.conditionFactory.makeCondition(fieldName, operator, value))
        return this
    }

    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName) {
        condition(efi.conditionFactory.makeCondition(fieldName, operator, toFieldName))
        return this
    }

    /** @see org.moqui.entity.EntityFind#condition(Map<String,?>) */
    EntityFind condition(Map<String, ?> fields) {
        if (!fields) return this
        if (!this.simpleAndMap) this.simpleAndMap = new HashMap()
        getEntityDef().setFields(fields, this.simpleAndMap, true, null, null)
        return this
    }

    /** @see org.moqui.entity.EntityFind#condition(EntityCondition) */
    EntityFind condition(EntityCondition condition) {
        if (!condition) return this
        if (whereEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (whereEntityCondition instanceof ListCondition) {
                ((ListCondition) whereEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                whereEntityCondition =
                    (EntityConditionImplBase) efi.conditionFactory.makeCondition([whereEntityCondition, condition])
            }
        } else {
            whereEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    EntityFind conditionDate(String fromFieldName, String thruFieldName, java.sql.Timestamp compareStamp) {
        condition(efi.conditionFactory.makeConditionDate(fromFieldName, thruFieldName, compareStamp))
        return this
    }

    /** @see org.moqui.entity.EntityFind#havingCondition(EntityCondition) */
    EntityFind havingCondition(EntityCondition condition) {
        if (!condition) return this
        if (havingEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (havingEntityCondition instanceof ListCondition) {
                ((ListCondition) havingEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                havingEntityCondition =
                    (EntityConditionImplBase) efi.conditionFactory.makeCondition([havingEntityCondition, condition])
            }
        } else {
            havingEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    /** @see org.moqui.entity.EntityFind#getWhereEntityCondition() */
    EntityCondition getWhereEntityCondition() {
        if (this.simpleAndMap) {
            EntityCondition simpleAndMapCond = this.efi.conditionFactory.makeCondition(this.simpleAndMap)
            if (this.whereEntityCondition) {
                return this.efi.conditionFactory.makeCondition(simpleAndMapCond, EntityCondition.JoinOperator.AND, this.whereEntityCondition)
            } else {
                return simpleAndMapCond
            }
        } else {
            return this.whereEntityCondition
        }
    }

    /** @see org.moqui.entity.EntityFind#getHavingEntityCondition() */
    EntityCondition getHavingEntityCondition() {
        return this.havingEntityCondition
    }

    /** @see org.moqui.entity.EntityFind#searchFormInputs(String,String,boolean) */
    EntityFind searchFormInputs(String inputFieldsMapName, String defaultOrderBy, boolean alwaysPaginate) {
        Map inf = inputFieldsMapName ? (Map) efi.ecfi.executionContext.context[inputFieldsMapName] :
            (efi.ecfi.executionContext.web ? efi.ecfi.executionContext.web.parameters : efi.ecfi.executionContext.context)
        EntityDefinition ed = getEntityDef()

        for (String fn in ed.getAllFieldNames()) {
            // NOTE: do we need to do type conversion here?

            // this will handle text-find
            if (inf.containsKey(fn) || inf.containsKey(fn + "_op")) {
                Object value = inf.get(fn)
                String op = inf.get(fn + "_op") ?: "contains"
                boolean not = (inf.get(fn + "_not") == "Y")
                boolean ic = (inf.get(fn + "_ic") == "Y")

                EntityCondition ec = null
                switch (op) {
                case "equals":
                    if (value) {
                        ec = efi.conditionFactory.makeCondition(fn,
                            not ? EntityCondition.ComparisonOperator.NOT_EQUAL : EntityCondition.ComparisonOperator.EQUALS,
                            value)
                        if (ic) ec.ignoreCase()
                    }
                    break;
                case "like":
                    if (value) {
                        ec = efi.conditionFactory.makeCondition(fn,
                            not ? EntityCondition.ComparisonOperator.NOT_LIKE : EntityCondition.ComparisonOperator.LIKE,
                            value)
                        if (ic) ec.ignoreCase()
                    }
                    break;
                case "contains":
                    if (value) {
                        ec = efi.conditionFactory.makeCondition(fn,
                            not ? EntityCondition.ComparisonOperator.NOT_LIKE : EntityCondition.ComparisonOperator.LIKE,
                            "%${value}%")
                        if (ic) ec.ignoreCase()
                    }
                    break;
                case "empty":
                    ec = efi.conditionFactory.makeCondition(
                        efi.conditionFactory.makeCondition(fn,
                        not ? EntityCondition.ComparisonOperator.NOT_EQUAL : EntityCondition.ComparisonOperator.EQUALS,
                        null),
                        not ? EntityCondition.JoinOperator.AND : EntityCondition.JoinOperator.OR,
                        efi.conditionFactory.makeCondition(fn,
                        not ? EntityCondition.ComparisonOperator.NOT_EQUAL : EntityCondition.ComparisonOperator.EQUALS,
                        ""))
                    break;
                }
                if (ec != null) this.condition(ec)
            } else {
                // these will handle range-find and date-find
                if (inf.containsKey(fn + "_from")) this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, inf.get(fn + "_from")))
                if (inf.containsKey(fn + "_thru")) this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.ComparisonOperator.LESS_THAN, inf.get(fn + "_thru")))
            }
        }

        // always look for an orderByField parameter too
        String orderByString = inf.get("orderByField") ?: defaultOrderBy
        if (orderByString) {
            if (orderByString.contains(",")) {
                for (String obsPart in orderByString.split(",")) this.orderBy(obsPart.trim())
            } else {
                this.orderBy(orderByString)
            }
        }

        // look for the pageIndex and optional pageSize parameters
        if (alwaysPaginate || inf.get("pageIndex")) {
            int pageIndex = (inf.get("pageIndex") ?: 0) as int
            int pageSize = (inf.get("pageSize") ?: (this.limit ?: 20)) as int
            offset(pageIndex * pageSize)
            limit(pageSize)
        }

        // if there is a pageNoLimit clear out the limit regardless of other settings
        if (inf.get("pageNoLimit") == "true") {
            this.offset = null
            this.limit = null
        }

        return this
    }

    int getPageIndex() { return offset == null ? 0 : offset/getPageSize() }
    int getPageSize() { return limit ?: 20 }

    EntityFind findNode(Node node) {
        ExecutionContext ec = this.efi.ecfi.executionContext

        this.entity((String) node["@entity-name"])
        if (node["@cache"]) { this.useCache(node["@cache"] == "true") }
        if (node["@for-update"]) this.forUpdate(node["@for-update"] == "true")
        if (node["@distinct"]) this.distinct(node["@distinct"] == "true")
        if (node["@offset"]) this.offset(node["@offset"] as Integer)
        if (node["@limit"]) this.limit(node["@limit"] as Integer)
        for (Node sf in node["select-field"]) this.selectField((String) sf["@field-name"])
        for (Node ob in node["order-by"]) this.orderBy((String) ob["@field-name"])

        if (!this.getUseCache()) {
            for (Node df in node["date-filter"])
                this.condition(ec.entity.conditionFactory.makeConditionDate((String) df["@from-field-name"] ?: "fromDate",
                        (String) df["@thru-field-name"] ?: "thruDate",
                        (df["@valid-date"] ? ec.resource.evaluateContextField((String) df["@valid-date"], null) as Timestamp : ec.user.nowTimestamp)))
        }

        for (Node ecn in node["econdition"])
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(ecn))
        for (Node ecs in node["econditions"])
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionConditions(ecs))
        for (Node eco in node["econdition-object"])
            this.condition((EntityCondition) ec.resource.evaluateContextField((String) eco["@field"], null))

        if (node["search-form-inputs"]) {
            Node sfiNode = (Node) node["search-form-inputs"][0]
            searchFormInputs((String) sfiNode["@input-fields-map"], (String) sfiNode["@default-order-by"], (sfiNode["@paginate"] ?: "true") as boolean)
        }
        if (node["having-econditions"]) {
            for (Node havingCond in node["having-econditions"])
                this.havingCondition(efi.conditionFactory.makeActionCondition(havingCond))
        }

        // logger.info("TOREMOVE Added findNode\n${node}\n${this.toString()}")

        return this
    }

    // ======================== General/Common Options ========================

    /** @see org.moqui.entity.EntityFind#selectField(String) */
    EntityFind selectField(String fieldToSelect) {
        if (!this.fieldsToSelect) this.fieldsToSelect = new ListOrderedSet()
        if (fieldToSelect) this.fieldsToSelect.add(fieldToSelect)
        return this
    }

    /** @see org.moqui.entity.EntityFind#selectFields(Collection<String>) */
    EntityFind selectFields(Collection<String> fieldsToSelect) {
        if (!this.fieldsToSelect) this.fieldsToSelect = new ListOrderedSet()
        if (fieldsToSelect) this.fieldsToSelect.addAll(fieldsToSelect)
        return this
    }

    /** @see org.moqui.entity.EntityFind#getSelectFields() */
    List<String> getSelectFields() { return this.fieldsToSelect ? this.fieldsToSelect.asList() : null }

    /** @see org.moqui.entity.EntityFind#orderBy(String) */
    EntityFind orderBy(String orderByFieldName) {
        if (!this.orderByFields) this.orderByFields = new ArrayList()
        if (orderByFieldName) this.orderByFields.add(orderByFieldName)
        return this
    }

    /** @see org.moqui.entity.EntityFind#orderBy(List<String>) */
    EntityFind orderBy(List<String> orderByFieldNames) {
        if (!this.orderByFields) this.orderByFields = new ArrayList()
        if (orderByFieldNames) this.orderByFields.addAll(orderByFieldNames)
        return this
    }

    /** @see org.moqui.entity.EntityFind#getOrderBy() */
    List<String> getOrderBy() { return this.orderByFields ? Collections.unmodifiableList(this.orderByFields) : null }

    /** @see org.moqui.entity.EntityFind#useCache(boolean) */
    EntityFind useCache(Boolean useCache) { this.useCache = useCache; return this }

    /** @see org.moqui.entity.EntityFind#getUseCache() */
    boolean getUseCache() { return this.useCache }

    // ======================== Advanced Options ==============================

    /** @see org.moqui.entity.EntityFind#distinct(boolean) */
    EntityFind distinct(boolean distinct) { this.distinct = distinct; return this }
    /** @see org.moqui.entity.EntityFind#getDistinct() */
    boolean getDistinct() { return this.distinct }

    /** @see org.moqui.entity.EntityFind#offset(int) */
    EntityFind offset(Integer offset) { this.offset = offset; return this }
    /** @see org.moqui.entity.EntityFind#getOffset() */
    Integer getOffset() { return this.offset }

    /** @see org.moqui.entity.EntityFind#limit(int) */
    EntityFind limit(Integer limit) { this.limit = limit; return this }
    /** @see org.moqui.entity.EntityFind#getLimit() */
    Integer getLimit() { return this.limit }

    /** @see org.moqui.entity.EntityFind#forUpdate(boolean) */
    EntityFind forUpdate(boolean forUpdate) { this.forUpdate = forUpdate; return this }
    /** @see org.moqui.entity.EntityFind#getForUpdate() */
    boolean getForUpdate() { return this.forUpdate }

    // ======================== JDBC Options ==============================

    /** @see org.moqui.entity.EntityFind#resultSetType(int) */
    EntityFind resultSetType(int resultSetType) { this.resultSetType = resultSetType; return this }
    /** @see org.moqui.entity.EntityFind#getResultSetType() */
    int getResultSetType() { return this.resultSetType }

    /** @see org.moqui.entity.EntityFind#resultSetConcurrency(int) */
    EntityFind resultSetConcurrency(int resultSetConcurrency) {
        this.resultSetConcurrency = resultSetConcurrency
        return this
    }
    /** @see org.moqui.entity.EntityFind#getResultSetConcurrency() */
    int getResultSetConcurrency() { return this.resultSetConcurrency }

    /** @see org.moqui.entity.EntityFind#fetchSize(int) */
    EntityFind fetchSize(Integer fetchSize) { this.fetchSize = fetchSize; return this }
    /** @see org.moqui.entity.EntityFind#getFetchSize() */
    Integer getFetchSize() { return this.fetchSize }

    /** @see org.moqui.entity.EntityFind#fetchSize(int) */
    EntityFind maxRows(Integer maxRows) { this.maxRows = maxRows; return this }
    /** @see org.moqui.entity.EntityFind#getFetchSize() */
    Integer getMaxRows() { return this.maxRows }

    // ======================== Run Find Methods ==============================

    /** @see org.moqui.entity.EntityFind#one() */
    EntityValue one() throws EntityException {
        if (this.dynamicView) {
            throw new IllegalArgumentException("Dynamic View not supported for 'one' find.")
        }

        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.getExecutionContext()

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-one", true)

        // if over-constrained (anything in addition to a full PK), just use the full PK
        EntityConditionImplBase whereCondition
        if (ed.containsPrimaryKey(simpleAndMap)) {
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(ed.getPrimaryKeys(simpleAndMap))
        } else {
            whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        }

        // no condition means no condition/parameter set, so return null for find.one()
        if (!whereCondition) {
            // pop the ArtifactExecutionInfo
            ec.artifactExecution.pop()
            return null
        }

        CacheImpl entityOneCache = null
        boolean doCache = this.shouldCache()
        if (doCache) {
            entityOneCache = this.efi.getCacheOne(this.entityName)
            Element cacheElement = entityOneCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityOneCache.removeElement(cacheElement)
                } else {
                    EntityValue cacheHit = (EntityValue) cacheElement.objectValue
                    // if (logger.traceEnabled) logger.trace("Found entry in cache for entity [${ed.entityName}] and condition [${whereCondition}]: ${cacheHit}")
                    efi.runEecaRules(ed.getEntityName(), cacheHit, "find-one", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        // for find one we'll always use the basic result set type and concurrency:
        this.resultSetType(ResultSet.TYPE_FORWARD_ONLY)
        this.resultSetConcurrency(ResultSet.CONCUR_READ_ONLY)

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getAllFieldNames())
        // SELECT fields
        efb.makeSqlSelectFields(this.fieldsToSelect)
        // FROM Clause
        efb.makeSqlFromClause()

        // WHERE clause only for one/pk query
        // NOTE: do this here after caching because this will always be added on and isn't a part of the original where
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (viewWhere) whereCondition =
            (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, JoinOperator.AND, viewWhere)
        efb.startWhereClause()
        whereCondition.makeSqlWhere(efb)

        if (this.forUpdate) efb.makeForUpdate()

        // run the SQL now that it is built
        EntityValueImpl newEntityValue = null
        try {
            efi.getEntityDbMeta().checkTableRuntime(ed)
            newEntityValue = (EntityValueImpl) internalOne(efb, whereCondition.toString())
        } catch (SQLException e) {
            throw new EntityException("Error finding value", e)
        } finally {
            efb.closeAll()
        }

        // put it in whether null or not
        if (doCache) {
            entityOneCache.put(whereCondition, newEntityValue)
            // need to register an RA just in case the condition was not actually a primary key
            efi.registerCacheOneRa(this.entityName, whereCondition, newEntityValue)
        }

        if (logger.traceEnabled) logger.trace("Find one on entity [${ed.entityName}] with condition [${whereCondition}] found value [${newEntityValue}]")

        // final ECA trigger
        efi.runEecaRules(ed.getEntityName(), newEntityValue, "find-one", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "one", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), newEntityValue ? 1 : 0)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return newEntityValue
    }

    protected EntityValue internalOne(EntityFindBuilder efb, String condSql) {
        efb.makeConnection()
        efb.makePreparedStatement()
        efb.setPreparedStatementValues()

        EntityValue newEntityValue = null
        ResultSet rs = efb.executeQuery()
        if (rs.next()) {
            newEntityValue = new EntityValueImpl(this.entityDef, this.efi)
            int j = 1
            for (String fieldName in this.fieldsToSelect) {
                EntityQueryBuilder.getResultSetValue(rs, j, this.entityDef.getFieldNode(fieldName), newEntityValue, this.efi)
                j++
            }
        } else {
            logger.trace("Result set was empty for find on entity [${this.entityName}] with condition [${condSql}]")
        }
        if (rs.next()) {
            logger.trace("Found more than one result for condition [${condSql}] on entity [${this.entityDef.getEntityName()}]")
        }
        return newEntityValue
    }

    /** @see org.moqui.entity.EntityFind#list() */
    EntityList list() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-list", true)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        CacheImpl entityListCache = null
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        if (doCache) {
            entityListCache = this.efi.getCacheList(this.entityName)
            Element cacheElement = entityListCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityListCache.removeElement(cacheElement)
                } else {
                    EntityList cacheHit = (EntityList) cacheElement.objectValue
                    efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-list", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        EntityListIterator eli = this.iteratorPlain()
        EntityListImpl el

        Node databaseNode = this.efi.getDatabaseNode(this.efi.getEntityGroupName(ed.getEntityName()))
        if (this.limit != null && databaseNode."@offset-style" == "cursor") {
            el = eli.getPartialList(this.offset ?: 0, this.limit, true)
        } else {
            el = eli.getCompleteList(true)
        }

        if (doCache) {
            EntityListImpl elToCache = el ?: EntityListImpl.EMPTY
            elToCache.setFromCache(true)
            entityListCache.put(whereCondition, elToCache)
            efi.registerCacheListRa(this.entityName, whereCondition, elToCache)
        }
        // run the final rules
        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-list", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "list", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), el ? el.size() : 0)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return el
    }

    /** @see org.moqui.entity.EntityFind#iterator() */
    EntityListIterator iterator() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        if (ed.isViewEntity() && (!entityNode."member-entity" || !entityNode."alias"))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-iterator", true)
        EntityListIterator eli = iteratorPlain()

        // NOTE: if we are doing offset/limit with a cursor no good way to limit results, but we'll at least jump to the offset
        Node databaseNode = this.efi.getDatabaseNode(this.efi.getEntityGroupName(ed.getEntityName()))
        if (databaseNode."@offset-style" == "cursor") {
            if (!eli.absolute(offset)) {
                // can't seek to desired offset? not enough results, just go to after last result
                eli.afterLast()
            }
        }

        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-iterator", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "iterator", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), null)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return eli
    }

    protected EntityListIterator iteratorPlain() throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        if (this.getDistinct() || (ed.isViewEntity() &&
                ed.getEntityNode()."entity-condition"[0]?."@distinct") == "true") {
            efb.makeDistinct()
        }

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getAllFieldNames())
        // select fields
        efb.makeSqlSelectFields(this.fieldsToSelect)
        
        // from Clause
        efb.makeSqlFromClause()

        // where clause
        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (whereCondition && viewWhere) {
            whereCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, JoinOperator.AND, viewWhere)
        } else if (viewWhere) {
            whereCondition = viewWhere
        }
        if (whereCondition) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }

        // group by clause
        efb.makeGroupByClause(this.fieldsToSelect)

        // having clause
        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        if (havingCondition && viewHaving) {
            havingCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(havingCondition, JoinOperator.AND, viewHaving)
        } else if (viewHaving) {
            havingCondition = viewHaving
        }
        if (havingCondition) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        // order by clause
        List<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.getOrderBy()) orderByExpanded.addAll(this.getOrderBy())
        def ecObList = ed.getEntityNode()."entity-condition"?.getAt(0)?."order-by"
        if (ecObList) for (Node orderBy in ecObList) orderByExpanded.add(orderBy."@field-name")
        efb.makeOrderByClause(orderByExpanded)

        efb.addLimitOffset(this.limit, this.offset)
        if (this.forUpdate) efb.makeForUpdate()

        // run the SQL now that it is built
        EntityListIterator eli
        try {
            efi.getEntityDbMeta().checkTableRuntime(ed)
            eli = internalIterator(efb)
        } catch (EntityException e) {
            efb.closeAll()
            throw e
        } catch (Throwable t) {
            efb.closeAll()
            throw new EntityException("Error in find", t)
        }

        return eli
    }

    EntityListIteratorImpl internalIterator(EntityFindBuilder efb) {
        EntityListIteratorImpl eli
        Connection con = efb.makeConnection()
        efb.makePreparedStatement()
        efb.setPreparedStatementValues()

        ResultSet rs = efb.executeQuery()

        eli = new EntityListIteratorImpl(con, rs, this.getEntityDef(), this.fieldsToSelect, this.efi)
        // ResultSet will be closed in the EntityListIterator
        efb.releaseAll()
        return eli
    }

    /** @see org.moqui.entity.EntityFind#count() */
    long count() throws EntityException {
        long startTime = System.currentTimeMillis()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContext ec = efi.ecfi.executionContext

        ec.getArtifactExecution().push(
                new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW"),
                (entityNode."@authorize-skip" != "true" && !entityNode."@authorize-skip"?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-count", true)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        CacheImpl entityCountCache = null
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        if (doCache) {
            entityCountCache = this.efi.getCacheCount(this.entityName)
            Element cacheElement = entityCountCache.getElement(whereCondition)
            if (cacheElement != null) {
                if (cacheElement.expired) {
                    entityCountCache.removeElement(cacheElement)
                } else {
                    Long cacheHit = (Long) cacheElement.objectValue
                    efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-count", false)
                    // pop the ArtifactExecutionInfo
                    ec.getArtifactExecution().pop()
                    return cacheHit
                }
            }
        }

        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(false, true))

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        // count function instead of select fields
        efb.makeCountFunction()

        // from Clause
        efb.makeSqlFromClause()

        // where clause
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (whereCondition && viewWhere) {
            whereCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, JoinOperator.AND, viewWhere)
        } else if (viewWhere) {
            whereCondition = viewWhere
        }
        if (whereCondition) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }

        // group by clause
        efb.makeGroupByClause(this.fieldsToSelect)

        // having clause
        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        if (havingCondition && viewHaving) {
            havingCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(havingCondition, JoinOperator.AND, viewHaving)
        } else if (viewHaving) {
            havingCondition = viewHaving
        }
        if (havingCondition) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        efb.closeCountFunctionIfGroupBy()

        // run the SQL now that it is built
        long count = 0
        try {
            efi.getEntityDbMeta().checkTableRuntime(ed)
            count = internalCount(efb)
        } catch (SQLException e) {
            throw new EntityException("Error finding count", e)
        } finally {
            efb.closeAll()
        }

        if (doCache) entityCountCache.put(whereCondition, count)

        efi.runEecaRules(ed.getEntityName(), simpleAndMap, "find-count", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "count", ed.getEntityName(), simpleAndMap, startTime, System.currentTimeMillis(), count)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop()

        return count
    }

    protected long internalCount(EntityFindBuilder efb) {
        long count = 0
        efb.makeConnection()
        efb.makePreparedStatement()
        efb.setPreparedStatementValues()

        ResultSet rs = efb.executeQuery()
        if (rs.next()) count = rs.getLong(1)
        return count
    }

    long updateAll(Map<String, ?> fieldsToSet) {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk update in the DB if there are no EECAs for the entity
        this.useCache(false).forUpdate(true)
        EntityListIterator eli
        long totalUpdated = 0
        try {
            eli = iterator()
            EntityValue value
            while ((value = eli.next()) != null) {
                value.putAll(fieldsToSet)
                if (value.isModified()) {
                    // NOTE: this may blow up in some cases, if it does then change this to put all values to update in a
                    // list and update them after the eli is closed, or implement and use the eli.set(value) method
                    value.update()
                    totalUpdated++
                }
            }
        } finally {
            eli.close()
        }
        return totalUpdated
    }

    long deleteAll() {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk delete in the DB if there are no EECAs for the entity
        this.useCache(false).forUpdate(true)
        this.resultSetConcurrency(ResultSet.CONCUR_UPDATABLE)
        EntityListIterator eli
        long totalDeleted = 0
        try {
            eli = iterator()
            while (eli.next() != null) {
                eli.remove()
                totalDeleted++
            }
        } finally {
            eli.close()
        }
        return totalDeleted
    }

    protected EntityDefinition getEntityDef() {
        if (this.entityDef) return this.entityDef
        if (this.dynamicView) {
            this.entityDef = this.dynamicView.makeEntityDefinition()
        } else {
            this.entityDef = this.efi.getEntityDefinition(this.entityName)
        }
        return this.entityDef
    }

    protected boolean shouldCache() {
        if (this.dynamicView) return false
        String entityCache = this.getEntityDef().getEntityNode()."@use-cache"
        return ((this.useCache == Boolean.TRUE && entityCache != "never") || entityCache == "true")
    }

    @Override
    String toString() {
        return "Find ${entityName} WHERE [${simpleAndMap}] [${whereEntityCondition}] HAVING [${havingEntityCondition}] " +
                "SELECT [${fieldsToSelect}] ORDER BY [${orderByFields}] CACHE [${useCache}] DISTINCT [${distinct}] " +
                "OFFSET [${offset}] LIMIT [${limit}] FOR UPDATE [${forUpdate}]"
    }
}
