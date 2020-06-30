/*
 * Author: Santiago Ontañón Villar (Brain Games)
 */
package workers.pattopt;

import cl.MDLConfig;
import code.CodeBase;
import code.SourceFile;
import code.SourceStatement;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import workers.MDLWorker;

/**
 *
 * @author santi
 */
public class PatternBasedOptimizer implements MDLWorker {
    
    public static class OptimizationResult {
        public int bytesSaved = 0;
        public int patternApplications = 0;
        
        public void aggregate(OptimizationResult r) {
            bytesSaved += r.bytesSaved;
            patternApplications += r.patternApplications;
        }
    }
    
    
    MDLConfig config;
    boolean activate = false;
    boolean silent = false;
    boolean logPatternsMatchedWithViolatedConstraints = false;
    List<Pattern> patterns = new ArrayList<>();
    
    
    public PatternBasedOptimizer(MDLConfig a_config)
    {
        config = a_config;        
    }
    
    
    @Override
    public String docString() {
        return "  -po: Runs the pattern-based optimizer.\n" +
               "  -posilent: Supresses the pattern-based-optimizer output\n" +
               "  -popotential: Reports lines where a potential optimization was not applied for safety, but could maybe be done manually.\n";
    }

    @Override
    public boolean parseFlag(List<String> flags) {
        if (flags.get(0).equals("-po")) {
            flags.remove(0);
            activate = true;
            return true;
        }
        if (flags.get(0).equals("-posilent")) {
            flags.remove(0);
            silent = true;
            return true;
        }
        if (flags.get(0).equals("-popotential")) {
            flags.remove(0);
            logPatternsMatchedWithViolatedConstraints = true;
            return true;
        }
        return false;
    }
    
    
    void initPatterns() 
    {
        try {
            String inputFile = "data/pbo-patterns.txt";
            BufferedReader br;

            try {
                // In case we are running from inside a JAR file:
                InputStream in = config.getClass().getResourceAsStream("/"+inputFile); 
                br = new BufferedReader(new InputStreamReader(in));
            } catch (Exception e) {
                br = new BufferedReader(new FileReader(inputFile));
            }            

            String patternString = "";
            while(true) {
                String line = br.readLine();
                if (line == null) {
                    if (!patternString.equals("")) {
                        patterns.add(new Pattern(patternString, config));
                    }
                    break;
                }
                line = line.trim();
                // ignore comments:
                if (line.startsWith(";")) continue;
                
                if (line.equals("")) {
                    if (!patternString.equals("")) {
                        patterns.add(new Pattern(patternString, config));
                        patternString = "";
                    }
                } else {
                    patternString += line + "\n";
                }                
            }
        } catch (Exception e) {
            config.error("PatternBasedOptimizer: error initializing patterns!");
            e.printStackTrace();
        }
    }
    

    @Override
    public boolean work(CodeBase code) throws Exception {
        if (!activate) return true;
        optimize(code);
        return true;
    }


    public OptimizationResult optimize(CodeBase code) throws Exception {
        initPatterns();        
        OptimizationResult r = new OptimizationResult();
        for(Pattern patt: patterns) {
            OptimizationResult r2 = optimizeWithPattern(patt, code);
            r.aggregate(r2);
        }
        
        if (r.patternApplications > 0) {
            code.resetAddresses();
        }
        
        if (!silent) {
            config.info("PatternBasedOptimizer: " + r.patternApplications + " patterns applied, " + r.bytesSaved + " bytes saved");
        }
        return r;
    }
    
    public OptimizationResult optimizeWithPattern(Pattern patt, CodeBase code) throws Exception {
        OptimizationResult r = new OptimizationResult();
        for(SourceFile f:code.getSourceFiles()) {
            for(int i = 0;i<f.getStatements().size();i++) {
                PatternMatch match = patt.match(i, f, code, config, logPatternsMatchedWithViolatedConstraints);
                if (match != null) {
                    int lineNumber = f.getStatements().get(i).lineNumber;
                    int startIndex = i;
                    int endIndex = startIndex;
                    for(int id:match.opMap.keySet()) {
                        int idx = f.getStatements().indexOf(match.opMap.get(id));
                        if (idx > endIndex) endIndex = idx;
                    }
                    SourceStatement endStatement = null;
                    if (f.getStatements().size()>endIndex+1) {
                        endStatement = f.getStatements().get(endIndex+1);
                    }
                    
                    String previousCode = "";
                    for(int line = startIndex;line<=endIndex;line++) {
                        previousCode += f.getStatements().get(line).toString();
                        if (line != endIndex) previousCode += "\n";
                    }
                    
                    if (patt.apply(f.getStatements(), match)) {
                        if (!silent) {
                            String newCode = "";
                            endIndex = f.getStatements().size();
                            if (endStatement != null) endIndex = f.getStatements().indexOf(endStatement);
                            for(int line = startIndex;line<endIndex;line++) {
                                newCode += f.getStatements().get(line).toString() + "\n";
                            }
                            // config.info("Pattern "+patt.getName()+" applied in " + f.fileName + ", line " + lineNumber + ": " + patt.getSpaceSaving(match) + " bytes saved");
                            config.info("PatternBasedOptimizer substitution in " + f.fileName + ", line " + lineNumber + ": " + patt.getSpaceSaving(match) + " bytes saved");
                            config.info(previousCode);
                            config.info("Replaced by:");
                            config.info(newCode);
                        }
                        r.patternApplications++;
                        r.bytesSaved += patt.getSpaceSaving(match);
                    }
                }
            }
        }
        return r;
    }
            
}
