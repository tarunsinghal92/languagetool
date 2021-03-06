/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.tagging.disambiguation.rules;

import org.languagetool.AnalyzedToken;
import org.languagetool.Language;
import org.languagetool.rules.patterns.Element;
import org.languagetool.rules.patterns.Match;
import org.languagetool.tagging.disambiguation.rules.DisambiguationPatternRule.DisambiguatorAction;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Loads {@link DisambiguationPatternRule}s from a disambiguation rules XML
 * file.
 * 
 * @author Marcin Miłkowski
 */
public class DisambiguationRuleLoader extends DefaultHandler {

  public DisambiguationRuleLoader() {
    super();
  }

  public final List<DisambiguationPatternRule> getRules(final InputStream file)
      throws ParserConfigurationException, SAXException, IOException {
    final DisambiguationRuleHandler handler = new DisambiguationRuleHandler();
    final SAXParserFactory factory = SAXParserFactory.newInstance();
    final SAXParser saxParser = factory.newSAXParser();
    saxParser.parse(file, handler);
    return handler.getDisambRules();
  }

}

class DisambiguationRuleHandler extends DisambXMLRuleHandler {

  private static final String WD = "wd";
  private static final String ACTION = "action";
  private static final String DISAMBIG = "disambig";

  private int subId;
  private String name;
  private String ruleGroupId;
  private String ruleGroupName;
  private StringBuilder disamb = new StringBuilder();
  private StringBuilder wd = new StringBuilder();
  private StringBuilder example = new StringBuilder();

  private boolean inWord;

  private String disambiguatedPOS;

  private int startPos = -1;
  private int endPos = -1;
  private int tokenCountForMarker = 0;

  private Match posSelector;

  private int uniCounter;
  
  private List<AnalyzedToken> newWdList;
  private String wdLemma;
  private String wdPos;

  private boolean inExample;
  private boolean untouched;
  private List<String> untouchedExamples;
  private List<DisambiguatedExample> disambExamples;
  private String input;
  private String output;
  
  private DisambiguationPatternRule.DisambiguatorAction disambigAction;

 
  // ===========================================================
  // SAX DocumentHandler methods
  // ===========================================================

  @Override
  public void startElement(final String namespaceURI, final String lName,
      final String qName, final Attributes attrs) throws SAXException {
      switch (qName) {
        case "rule":
          id = attrs.getValue("id");
          if (inRuleGroup) {
            subId++;
          }
          name = attrs.getValue("name");
          if (inRuleGroup && id == null) {
            id = ruleGroupId;
          }
          if (inRuleGroup && name == null) {
            name = ruleGroupName;
          }
          break;
        case "rules":
          language = Language.getLanguageForShortName(attrs.getValue("lang"));
          break;
        case PATTERN:
          inPattern = true;
          tokenCountForMarker = 0;
          if (attrs.getValue(CASE_SENSITIVE) != null && YES.equals(attrs.getValue(CASE_SENSITIVE))) {
            caseSensitive = true;
          }
          break;
        case EXCEPTION:
          setExceptions(attrs);
          break;
        case AND:
          inAndGroup = true;
          tokenCountForMarker++;
          if (inUnification) {
            uniCounter++;
          }
          break;
        case UNIFY:
          inUnification = true;
          uniNegation = YES.equals(attrs.getValue(NEGATE));
          uniCounter = 0;
          break;
        case "feature":
          uFeature = attrs.getValue("id");
          break;
        case TYPE:
          uType = attrs.getValue("id");
          uTypeList.add(uType);
          break;
        case TOKEN:
          setToken(attrs);
          if (!inAndGroup) {
            tokenCountForMarker++;
          }
          break;
        case DISAMBIG:
          inDisambiguation = true;
          disambiguatedPOS = attrs.getValue(POSTAG);
          if (attrs.getValue(ACTION) == null) {
            // default mode:
            disambigAction = DisambiguatorAction.REPLACE;
          } else {
            disambigAction = DisambiguatorAction
                    .valueOf(attrs.getValue(ACTION).toUpperCase(Locale.ENGLISH));
          }
          disamb = new StringBuilder();
          break;
        case MATCH:
          inMatch = true;
          match = new StringBuilder();
          Match.CaseConversion caseConversion = Match.CaseConversion.NONE;
          if (attrs.getValue("case_conversion") != null) {
            caseConversion = Match.CaseConversion.valueOf(attrs
                    .getValue("case_conversion").toUpperCase(Locale.ENGLISH));
          }
          Match.IncludeRange includeRange = Match.IncludeRange.NONE;
          if (attrs.getValue("include_skipped") != null) {
            includeRange = Match.IncludeRange.valueOf(attrs
                    .getValue("include_skipped").toUpperCase(Locale.ENGLISH));
          }
          final Match mWorker = new Match(attrs.getValue(POSTAG), attrs
                  .getValue("postag_replace"), YES
                  .equals(attrs.getValue(POSTAG_REGEXP)), attrs
                  .getValue("regexp_match"), attrs.getValue("regexp_replace"),
                  caseConversion, YES.equals(attrs.getValue("setpos")),
                  YES.equals(attrs.getValue("suppress_mispelled")),
                  includeRange);
          if (inDisambiguation) {
            if (attrs.getValue(NO) != null) {
              final int refNumber = Integer.parseInt(attrs.getValue(NO));
              refNumberSanityCheck(refNumber);
              mWorker.setTokenRef(refNumber);
              posSelector = mWorker;
            }
          } else if (inToken) {
            if (attrs.getValue(NO) != null) {
              final int refNumber = Integer.parseInt(attrs.getValue(NO));
              refNumberSanityCheck(refNumber);
              mWorker.setTokenRef(refNumber);
              tokenReference = mWorker;
              elements.append('\\');
              elements.append(refNumber);
            }
          }
          break;
        case RULEGROUP:
          ruleGroupId = attrs.getValue("id");
          ruleGroupName = attrs.getValue("name");
          inRuleGroup = true;
          subId = 0;
          break;
        case UNIFICATION:
          uFeature = attrs.getValue(FEATURE);
          inUnificationDef = true;
          break;
        case "equivalence":
          uType = attrs.getValue(TYPE);
          break;
        case WD:
          wdLemma = attrs.getValue("lemma");
          wdPos = attrs.getValue("pos");
          inWord = true;
          wd = new StringBuilder();
          break;
        case EXAMPLE:
          inExample = true;
          if (untouchedExamples == null) {
            untouchedExamples = new ArrayList<>();
          }
          if (disambExamples == null) {
            disambExamples = new ArrayList<>();
          }
          untouched = attrs.getValue(TYPE).equals("untouched");
          if (attrs.getValue(TYPE).equals("ambiguous")) {
            input = attrs.getValue("inputform");
            output = attrs.getValue("outputform");
          }
          example = new StringBuilder();
          break;
        case "marker":
          example.append("<marker>");
          if (inPattern) {
            startPos = tokenCounter;
          }
          break;
      }
  }

