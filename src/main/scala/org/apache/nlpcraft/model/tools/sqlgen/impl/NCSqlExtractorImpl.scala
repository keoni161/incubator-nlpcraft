/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nlpcraft.model.tools.sqlgen.impl

import java.sql.Timestamp
import java.util

import org.apache.nlpcraft.model.tools.sqlgen.{NCSqlLimit, _}
import org.apache.nlpcraft.model.{NCModel, NCToken, NCVariant}

import scala.collection.JavaConverters._

/**
  *
  */
class NCSqlExtractorImpl(schema: NCSqlSchema, variant: NCVariant) extends NCSqlExtractor {
    require(schema != null)
    require(variant != null)
    
    /**
      *
      * @param tok
      * @param id
      */
    private def checkTokenId(tok: NCToken, id: String): Unit =
        if (tok.getId != id)
            throw new NCSqlException(s"Expected token ID '$id' but got: $tok")
    
    /**
      *
      * @param tok
      * @param grp
      */
    private def checkGroup(tok: NCToken, grp: String): Unit =
        if (!tok.isOfGroup(grp))
            throw new NCSqlException(s"Token does not belong to the group '$grp': $tok")
    
    /**
      *
      * @param name
      * @return
      */
    private def findSchemaTable(name: String): NCSqlTable =
        schema.getTables.asScala.find(_.getTable == name).getOrElse(throw new NCSqlException(s"Table not found: $name"))
    
    /**
      *
      * @param cols
      * @param tbl
      * @param col
      * @return
      */
    private def findSchemaColumn(cols: util.List[NCSqlColumn], tbl: String, col: String): NCSqlColumn =
        cols.asScala.find(_.getColumn == col).getOrElse(throw new NCSqlException(s"Column not found: $tbl.$col"))
    
    override def extractLimit(limitTok: NCToken) = ???
    
    override def extractDateRangeConditions(colTok: NCToken, dateTok: NCToken) = ???
    
    override def extractNumConditions(colTok: NCToken, numTok: NCToken) = ???
    
    override def extractInConditions(valToks: NCToken*) = ???
    
    override def extractSort(sortTok: NCToken) = ???
    
    override def extractAggregate(aggrFunTok: NCToken, aggrGrpTok: NCToken) = ???
    
    override def extractTable(tblTok: NCToken): NCSqlTable = {
        checkGroup(tblTok, "table")
        
        findSchemaTable(tblTok.meta("sql:name"))
    }
    
    override def extractColumn(colTok: NCToken): NCSqlColumn = {
        checkGroup(colTok, "column")
    
        val tbl: String = colTok.meta("sql:tablename")
        val col: String = colTok.meta("sql:name")
    
        findSchemaColumn(findSchemaTable(tbl).getColumns, tbl, col)
    }
    
    
    
    
    
    
    
    
    
    
    
    /**
      * 
      * @param variant
      * @param tok
      * @return
      */
    private def getLinkBySingleIndex(variant: Seq[NCToken], tok: NCToken): NCToken = {
        val idxs: util.List[Integer] = tok.meta(s"${tok.getId}:indexes")

        val idx = idxs.asScala.head

        if (idx < variant.length) {
            val note: String = tok.meta(s"${tok.getId}:note")

            val link = variant(idx)

            if (link.getId != note)
                throw new NCSqlException(s"Unexpected token with index: $idx, type: $note")

            link
        }
        else
            throw new NCSqlException(s"Token not found with index: $idx")
    }


    private def getWithGroup(tok: NCToken, group: String): Seq[NCToken] =
        (Seq(tok) ++ tok.findPartTokens().asScala).flatMap(p ⇒ if (p.getGroups.contains(group)) Some(p) else None)

    private def findAnyTableTokenOpt(schema: NCSqlSchema, tok: NCToken): Option[NCSqlTable] = {
        val tabs = getWithGroup(tok, "table")

        tabs.size match {
            case 1 ⇒ Some(findSchemaTable(schema, tabs.head.meta("sql:name")))

            case 0 ⇒ None
            case _ ⇒ throw new NCSqlException("Too many tables found")
        }
    }

