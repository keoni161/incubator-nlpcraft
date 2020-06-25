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

package org.apache.nlpcraft.server.nlp.enrichers.ctxword

import io.opencensus.trace.Span
import org.apache.nlpcraft.common.NCService
import org.apache.nlpcraft.common.nlp.{NCNlpSentence, NCNlpSentenceNote => Note, NCNlpSentenceToken => Token}
import org.apache.nlpcraft.server.ctxword._
import org.apache.nlpcraft.server.mdo.{NCExampleMdo, NCContextWordConfigMdo => Config}
import org.apache.nlpcraft.server.nlp.enrichers.NCServerEnricher
import org.jibx.schema.codegen.extend.DefaultNameConverter

import scala.collection._

object NCContextWordEnricher extends NCServerEnricher {
    private final val CONVERTER = new DefaultNameConverter

    private final val POS_PLURALS = Set("NNS", "NNPS")
    private final val POS_SINGULAR = Set("NN", "NNP")

    private final val CTX_WORDS_LIMIT = 1000

    // Configuration when we try to find context words for words nouns using initial sentence.
    private final val MIN_SENTENCE_SCORE = 0.5
    private final val MIN_SENTENCE_FTEXT = 0.5

    // Configuration when we try to find context words for words nouns using substituted examples.
    private final val MIN_EXAMPLE_SCORE = 0.8
    private final val MIN_EXAMPLE_FTEXT = 0.5

    private case class Word(text: String, index: Int, examplePos: String, wordPos: String)
    private case class Holder(
        elementId: String,
        stem: String,
        value: String,
        score: Double,
        bertScore: Option[Double] = None,
        ftextScore: Option[Double] = None
    ) {
        override def toString: String = {
            var s = s"ElementId=$elementId, stem=$stem, value=$value, score=$score"

            bertScore match {
                case Some(score) ⇒ s = s"$s, bertScore=$score"
                case None ⇒ // No-op.
            }

            ftextScore match {
                case Some(score) ⇒ s = s"$s, ftextScore=$score"
                case None ⇒ // No-op.
            }

            s
        }
    }

    override def start(parent: Span = null): NCService = startScopedSpan("start", parent) { _ ⇒
        super.start()
    }

    override def stop(parent: Span): Unit = startScopedSpan("stop", parent) { _ ⇒
        super.stop()
    }

    private def tryDirect(cfg: Config, toks: Seq[Token], score: Double): Map[Token, Holder] = {
        val res =
            cfg.synonyms.flatMap { case (elemId, syns) ⇒
                toks.flatMap(tok ⇒
                    syns.get(tok.stem) match {
                        case Some(value) ⇒ Some(tok → Holder(elemId, tok.stem, value, score))
                        case None ⇒ None
                    }
                )
            }

        logResults("direct", res)

        res
    }

    private def trySentence(cfg: Config, toks: Seq[Token], ns: NCNlpSentence, f: NCContextWordFactors): Map[Token, Holder] = {
        val words = ns.tokens.map(_.origText)

        val suggs =
            NCContextWordManager.suggest(
                toks.map(t ⇒ NCContextWordRequest(words, t.index)),
                NCContextWordParameter(
                    limit = CTX_WORDS_LIMIT,
                    totalScore = f.getMin("min.sentence.total.score"),
                    ftextScore = f.getMin("min.sentence.ftext.score")

                )
            )

        require(toks.size == suggs.size)

        val res =
            toks.zip(suggs).
                flatMap { case (tok, suggs) ⇒
                    suggs.sortBy(-_.totalScore).flatMap(sugg ⇒
                        cfg.contextWords.toStream.flatMap { case (elemId, stems) ⇒
                            if (
                                stems.contains(sugg.stem) &&
                                sugg.totalScore >= f.get(elemId, "min.sentence.total.score") &&
                                sugg.ftextScore >= f.get(elemId, "min.sentence.ftext.score")
                            )
                                Some(tok → makeHolder(elemId, tok, sugg))
                            else
                                None
                        }).headOption
                }.toMap

        logResults("sentence", res)

        res
    }

