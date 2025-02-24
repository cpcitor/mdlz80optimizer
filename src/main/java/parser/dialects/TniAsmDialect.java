/*
 * Author: Santiago Ontañón Villar (Brain Games)
 */
package parser.dialects;


import cl.MDLConfig;
import code.CodeBase;
import code.Expression;
import code.SourceConstant;
import code.SourceFile;
import code.CodeStatement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import parser.LineParser;
import parser.SourceLine;

/**
 * tniASM 0.45 Dialect
 * @author theNestruo (with later contributions from santi.ontanon)
 */
public class TniAsmDialect implements Dialect {

    private final MDLConfig config;

    List<CodeStatement> linesToKeepIfGeneratingDialectAsm = new ArrayList<>();     

    /**
     * Constructor
     * @param a_config
     */
    public TniAsmDialect(MDLConfig a_config) {
        super();

        config = a_config;
        
        if (!config.hexStyleChanged) {
            config.hexStyle = MDLConfig.HEX_STYLE_0X;
        }
        
        config.warning_jpHlWithParenthesis = false;  // I don't think tniasm supports "jp hl"
        config.fix_tniasm_parenthesisExpressionBug = true;
        config.relativizeIncbinPaths = false;

        config.preProcessor.macroSynonyms.put("ifexist", config.preProcessor.MACRO_IFDEF);
        config.preProcessor.macroSynonyms.put("%if", config.preProcessor.MACRO_IF);
        config.preProcessor.macroSynonyms.put("%else", config.preProcessor.MACRO_ELSE);
        config.preProcessor.macroSynonyms.put("%endif", config.preProcessor.MACRO_ENDIF);
        config.preProcessor.macroSynonyms.put("%macro", config.preProcessor.MACRO_MACRO);
        config.preProcessor.macroSynonyms.put("%endmacro", config.preProcessor.MACRO_ENDM);
        config.lineParser.addKeywordSynonym("%macro", config.preProcessor.MACRO_MACRO);
        
        config.lineParser.addKeywordSynonym("rb", config.lineParser.KEYWORD_DS);
        config.lineParser.addKeywordSynonym("%res8", config.lineParser.KEYWORD_DS);
        config.lineParser.addKeywordSynonym("::", config.lineParser.KEYWORD_STD_COLON);
        
        config.lineParser.defineSpaceVirtualByDefault = true;
        config.lineParser.allowtniASMMultipleInstructionsPerLine = true;
        config.lineParser.allowBackslashAsLineBreaks = true;
        config.lineParser.macroDefinitionStyle = LineParser.MACRO_MACRO_NAME_ARGS;
        config.lineParser.tniAsmStylemacroArgs = true;
        config.caseSensitiveSymbols = false;
        
        config.expressionParser.binaryDigitsCanContainSpaces = true;
        config.expressionParser.OP_BIT_NEGATION = "not";
        config.expressionParser.OP_BIT_OR = "or";
        config.expressionParser.OP_BIT_AND = "and";
        config.expressionParser.OP_BIT_XOR = "xor";
        config.expressionParser.OP_LOGICAL_OR = "or";
        config.expressionParser.OP_LOGICAL_AND = "and";
        config.expressionParser.OP_MOD = "mod";
        config.expressionParser.addOpSynonym("|", config.expressionParser.OP_LOGICAL_OR);
        config.expressionParser.addOpSynonym("&", config.expressionParser.OP_LOGICAL_AND);
        config.expressionParser.addOpSynonym("%", config.expressionParser.OP_MOD);
        config.tryParsingUndefinedSymbolsAsHex = true;
        
        config.tokenizer.doubleTokens.add("%symfile");
        config.tokenizer.doubleTokens.add("%expfile");
        config.tokenizer.doubleTokens.add("%res8");
        config.tokenizer.doubleTokens.add("%res16");
        config.tokenizer.doubleTokens.add("%if");
        config.tokenizer.doubleTokens.add("%else");
        config.tokenizer.doubleTokens.add("%endif");
        config.tokenizer.doubleTokens.add("%macro");
        config.tokenizer.doubleTokens.add("%endmacro");
        config.tokenizer.doubleTokens.add("%error");
    }

    
    @Override
    public boolean recognizeIdiom(List<String> tokens, SourceConstant label, CodeBase code)
    {
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("rw")) return true;
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%res16")) return true;
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("fname")) return true;
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("forg")) return true;
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%symfile")) return true;
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%expfile")) return true;
        if (tokens.size()>= 2 && tokens.get(0).equalsIgnoreCase("phase")) return true;
        if (tokens.size()>= 1 && tokens.get(0).equalsIgnoreCase("dephase")) return true;
        if (tokens.size()>=1 && tokens.get(0).equalsIgnoreCase("%error")) return true;
        return false;
    }
    
    
    private SourceConstant getLastAbsoluteLabel(CodeStatement s) 
    {
        // sjasm considers any label as an absolute label, even if it's associated with an equ,
        // so, no need to check if s.label.isLabel() (as in asMSX):
        while(s != null) {
            if (s.label != null && !s.label.originalName.startsWith(".")) {
                return s.label;
            } else {
                s = s.source.getPreviousStatementTo(s, s.source.code);
            }
        }
        return null;        
    }
    

    @Override
    public Pair<String, SourceConstant> newSymbolName(String name, Expression value, CodeStatement s) {
        // A relative label
        if (name.startsWith(".")) {
            SourceConstant lastAbsoluteLabel = getLastAbsoluteLabel(s);        
            if (lastAbsoluteLabel != null) {
                return Pair.of(lastAbsoluteLabel.originalName + name, lastAbsoluteLabel);
            }
        }

        // An absolute label
        return Pair.of(config.lineParser.getLabelPrefix() + name, null);
    }


    @Override
    public Pair<String, SourceConstant> symbolName(String name, CodeStatement s) {
        // A relative label
        if (name.startsWith(".")) {
            SourceConstant lastAbsoluteLabel = getLastAbsoluteLabel(s);
            if (lastAbsoluteLabel != null) {
                return Pair.of(lastAbsoluteLabel.originalName + name, lastAbsoluteLabel);
            }
        }

        // An absolute label
        return Pair.of(name, null);
    }
    
    
    @Override
    public boolean parseLine(List<String> tokens, List<CodeStatement> l,
            SourceLine sl,
            CodeStatement s, CodeStatement previous, SourceFile source, CodeBase code)
    {
        if ((tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("rw")) ||
            (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%res16"))) {
            tokens.remove(0);
            
            // Parse it as a "ds", but multiply the number by 2:
            if (!config.lineParser.parseDefineSpace(tokens, l, sl, s, previous, source, code)) return false;
            if (s.space != null) {
                s.space = Expression.operatorExpression(Expression.EXPRESSION_MUL, 
                        s.space, 
                        Expression.constantExpression(2, config), config);
            }
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }   
        if (tokens.size() >= 2 && tokens.get(0).equalsIgnoreCase("fname")) {
            tokens.remove(0);
            tokens.remove(0);   // file name
            // just ignore
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }        
        if (tokens.size() >= 2 && tokens.get(0).equalsIgnoreCase("forg")) {
            tokens.remove(0);
            tokens.remove(0);   // file name
            // just ignore for now
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }        
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%symfile")) {
            tokens.remove(0);
            tokens.remove(0);   // file name
            // just ignore for now
            return true;
        }
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%expfile")) {
            tokens.remove(0);
            tokens.remove(0);   // file name
            // just ignore for now
            return true;
        }
        if (tokens.size() >= 2 && tokens.get(0).equalsIgnoreCase("phase")) {
            tokens.remove(0);
            
            // parse as an "org":
            Expression exp = config.expressionParser.parse(tokens, s, previous, code);
            if (exp == null) {
                config.error("Cannot parse phase address in " + sl);
                return false;
            }            
            s.type = CodeStatement.STATEMENT_ORG;
            s.org = exp;
            
            linesToKeepIfGeneratingDialectAsm.add(s);
            
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }
        if (tokens.size() >= 1 && tokens.get(0).equalsIgnoreCase("dephase")) {
            tokens.remove(0);
            
            // ignore for now...
            
            linesToKeepIfGeneratingDialectAsm.add(s);
            
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }        
        if (tokens.size()>=2 && tokens.get(0).equalsIgnoreCase("%error")) {
            config.error(tokens.get(1));
            tokens.remove(0);
            tokens.remove(0);
            return config.lineParser.parseRestofTheLine(tokens, l, sl, s, previous, source, code);
        }
        
        return false;
    }
        
    
    @Override
    public boolean postParsePreMacroExpansionActions(CodeBase code)
    {
        // Check to see if any labels were actually hex constants:
        for(SourceFile f:code.getSourceFiles()) {
            for(CodeStatement s:f.getStatements()) {
                for(Expression e:s.getAllExpressions()) {
                    for(Expression symbol:e.getAllSymbolExpressions()) {
                        if (!symbol.symbolName.equals(CodeBase.CURRENT_ADDRESS) && 
                            code.getSymbolValue(symbol.symbolName, true) == null) {
                            // Label that could not be evaluated:
                            if (symbol.symbolName.endsWith("h") || symbol.symbolName.endsWith("H")) {
                                String prefix = symbol.symbolName.substring(0, symbol.symbolName.length()-1);
                                Integer hex = config.tokenizer.parseHex(prefix);
                                if (hex != null) {
                                    // replace expression with hex constant:
                                    if (config.warning_ambiguous) {
                                        config.warn("'" + symbol.symbolName + "' is an ambiguous constant, that could be interpreted as a label or as a hex constant. Please prefix by a leading 0 if this is supposed to be a hex constant in " + s.sl);
                                    }
                                    if (prefix.length() <= 2) {
                                        symbol.copyFrom(Expression.constantExpression(hex, Expression.RENDER_AS_8BITHEX, config));
                                    } else {
                                        symbol.copyFrom(Expression.constantExpression(hex, Expression.RENDER_AS_16BITHEX, config));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return true;
    }
    
    
    @Override
    public String statementToString(CodeStatement s, CodeBase code, Path rootPath) {
        boolean useOriginalNames = false;
        if (linesToKeepIfGeneratingDialectAsm.contains(s)) {
            return s.sl.line;
        }

//        if (auxiliaryStatementsToRemoveIfGeneratingDialectasm.contains(s)) return "";
        
        return s.toStringUsingRootPath(rootPath, useOriginalNames, true, code);
    }        
}