    private def findAnyColumnTokenOpt(tok: NCToken): Option[NCToken] = {
        val cols = getWithGroup(tok, "column")

        cols.size match {
            case 1 ⇒ Some(cols.head)

            case 0 ⇒ None
            case _ ⇒ throw new NCSqlException("Too many columns found for token: $tok")
        }
    }

    private def getReference(schema: NCSqlSchema, variant: NCVariant, indexedTok: NCToken): Option[NCSqlColumn] = {
        val refTok = getLinkBySingleIndex(variant.asScala, indexedTok)

        findAnyColumnTokenOpt(refTok) match {
            // If reference is column - sort by column.
            case Some(t) ⇒ Some(extractSqlColumn(schema, t))
            case None ⇒
                // If reference is table -  sort by any PK column of table.
                findAnyTableTokenOpt(schema, refTok) match {
                    case Some(tab) ⇒ Some(tab.getColumns.asScala.minBy(col ⇒ if (col.isPk) 0 else 1))
                    case None ⇒ None
                }
        }
    }

    def findAnyColumnToken(tok: NCToken): NCToken =
        findAnyColumnTokenOpt(tok).getOrElse(throw new NCSqlException(s"No columns found for token: $tok"))
    

    /**
      * 
      * @param schema
      * @param variant
      * @param limitTok
      * @return
      */
    def extractLimit(schema: NCSqlSchema, variant: NCVariant, limitTok: NCToken): NCSqlLimit = {
        checkTokenId(limitTok, "nlpcraft:limit")
        
        // Skips indexes to simplify.
        val limit: Double = limitTok.meta("nlpcraft:limit:limit")

        NCSqlLimitImpl(
            getReference(schema, variant, limitTok).getOrElse(throw new NCSqlException(s"Limit not found for: $limitTok")),
            limit.intValue(),
            limitTok.meta("nlpcraft:limit:asc")
        )
    }
    
    /**
      *
      * @param schema
      * @param colTok
      * @param dateTok
      * @return
      */
    def extractDateRangeConditions(schema: NCSqlSchema, colTok: NCToken, dateTok: NCToken): util.List[NCSqlSimpleCondition] = {
        checkTokenId(dateTok, "nlpcraft:date")
        checkGroup(colTok, "column")
        
        val col = extractSqlColumn(schema, colTok)
        val range = extractDateRange(dateTok)

        val seq: Seq[NCSqlSimpleCondition] =
            Seq(NCSqlSimpleConditionImpl(col, ">=", range.getFrom), NCSqlSimpleConditionImpl(col, "<=", range.getTo))

        seq.asJava
    }

    def extractDateRange(t: NCToken): NCSqlDateRange =
        NCSqlDateRangeImpl(
            new Timestamp(t.meta("nlpcraft:date:from")),
            new Timestamp(t.meta("nlpcraft:date:to"))
        )