  private void refNumberSanityCheck(int refNumber) throws SAXException {
    if (refNumber > elementList.size()) {
      throw new SAXException("Only backward references in match elements are possible, tried to specify token "
              + refNumber + "\n Line: " + pLocator.getLineNumber()
              + ", column: " + pLocator.getColumnNumber() + ".");
    }
  }

  @Override
  public void endElement(final String namespaceURI, final String sName,
      final String qName) throws SAXException {
    if ("rule".equals(qName)) {
      final DisambiguationPatternRule rule = new DisambiguationPatternRule(id,
          name, language, elementList, disambiguatedPOS, posSelector,
          disambigAction);

      endPositionCorrection = endPos - tokenCountForMarker;
      if (startPos != -1 && endPos != -1) {
        rule.setStartPositionCorrection(startPos);
        rule.setEndPositionCorrection(endPositionCorrection);
      } else {
        startPos = 0;
        endPos = tokenCountForMarker;
      }
      rule.setSubId(inRuleGroup ? Integer.toString(subId) : "1");

      final int matchedTokenCount = endPos - startPos;
      if (newWdList != null) {
        if (disambigAction == DisambiguatorAction.ADD || disambigAction == DisambiguatorAction.REMOVE
                || disambigAction == DisambiguatorAction.REPLACE) {
          if ((!newWdList.isEmpty() && disambigAction == DisambiguatorAction.REPLACE)
                  && newWdList.size() != matchedTokenCount) {
            throw new SAXException(
                language.getName() + " rule error. The number of interpretations specified with wd: "
                    + newWdList.size()
                    + " must be equal to the number of matched tokens (" + matchedTokenCount + ")"
                    + "\n Line: " + pLocator.getLineNumber() + ", column: "
                    + pLocator.getColumnNumber() + ".");
          }
          rule.setNewInterpretations(newWdList
              .toArray(new AnalyzedToken[newWdList.size()]));
        }
        newWdList.clear();
      }
      caseSensitive = false;
      if (disambExamples != null) {
        rule.setExamples(disambExamples);
      }
      if (untouchedExamples != null) {
        rule.setUntouchedExamples(untouchedExamples);
      }
      rules.add(rule);
      if (disambigAction == DisambiguatorAction.UNIFY && matchedTokenCount != uniCounter) {
        throw new SAXException(language.getName() + " rule error. The number unified tokens: "
            + uniCounter + " must be equal to the number of matched tokens: " + matchedTokenCount
            + "\n Line: " + pLocator.getLineNumber() + ", column: "
            + pLocator.getColumnNumber() + ".");
      }
      final boolean singleTokenCorrection = endPos - startPos > 1;
      if ((!singleTokenCorrection && (disambigAction == DisambiguatorAction.FILTER || disambigAction == DisambiguatorAction.REPLACE))
          && (matchedTokenCount > 1)) {
        throw new SAXException(
            language.getName() + " rule error. Cannot replace or filter more than one token at a time."
                + "\n Line: " + pLocator.getLineNumber() + ", column: "
                + pLocator.getColumnNumber() + ".");
      }
      elementList.clear();
      posSelector = null;
      disambExamples = null;
      untouchedExamples = null;
      startPos = -1;
      endPos = -1;
    } else if (qName.equals(EXCEPTION)) {
      finalizeExceptions();
    } else if (qName.equals(AND)) {
      inAndGroup = false;
      andGroupCounter = 0;
      tokenCounter++;
    } else if (qName.equals(TOKEN)) {
      if (!exceptionSet || tokenElement == null) {
        tokenElement = new Element(elements.toString(), caseSensitive,
            regExpression, tokenInflected);
        tokenElement.setNegation(tokenNegated);
      } else {
        tokenElement.setStringElement(elements.toString());
      }
      if (skipPos != 0) {
        tokenElement.setSkipNext(skipPos);
        skipPos = 0;
      }
      if (minOccurrence == 0) {
        tokenElement.setMinOccurrence(0);
      }
      if (maxOccurrence != 1) {
        tokenElement.setMaxOccurrence(maxOccurrence);
        maxOccurrence = 1;
      }
      if (posToken != null) {
        tokenElement.setPosElement(posToken, posRegExp, posNegation);
        posToken = null;
      }
      
      if (chunkTag != null) {
        tokenElement.setChunkElement(chunkTag);
        chunkTag = null;
      }

      if (tokenReference != null) {
        tokenElement.setMatch(tokenReference);
      }

      if (inAndGroup && andGroupCounter > 0) {
        elementList.get(elementList.size() - 1)
            .setAndGroupElement(tokenElement);
        if (minOccurrence !=1 || maxOccurrence !=1) {
              throw new SAXException("Please set min and max attributes on the " +
                      "first token in the AND group.\n You attempted to set these " +
                      "attributes on the token no. " + (andGroupCounter + 1) + "." + "\n Line: "
                      + pLocator.getLineNumber() + ", column: "
                      + pLocator.getColumnNumber() + ".");
        }
      } else {
          if (minOccurrence < 1) {
              elementList.add(tokenElement);
          }
          for (int i = 1; i <= minOccurrence; i ++) {
              elementList.add(tokenElement);
          }
          minOccurrence = 1;
      }
      if (inAndGroup) {
        andGroupCounter++;
      }
      if (inUnification) {
        tokenElement.setUnification(equivalenceFeatures);
        if (!inAndGroup) {
          uniCounter++;
        }
      }
      if (inUnificationDef) {
        language.getDisambiguationUnifierConfiguration().setEquivalence(uFeature, uType, tokenElement);
        elementList.clear();
      }
      if (tokenSpaceBeforeSet) {
        tokenElement.setWhitespaceBefore(tokenSpaceBefore);
      }
      resetToken();
    } else if (qName.equals(PATTERN)) {
      inPattern = false;
      tokenCounter = 0;
    } else if (qName.equals(MATCH)) {
      if (inDisambiguation) {
        posSelector.setLemmaString(match.toString());
      } else if (inToken) {
        tokenReference.setLemmaString(match.toString());
      }
      inMatch = false;
    } else if (qName.equals(DISAMBIG)) {
      inDisambiguation = false;
    } else if (qName.equals(RULEGROUP)) {
      inRuleGroup = false;
    } else if (qName.equals(UNIFICATION) && inUnificationDef) {
      inUnificationDef = false;
      tokenCounter = 0;
    } else if ("feature".equals(qName)) {
      equivalenceFeatures.put(uFeature, uTypeList);
      uTypeList = new ArrayList<>();
    } else if (qName.equals(UNIFY)) {
      inUnification = false;
      equivalenceFeatures = new HashMap<>();
      //set negation on the last token only!
      final int lastElement = elementList.size() - 1;
      elementList.get(lastElement).setLastInUnification();
      if (uniNegation) {
        elementList.get(lastElement).setUniNegation();
      }
    } else if (qName.equals(WD)) {
      addNewWord(wd.toString(), wdLemma, wdPos);
      inWord = false;
    } else if (EXAMPLE.equals(qName)) {
      inExample = false;
      if (untouched) {
        untouchedExamples.add(example.toString());
      } else {
        disambExamples.add(new DisambiguatedExample(example.toString(), input, output));
      }
    } else if ("marker".equals(qName)) {
      example.append("</marker>");
      if (inPattern) {
        endPos = tokenCountForMarker;
      }
    }
  }

  private void addNewWord(final String word, final String lemma,
      final String pos) {
    final AnalyzedToken newWd = new AnalyzedToken(word, pos, lemma);
    if (newWdList == null) {
      newWdList = new ArrayList<>();
    }
    newWdList.add(newWd);
  }

  @Override
  public final void characters(final char[] buf, final int offset, final int len) {
    final String s = new String(buf, offset, len);
    if (inException) {
      exceptions.append(s);
    } else if (inToken && inPattern) {
      elements.append(s);
    } else if (inMatch) {
      match.append(s);
    } else if (inWord) {
      wd.append(s);
    } else if (inDisambiguation) {
      disamb.append(s);
    } else if (inExample) {
      example.append(s);
    }
  }

}