    private def tryExamples(cfg: Config, toks: Seq[Token], f: NCContextWordFactors): Map[Token, Holder] = {
        val examples = cfg.examples.toSeq

        case class V(elementId: String, example: String, token: Token)
        case class VExt(value: V, requests: Seq[NCContextWordRequest])

        val allReqs: Seq[VExt] =
            examples.flatMap { case (elemId, exSeq) ⇒
                def make(ex: NCExampleMdo, tok: Token): VExt = {
                    val words =
                        substitute(
                            ex.words,
                            ex.substitutions.map { case (idx, pos) ⇒ Word(tok.origText, idx, pos, tok.pos) }
                        )

                    VExt(
                        V(elemId, words.mkString(" "), tok),
                        ex.substitutions.keys.toSeq.sorted.map(i ⇒ NCContextWordRequest(words, i))
                    )
                }

                for (ex ← exSeq; tok ← toks) yield make(ex, tok)
            }

        val allSuggs =
            NCContextWordManager.suggest(
                allReqs.flatMap(_.requests),
                // Ftext used as default value.
                NCContextWordParameter(limit = CTX_WORDS_LIMIT, totalScore = f.getMin("min.example.total.score"))
            )

        require(allSuggs.size == allReqs.map(_.requests.size).sum)

        val res =
            allReqs.
                flatMap(p ⇒ p.requests.indices.map(_ ⇒ p.value)).
                zip(allSuggs).
                groupBy { case (v, _) ⇒ (v.elementId, v.token) }.
                flatMap { case ((elemId, tok), seq) ⇒
                    val suggs =
                        seq.groupBy { case (v, _) ⇒ v.example}.
                            flatMap { case (_, seq) ⇒
                                seq.flatMap { case (_, seq) ⇒ seq }.
                                    sortBy(p ⇒ (-p.ftextScore, -p.totalScore)).
                                    find(p ⇒ cfg.contextWords(elemId).contains(p.stem))
                            }

                    if (suggs.size == cfg.examples(elemId).size) {
                        suggs.toSeq.
                            filter(sugg ⇒
                                sugg.totalScore >= f.get(elemId, "min.example.total.score") &&
                                sugg.ftextScore >= f.get(elemId, "min.example.ftext.score")

                            ).
                            sortBy(p ⇒ (-p.ftextScore, -p.totalScore)).headOption match {
                                case Some(best) ⇒ Some(tok → makeHolder(elemId, tok, best))
                                case None ⇒ None
                            }
                    }
                    else
                        None
                }

        logResults("examples", res)

        res
    }

    private def makeHolder(elemId: String, tok: Token, resp: NCContextWordResponse): Holder =
        Holder(
            elementId = elemId,
            stem = resp.stem,
            value = tok.normText,
            score = resp.totalScore,
            bertScore = Some(resp.bertScore),
            ftextScore = Some(resp.ftextScore)
        )

    private def substitute(template: Seq[String], substs: Iterable[Word]): Seq[String] = {
        require(substs.map(_.index).forall(i ⇒ i >= 0 && i < template.length))

        val substMap = substs.map(p ⇒ p.index → p).toMap

        template.zipWithIndex.map {  case (templ, i) ⇒
            substMap.get(i) match {
                case Some(subst) ⇒
                    if (POS_SINGULAR.contains(subst.examplePos) && POS_PLURALS.contains(subst.wordPos))
                        CONVERTER.depluralize(subst.text)
                    else if (POS_PLURALS.contains(subst.examplePos) && POS_SINGULAR.contains(subst.wordPos))
                        CONVERTER.pluralize(subst.text)
                    else
                        subst.text
                case None ⇒ templ
            }
        }
    }

    private def logResults(typ: String, m: Map[Token, Holder]): Unit =
        m.foreach {
            case (tok, h) ⇒ logger.info(s"Token detected [index=${tok.index}, text=${tok.origText}, type=$typ, data=$h")
        }

    override def enrich(sen: NCNlpSentence, parent: Span): Unit =
        startScopedSpan("enrich", parent, "srvReqId" → sen.srvReqId, "txt" → sen.text) {
            _ ⇒
                sen.ctxWordsConfig match {
                    case Some(cfg) ⇒
                        val toks = sen.filter(tok ⇒ cfg.poses.contains(tok.pos))
                        var m = tryDirect(cfg, toks, Integer.MAX_VALUE)

                        def getOther: Seq[Token] = toks.filter(t ⇒ !m.contains(t))

                        if (m.size != toks.length) {
                            val f =
                                NCContextWordFactors(
                                    cfg.modelMeta,
                                    cfg.examples.keySet,
                                    Map(
                                        "min.sentence.total.score" → MIN_SENTENCE_SCORE,
                                        "min.sentence.ftext.score" → MIN_SENTENCE_FTEXT,
                                        "min.example.total.score" → MIN_EXAMPLE_SCORE,
                                        "min.example.ftext.score" → MIN_EXAMPLE_FTEXT
                                    )
                                )

                            m ++= trySentence(cfg, getOther, sen, f)

                            if (m.size != toks.length)
                                m ++= tryExamples(cfg, getOther, f)
                        }

                        m.foreach {
                            case (t, h) ⇒ t.add(Note(Seq(t.index), h.elementId, "value" → h.value, "score" → h.score))
                        }
                    case None ⇒ // No-op.
                }
        }
}