    def extractNumConditions(schema: NCSqlSchema, colTok: NCToken, numTok: NCToken): util.List[NCSqlSimpleCondition] = {
        val col = extractSqlColumn(schema, colTok)

        val from: java.lang.Double = numTok.meta("nlpcraft:num:from")
        val fromIncl: Boolean = numTok.meta("nlpcraft:num:fromincl")
        val to: java.lang.Double = numTok.meta("nlpcraft:num:to")
        val toIncl: Boolean = numTok.meta("nlpcraft:num:toincl")

        val isRangeCondition: Boolean = numTok.meta("nlpcraft:num:israngecondition")
        val isEqualCondition: Boolean = numTok.meta("nlpcraft:num:isequalcondition")
        val isNotEqualCondition: Boolean = numTok.meta("nlpcraft:num:isnotequalcondition")
        val isFromNegativeInfinity: Boolean = numTok.meta("nlpcraft:num:isfromnegativeinfinity")
        val isToPositiveInfinity: Boolean = numTok.meta("nlpcraft:num:istopositiveinfinity")

        val seq: Seq[NCSqlSimpleCondition] =
            if (isEqualCondition)
                Seq(NCSqlSimpleConditionImpl(col, "=", from))
            else if (isNotEqualCondition)
                Seq(NCSqlSimpleConditionImpl(col, "<>", from))
            else {
                require(isRangeCondition)

                if (isFromNegativeInfinity)
                    Seq(NCSqlSimpleConditionImpl(col, if (fromIncl) "<=" else "<", to))
                else if (isToPositiveInfinity)
                    Seq(NCSqlSimpleConditionImpl(col, if (fromIncl) ">=" else ">", from))
                else
                    Seq(
                        NCSqlSimpleConditionImpl(col, if (fromIncl) ">=" else ">", from),
                        NCSqlSimpleConditionImpl(col, if (toIncl) "<=" else "<", to)
                    )
            }

        seq.asJava
    }

    def extractSorts(schema: NCSqlSchema, variant: NCVariant, sortTok: NCToken): NCSqlSort =
        NCSqlSortImpl(
            getReference(schema, variant, sortTok).getOrElse(throw new NCSqlException(s"Sort not found for: $sortTok")),
            sortTok.meta("nlpcraft:sort:asc")
        )

    def extractAggregate(schema: NCSqlSchema, variant: NCVariant, aggrFunc: NCToken, aggrGroupOpt: NCToken):
        NCSqlAggregate =
    {
        val select =
            aggrFunc match {
                case null ⇒ Seq.empty
                case _ ⇒
                    Seq(
                        NCSqlFunctionImpl(
                            extractSqlColumn(schema, findAnyColumnToken(getLinkBySingleIndex(variant.asScala, aggrFunc))),
                            aggrFunc.meta(s"${aggrFunc.getId}:type")
                        )
                    )

            }

        val groupBy = aggrGroupOpt match {
            case null ⇒ Seq.empty
            case aggrGroup ⇒
                val groupTok = getLinkBySingleIndex(variant.asScala, aggrGroup)

                // If reference is column - group by column.
                findAnyColumnTokenOpt(groupTok) match {
                    case Some(groupCol) ⇒ Seq(extractSqlColumn(schema, groupCol))
                    case None ⇒
                        // If reference is table - group by all PK columns of table or
                        // (if there aren't PKs, by any table column)
                        findAnyTableTokenOpt(schema, groupTok) match {
                            case Some(tab) ⇒
                                val cols = tab.getColumns.asScala
                                val pkCols = cols.filter(_.isPk)

                                if (pkCols.nonEmpty) pkCols else Seq(cols.head)
                            case None ⇒ throw new NCSqlException(s"Group by not found for: $aggrGroupOpt")
                        }
                }
        }

        NCSqlAggregateImpl(select, groupBy)
    }

    def extractTable(schema: NCSqlSchema, tok: NCToken): NCSqlTable = findSchemaTable(schema, tok.meta("sql:name"))
    def extractColumn(schema: NCSqlSchema, t: NCToken): NCSqlColumn = extractSqlColumn(schema, findAnyColumnToken(t))

    def extractValuesConditions(schema: NCSqlSchema, allValsToks: Array[NCToken]): util.List[NCSqlInCondition] = {
        allValsToks.map(tok ⇒ {
            val valToks = (Seq(tok) ++ tok.findPartTokens().asScala).filter(_.getValue != null)

            val valTok =
                valToks.size match {
                    case 1 ⇒ valToks.head

                    case 0 ⇒ throw new NCSqlException(s"Values column not found for token: $tok")
                    case _ ⇒ throw new NCSqlException(s"Too many values columns found token: $tok")
                }

            extractSqlColumn(schema, valTok) → valTok.getValue
        }).groupBy { case (col, _) ⇒ col }.map { case (col, seq) ⇒
            val cond: NCSqlInCondition = NCSqlInConditionImpl(col, seq.map { case (_, value) ⇒ value})

            cond
        }.toSeq.asJava
    }

    def makeSchema(mdl: NCModel): NCSqlSchema = {
        // Soft check that the model generated by 'sqlgen' tool.
        if (!mdl.metaOpt("sql:url").isPresent)
            throw new NCSqlException("")
        
        val elems = mdl.getElements.asScala

        val tabCols =
            elems.filter(_.getGroups.contains("column")).map(p ⇒ {
                val col: NCSqlColumn =
                    NCSqlColumnImpl(
                        p.meta("sql:tablename"),
                        p.meta("sql:name"),
                        p.meta("sql:datatype"),
                        p.meta("sql:ispk"),
                        p.meta("sql:isnullable")
                    )

                col
            }).groupBy(_.getTable).map { case (tab, cols) ⇒ tab → cols }

        val joinsMap = mdl.getMetadata.get("sql:joins").asInstanceOf[util.List[util.Map[String, Object]]]

        val tables =
            elems.filter(_.getGroups.contains("table")).
                map(p ⇒ {
                    def x(l: util.List[String]): Seq[String] = if (l == null) Seq.empty else l.asScala

                    val tab: String = p.meta("sql:name")
                    val defSelect = x(p.meta("sql:defaultselect"))
                    val defSort = x(p.meta("sql:defaultsort"))
                    val extra = x(p.meta("sql:extratables"))
                    val defDate: String = p.meta("sql:defaultdate")

                    val cols = tabCols(tab).toSeq.sortBy(p ⇒ (if (p.isPk) 0 else 1, p.getColumn)).asJava

                    val table: NCSqlTable = NCSqlTableImpl(
                        tab,
                        // TODO: columns should be list, but elements are set. How should we order them?
                        // TODO: Seems elements should be seq too in model.
                        cols.asScala,
                        defSort.
                            map(s ⇒ {
                                var pair = defDate.split("\\.")

                                val t =
                                    pair.length match {
                                        case 1 ⇒
                                            pair = s.split("#")

                                            // By default, same table name.
                                            tab
                                        case 2 ⇒
                                            val t = pair.head

                                            pair = pair.last.split("#")

                                            t
                                        case  _ ⇒ throw new NCSqlException(s"Invalid sort: $s")
                                    }

                                if (pair.length != 2)
                                    throw new NCSqlException(s"Invalid sort: $s")

                                val col = pair.head
                                val asc = pair.last.toLowerCase

                                if (asc != "asc" && asc != "desc")
                                    throw new NCSqlException(s"Invalid sort: $pair")

                                val sort: NCSqlSort = NCSqlSortImpl(findSchemaColumn(cols, t, col), asc == "asc")

                                sort
                            }),
                        defSelect,
                        extra,
                        if (defDate != null) {
                            val pair = defDate.split("\\.")

                            if (pair.length != 2)
                                throw new NCSqlException(s"Invalid default date: $defDate")

                            val tab = pair.head
                            val col = pair.last.toLowerCase

                            tabCols.
                                getOrElse(tab, throw new NCSqlException(s"Invalid default date: $defDate")).
                                find(_.getColumn == col).
                                getOrElse(throw new NCSqlException(s"Invalid default date: $defDate"))
                        }
                        else
                            null
                    )

                    table
                }).toSeq

        val joins = joinsMap.asScala.map(_.asScala).map(m ⇒ {
            val j: NCSqlJoin =
                NCSqlJoinImpl(
                    m("fromtable").asInstanceOf[String],
                    m("totable").asInstanceOf[String],
                    m("fromcolumns").asInstanceOf[util.List[String]].asScala,
                    m("tocolumns").asInstanceOf[util.List[String]].asScala,
                    NCSqlJoinType.valueOf(m("jointype").asInstanceOf[String].toUpperCase())
                )

            j
        })

        NCSqlSchemaImpl(tables, joins)
    }
}
