/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package workers.searchopt;

import cl.MDLConfig;
import code.CPUOp;
import code.CPUOpDependency;
import code.CodeBase;
import code.CodeStatement;
import code.Expression;
import code.SourceFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import parser.SourceLine;
import util.microprocessor.IMemory;
import util.microprocessor.PlainZ80IO;
import util.microprocessor.PlainZ80Memory;
import util.microprocessor.ProcessorException;
import util.microprocessor.Z80.CPUConfig;
import util.microprocessor.Z80.CPUConstants.RegisterNames;
import util.microprocessor.Z80.Z80Core;
import workers.MDLWorker;

/**
 *
 * @author santi
 */
public class SearchBasedOptimizer implements MDLWorker {
    public static final int SEARCH_ID_OPS = 0;
    public static final int SEARCH_ID_BYTES = 1;
    
    // randomize the register contents:
    public static final RegisterNames eightBitRegisters[] = {
        RegisterNames.A, RegisterNames.F,
        RegisterNames.B, RegisterNames.C,
        RegisterNames.D, RegisterNames.E,
        RegisterNames.H, RegisterNames.L,
        RegisterNames.A_ALT, RegisterNames.F_ALT,
        RegisterNames.B_ALT, RegisterNames.C_ALT,
        RegisterNames.D_ALT, RegisterNames.E_ALT,
        RegisterNames.H_ALT, RegisterNames.L_ALT,
        RegisterNames.IXH, RegisterNames.IXL,
        RegisterNames.IYH, RegisterNames.IYL,
        RegisterNames.R
    };

    MDLConfig config;
    boolean trigger = false;
    boolean showNewBestDuringSearch = true;
    
    int numberOfRandomSolutionChecks = 1000;
    int searchType = SEARCH_ID_OPS;
    
    // Global search state (so we don't need to pass it throughout recursive calls):
    Random rand = new Random();
    Z80Core z80 = null;
    IMemory z80Memory = null;
    int codeMaxAddress;
    int codeMaxOps;
    Specification spec = null;
    CodeBase code = null;
    List<CPUOp> currentOps = null;
    // The "dependencies" array, contains the set of dependencies (Registers/flags) that
    // have already been set by previous instructions:
    int nDependencies = 0;
    boolean currentDependencies[][] = null;
    long solutionsEvaluated = 0;        
    // best solution:
    List<CPUOp> bestOps = null;
    int bestSize = 0;
    int bestTime = 0;
    
    
    public SearchBasedOptimizer(MDLConfig a_config)
    {
        config = a_config;
    }
    
    
    @Override
    public String docString() {
        return "- ```-so```: Runs the search-based-based optimizer (input file is a function specification instead of an assembler file).\n";
    }

    
    @Override
    public String simpleDocString() {
        return "- ```-so```: Runs the search-based-based optimizer (input file is a function specification instead of an assembler file).\n";
    }

    
    @Override
    public boolean parseFlag(List<String> flags) {
        if (flags.get(0).equals("-so")) {
            flags.remove(0);
            trigger = true;
            config.codeSource = MDLConfig.CODE_FROM_SEARCHBASEDOPTIMIZER;
            return true;
        }
        return false;    
    }

    
    @Override
    public boolean triggered() {
        return trigger;
    }

    
    @Override
    public boolean work(CodeBase a_code) {
        // Parse specification file:
        this.code = a_code;
        spec = SpecificationParser.parse(config.inputFile, code, config);
        if (spec == null) {
            config.error("Cannot parse the input specification file '"+config.inputFile+"'");
            return false;
        }
                
        SourceFile sf = new SourceFile("autogenerated.asm", null, null, code, config);
        code.addOutput(null, sf, 0);

        // Precompute all the op dependencies before search:
        List<CPUOpDependency> allDependencies = new ArrayList<>();
        for(String regName: new String[]{"A", "F", "B", "C", "D", "E", "H", "L", 
                                         "IXH", "IXL", "IYH", "IYL"}) {
            allDependencies.add(new CPUOpDependency(regName, null, null, null, null));
        }
        for(String flagName: new String[]{"S" ,"Z" ,"H" , "P/V" ,"N" , "C"}) {
            allDependencies.add(new CPUOpDependency(null, flagName, null, null, null));
        }
        allDependencies.add(new CPUOpDependency(null, null, "C", null, null));
        allDependencies.add(new CPUOpDependency(null, null, null, "0", "0x10000"));
        nDependencies = allDependencies.size();
        config.debug("nDependencies: " + nDependencies);
        
        List<SBOCandidate> candidateOps = precomputeCandidateOps(spec, allDependencies, code);
        if (candidateOps == null) return false;
        config.debug("candidateOps: " + candidateOps.size());
        
        // Precalculate which instructions can contribute to the solution:
        {
            boolean goalDependencies[] = spec.getGoalDependencies(allDependencies);
            for(SBOCandidate op:candidateOps) {
                for(int i = 0;i<nDependencies;i++) {
                    if (goalDependencies[i] && op.outputDependencies[i]) {
                        op.directContributionToGoal = true;
                        break;
                    }
                }
            }
        }
        
        // Precalculate potential followups to each op:
        for(SBOCandidate op:candidateOps) {
            op.potentialFollowUps = new ArrayList<>();
            for(SBOCandidate op2:candidateOps) {
                if (sequenceMakesSense(op, op2, code)) {
                    op.potentialFollowUps.add(op2);
                }
            }
        }
        
        // Create a simulator:
        z80Memory = new PlainZ80Memory();
        z80 = new Z80Core(z80Memory, new PlainZ80IO(), new CPUConfig(config));
        
        // Run the search process to generate code:
        // Search via iterative deepening:
        currentOps = new ArrayList<>();
        
//        currentDependencies = 
        currentDependencies = new boolean[spec.maxOps+1][nDependencies];
        {
            boolean initialDependencies[] = spec.getInitialDependencies(allDependencies);
            for(int i = 0;i<nDependencies;i++) {
                currentDependencies[0][i] = initialDependencies[i];
            }
        }
        solutionsEvaluated = 0;        
        bestOps = null;
        bestSize = 0;
        bestTime = 0;        
        config.debug("Initial dependency set: " + Arrays.toString(currentDependencies[0]));
        try {
            if (searchType == SEARCH_ID_OPS) {
                codeMaxAddress = 0x10000;
                for(int depth = 0; depth<=spec.maxOps; depth++) {
                    codeMaxOps = depth;
                    if (depthFirstSearch(0, spec.codeStartAddress, candidateOps)) {
                        // solution found!
                        break;
                    }
                    config.info("SearchBasedOptimizer: depth "+depth+" complete ("+solutionsEvaluated+" solutions tested)");
                }

            } else if (searchType == SEARCH_ID_BYTES) {
                codeMaxOps = spec.maxOps;
                for(int size = 0; size<=spec.maxSizeInBytes; size++) {
                    codeMaxAddress = spec.codeStartAddress + size;
                    if (depthFirstSearch(0, spec.codeStartAddress, candidateOps)) {
                        // solution found!
                        break;
                    }
                    config.info("SearchBasedOptimizer: size "+size+" complete ("+solutionsEvaluated+" solutions tested)");
                }

            } else {
                config.error("Unsupported search type " + searchType);
                return false;
            }
        } catch (Exception e) {
            config.error("Exception while executing the search-based optimizer: " + e.getLocalizedMessage());
            config.error("Exception while executing the search-based optimizer: " + Arrays.toString(e.getStackTrace()));
            return false;
        }
            
        if (bestOps == null) return false;
        
        int lineNumber = 1;
        for(CPUOp op:bestOps) {
            SourceLine sl = new SourceLine("    " + op.toString(), sf, lineNumber);
            CodeStatement s = new CodeStatement(CodeStatement.STATEMENT_CPUOP, sl, sf, config);
            s.op = op;
            sf.getStatements().add(s);
            lineNumber++;
        }
        
        config.info("SearchBasedOptimizer: search ended ("+solutionsEvaluated+" solutions tested)");
                
        return true;
    }
    
    
    List<SBOCandidate> precomputeCandidateOps(Specification spec, List<CPUOpDependency> allDependencies, CodeBase code)
    {
        List<SBOCandidate> candidates = new ArrayList<>();
        
        /*
        OK - AND/OR/XOR
        OK - INC/DEC
        OK - ADD/ADC/SUB/SBC
        OK - LD
        OK - RLCA/RLA/RRCA/RRA/RLC/RL/RRC/RR
        OK - SLA/SRA/SRL/SLI
        - CPL/NEG
        - CCF/SCF
        - PUSH/POP
        - EX/EXX
        - LDI/LDD/LDIR/LDDR
        - CPI/CPD/CPIR/CPDR
        - CP
        - DAA
        - HALT/DI/EI/IM
        - RLD/RRD
        - BIT/SET/RES
        - JP/JR/DJNZ
        - CALL/RET/RETI/RETN
        - RST
        - IN/INI/IND/INIR/INDR
        - OUT/OUTI/OUTD/OTIR/OTDR
        - NOP
        */
        
        if (spec.allowAndOrXorOps) {
            if (!precomputeAndOrXor(candidates, spec, allDependencies, code)) return null;
        }
        if (spec.allowIncDecOps) {
            if (!precomputeIncDec(candidates, spec, allDependencies, code)) return null;
        }
        if (spec.allowAddAdcSubSbc) {
            if (!precomputeAddAdcSubSbc(candidates, spec, allDependencies, code)) return null;
        }
        if (spec.allowLd) {
            if (!precomputeLd(candidates, spec, allDependencies, code)) return null;
        }
        if (spec.allowRotations) {
            if (!precomputeRotations(candidates, spec, allDependencies, code)) return null;
        }
        if (spec.allowShifts) {
            if (!precomputeShifts(candidates, spec, allDependencies, code)) return null;
        }
        
        return candidates;
    }
    
    
    boolean precomputeOp(String line, List<SBOCandidate> candidates, List<CPUOpDependency> allDependencies, CodeBase code)
    {
        List<String> tokens = config.tokenizer.tokenize(line);
        SourceFile sf = new SourceFile("dummy", null, null, code, config);
        SourceLine sl = new SourceLine(line, sf, 0);
        List<CodeStatement> l = config.lineParser.parse(tokens, sl, sf, null, code, config);
        if (l == null || l.size() != 1) {
            config.error("Parsing candidate op in the search-based optimizer resulted in none, or more than one op! " + line);
            return false;
        }
        CodeStatement s = l.get(0);
        SBOCandidate candidate = new SBOCandidate(s.op, allDependencies, code, config);
        if (candidate.bytes == null) return false;
        candidates.add(candidate);
        return true;
    }
    
    
    boolean precomputeAndOrXor(List<SBOCandidate> candidates, Specification spec, 
                               List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String opNames[] = {"and", "or", "xor"};
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l"};
        for(String opName : opNames) {
            // register argument:
            for(String regName : regNames) {
                String line = opName + " " + regName;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // constant argument:
            for(Integer constant:spec.allowed8bitConstants) {
                String line = opName + " " + constant;
                if (constant == 0) {
                    if (opName.equals("add") || opName.equals("sub")) continue;
                }                
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;                
            }
            
            // (hl):
            if (spec.allowRamUse) {
                String line = opName + " (hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
            }
            
            // (ix+d) / (iy+d):
            if (spec.allowRamUse) {
                for(String reg:new String[]{"ix","iy"}) {
                    for(Integer constant:spec.allowedOffsetConstants) {
                        if (constant >= 0) {
                            String line = opName + " ("+reg+"+"+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        } else {
                            String line = opName + " ("+reg+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        }
                    }
                }
            }
        }
        return true;
    }
    

    boolean precomputeIncDec(List<SBOCandidate> candidates, Specification spec, 
                             List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String opNames[] = {"inc", "dec"};
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l",
                             "bc", "de", "hl", 
                             "ix", "iy",
                             "ixh", "ixl", "iyh", "iyl"};
        for(String opName : opNames) {
            // register argument:
            for(String regName : regNames) {
                String line = opName + " " + regName;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // (hl):
            if (spec.allowRamUse) {
                String line = opName + " (hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
            }
            
            // (ix+d) / (iy+d):
            if (spec.allowRamUse) {
                for(String reg:new String[]{"ix","iy"}) {
                    for(Integer constant:spec.allowedOffsetConstants) {
                        if (constant >= 0) {
                            String line = opName + " ("+reg+"+"+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        } else {
                            String line = opName + " ("+reg+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        }
                    }
                }        
            }
        }
        return true;
    }
    
    
    boolean precomputeAddAdcSubSbc(List<SBOCandidate> candidates, Specification spec, 
                                   List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String opNames[] = {"add", "adc", "sub", "sbc"};
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l", "ixh", "ixl", "iyh", "iyl"};
        for(String opName : opNames) {
            // register argument:
            for(String regName : regNames) {
                String line = opName + " a," + regName;
                if (opName.equals("sub") && regName.startsWith("i")) continue;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // constant argument:
            for(Integer constant:spec.allowed8bitConstants) {
                String line = opName + " a," + constant;
                if (constant == 0) {
                    if (opName.equals("add") || opName.equals("sub")) continue;
                }
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // (hl):
            if (spec.allowRamUse) {
                String line = opName + " a,(hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
            }
            
            // (ix+d) / (iy+d):
            if (spec.allowRamUse) {
                for(String reg:new String[]{"ix","iy"}) {
                    for(Integer constant:spec.allowedOffsetConstants) {
                        if (constant >= 0) {
                            String line = opName + " a,("+reg+"+"+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        } else {
                            String line = opName + " a,("+reg+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        }
                    }
                }
            }
        }
        
        String ops16bit[] = {"add hl,bc", "add hl,de", "add hl,hl", "add hl,sp",
                             "add ix,bc", "add ix,de", "add ix,ix", "add ix,sp",
                             "add iy,bc", "add iy,de", "add iy,iy", "add iy,sp",
        
                             "adc hl,bc", "adc hl,de", "adc hl,hl", "adc hl,sp",
                             "sbc hl,bc", "sbc hl,de", "sbc hl,hl", "sbc hl,sp"};
        for(String line:ops16bit) {
            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
        }
        return true;
    }    
    
    
    boolean precomputeLd(List<SBOCandidate> candidates, Specification spec, 
                         List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l"};
        for(String arg1 : regNames) {
            for(String arg2 : regNames) {
                if (arg1.equals(arg2)) continue;
                String line = "ld " + arg1 + "," + arg2;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            if (spec.allowRamUse) {
                String line = "ld (hl)," + arg1;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
                line = "ld " + arg1 + ",(hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // constant argument:
            for(Integer constant:spec.allowed8bitConstants) {
                String line = "ld " + arg1 + "," + constant;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;                
            }
            
            // (ix+d) / (iy+d):
            if (spec.allowRamUse) {
                for(String reg:new String[]{"ix","iy"}) {
                    for(Integer constant:spec.allowedOffsetConstants) {
                        if (constant >= 0) {
                            String line = "ld " + arg1 + ",("+reg+"+"+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                            line = "ld ("+reg+"+"+constant+")," + arg1;
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        } else {
                            String line = "ld " + arg1 + ",("+reg+constant+")";
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;
                            line = "ld ("+reg+constant+")," + arg1;
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;
                        }
                    }
                }
            }
        }
        
        String regNames2[] = {"a", "b", "c", "d", "e"};
        String regNames3[] = {"ixh", "ixl", "iyh", "iyl"};
        for(String arg1 : regNames2) {
            for(String arg2 : regNames3) {
                String line = "ld " + arg1 + "," + arg2;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
        }
        for(String arg1 : regNames3) {
            for(String arg2 : regNames2) {
                String line = "ld " + arg1 + "," + arg2;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
        }
        
        // ld (hl)/ixh/ixl/iyh/iyl,n:
        {
            String args[] = {"ixh", "ixl", "iyh", "iyl"};
            for(String  arg1:args) {
                for(Integer constant:spec.allowed8bitConstants) {
                    String line = "ld " + arg1 + "," + constant;
                    if (!precomputeOp(line, candidates, allDependencies, code)) return false;                
                }
            }
        }
        
        if (spec.allowRamUse) {
            for(Integer constant:spec.allowed8bitConstants) {
                String line = "ld (hl)," + constant;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;                
            }
        }
        
//        if (spec.allowRamUse) {
            // ld (nn),a/bc/de/hl/ix/iy/sp
            // ...
        
            // ld a/bc/de/hl/ix/iy/sp,(nn)
            // ...            
//        }

        // ld (ix+o)/(iy+o),n
        if (spec.allowRamUse) {
            for(Integer constant:spec.allowed8bitConstants) {
                for(String reg:new String[]{"ix","iy"}) {
                    for(Integer offset:spec.allowedOffsetConstants) {
                        if (constant >= 0) {
                            String line = "ld ("+reg+"+"+offset+")," + constant;
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        } else {
                            String line = "ld ("+reg+offset+")," + constant;
                            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                        }
                    }
                }
            }
        }

        // ld bc/de/hl/sp/ix/iy,nn
        {
            String args[] = {"bc", "de", "hl", "sp", "ix", "iy"};
            for(String  arg1:args) {
                for(Integer constant:spec.allowed16bitConstants) {
                    String line = "ld " + arg1 + "," + constant;
                    if (!precomputeOp(line, candidates, allDependencies, code)) return false;                
                }
            }
        }
        
        String otherOps[] = {//"ld a,i", "ld a,r", "ld i,a", "ld r,a",
                             //"ld sp,hl", "ld sp,ix", "ld sp,iy",
                             "ld ixl,ixh", "ld ixh,ixl", 
                             "ld iyl,iyh", "ld iyh,iyl",
                             };
        for(String line:otherOps) {
            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
        }
        if (spec.allowRamUse) {
            String otherRamOps[] = {"ld (bc),a", "ld (de),a",
                                    "ld a,(bc)", "ld a,(de)"};
            for(String line:otherRamOps) {
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
            }            
        }
        return true;
    }
    
    
    boolean precomputeRotations(List<SBOCandidate> candidates, Specification spec, 
                                List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String opNames[] = {"rlc", "rl", "rrc", "rr"};
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l"};
        for(String op : opNames) {
            for(String reg : regNames) {
                String line = op + " " + reg;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            if (spec.allowRamUse) {
                String line = op + " (hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // (ix+o)/(iy+o):
            for(String reg:new String[]{"ix","iy"}) {
                for(Integer constant:spec.allowedOffsetConstants) {
                    if (constant >= 0) {
                        String line = op + " ("+reg+"+"+constant+")";
                        if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                    } else {
                        String line = op + " ("+reg+constant+")";
                        if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                    }
                }
            }        
        }        
        String otherOps[] = {"rlca", "rla", "rrca", "rra"};
        for(String line:otherOps) {
            if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
        }
        return true;
    }


    boolean precomputeShifts(List<SBOCandidate> candidates, Specification spec, 
                             List<CPUOpDependency> allDependencies, CodeBase code)
    {
        String opNames[] = {"sla", "sra", "srl", "sli"};
        String regNames[] = {"a", "b", "c", "d", "e", "h", "l"};
        for(String op : opNames) {
            for(String reg : regNames) {
                String line = op + " " + reg;
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            if (spec.allowRamUse) {
                String line = op + " (hl)";
                if (!precomputeOp(line, candidates, allDependencies, code)) return false;
            }
            
            // (ix+o)/(iy+o):
            for(String reg:new String[]{"ix","iy"}) {
                for(Integer constant:spec.allowedOffsetConstants) {
                    if (constant >= 0) {
                        String line = op + " ("+reg+"+"+constant+")";
                        if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                    } else {
                        String line = op + " ("+reg+constant+")";
                        if (!precomputeOp(line, candidates, allDependencies, code)) return false;            
                    }
                }
            }   
        }        
        return true;
    }
    
    
    boolean sequenceMakesSense(SBOCandidate op1, SBOCandidate op2, CodeBase code)
    {
        if (op1.op.spec.getName().equals("ld") &&
            op2.op.spec.getName().equals("ld")) {
            if (op1.op.args.get(0).isRegister(code) &&
                op1.op.args.get(1).isRegister(code) &&
                op2.op.args.get(0).isRegister(code) &&
                op2.op.args.get(1).isRegister(code) && 
                op1.op.args.get(0).registerOrFlagName.equals(op2.op.args.get(1).registerOrFlagName) &&
                op2.op.args.get(0).registerOrFlagName.equals(op1.op.args.get(1).registerOrFlagName)) {
                return false;
            }
            // This is already captured in the more general pattern below, so I commented it out:
//            if (op1.op.args.get(0).isRegister(code) &&
//                op2.op.args.get(0).isRegister(code) &&
//                op2.op.args.get(0).registerOrFlagName.equals(op1.op.args.get(0).registerOrFlagName)) {
//                return false;
//            }
            // op1, op2 is useless if op2's output is a superseteq of op1's, but op2 does not take any input dependency from op1
            {
                boolean op2outputIsSuperset = true;
                boolean op2dependsOnOp1 = false;
                for(int i = 0;i<op1.outputDependencies.length;i++) {
                    if (op1.outputDependencies[i] && !op2.outputDependencies[i]) {
                        op2outputIsSuperset = false;
                        break;
                    }
                    if (op1.outputDependencies[i] && op2.inputDependencies[i]) {
                        op2dependsOnOp1 = true;
                        break;
                    }
                }
                if (op2outputIsSuperset && !op2dependsOnOp1) return false;
            }
        }
        
        return true;
    }
    
    
    boolean depthFirstSearch(int depth, int codeAddress,
                             List<SBOCandidate> candidateOps) throws Exception
    {
        if (depth >= codeMaxOps || codeAddress >= codeMaxAddress) {
            try {
                int size = codeAddress - spec.codeStartAddress;

                // Check to ensure the solution has a chance to be better than the current one,
                // otherwise, don't even bother evaluating:
                if (bestOps != null &&
                    (size > bestSize ||
                     (size == bestSize && currentOps.size() > bestOps.size()))) {
                    return false;
                }
                
                solutionsEvaluated++;
                
//                if (currentOps.size() == 2) {
//                    System.out.println("" + currentOps);
//                }
                
                int time = 0;
                for(int i = 0; i < numberOfRandomSolutionChecks; i++) {
                    time = evaluateSolution(codeAddress);
                    if (time < 0) return false;
                }
                if (bestOps == null || 
                    size < bestSize ||
                    (size == bestSize && currentOps.size() < bestOps.size()) ||
                    (size == bestSize && currentOps.size() == bestOps.size() && time < bestTime)) {
                    bestOps = new ArrayList<>();
                    bestOps.addAll(currentOps);
                    bestSize = size;
                    bestTime = time;
                    
                    if (showNewBestDuringSearch) {
                        config.info("New solution found after "+solutionsEvaluated+" solutions tested (size: "+size+", time: " + time + "):");
                        for(CPUOp op:bestOps) {
                            config.info("    " + op);
                        }
                    }
                }
                return true;
            } catch(ProcessorException e) {
                // This could happen if the program self-modifies itself and garbles the codebase,
                // resulting in an inexisting opcode.
                return false;
            }
        } else {
            boolean found = false;
            // the very last op must contribute to the goal:
            for(SBOCandidate candidate : candidateOps) {
                if (!candidate.directContributionToGoal &&
                    (depth == codeMaxOps-1 || codeMaxAddress == codeAddress + candidate.bytes.length)) {
                    continue;
                }
                boolean dependenciesSatisfied = true;
                for(int i = 0;i<nDependencies;i++) {
                    if (candidate.inputDependencies[i] && !currentDependencies[depth][i]) {
                        dependenciesSatisfied = false;
                    }
                    currentDependencies[depth+1][i] = currentDependencies[depth][i] || candidate.outputDependencies[i];
                }
                if (dependenciesSatisfied && 
                    codeAddress + candidate.bytes.length <= codeMaxAddress) {                
                    
                    int nextAddress = codeAddress;
                    for(int i = 0; i < candidate.bytes.length; i++) {
                        z80Memory.writeByte(nextAddress, candidate.bytes[i]);
                        nextAddress++;
                    }
                    currentOps.add(candidate.op);
                    
                    if (depthFirstSearch(depth+1, nextAddress, candidate.potentialFollowUps)) {
                        found = true;
                        // we keep going, in case we find a solution of the same size, but faster
                    }
                    currentOps.remove(currentOps.size()-1);
                }
            }
            return found;
        }
    }
    
    
    // return -1 is solution fails
    // return time it takes if solution succeeds
    int evaluateSolution(int breakPoint) throws Exception
    {
        // evaluate solution:
        z80.reset();

        for(RegisterNames register: eightBitRegisters) {
            z80.setRegisterValue(register, rand.nextInt(256));
        }
        z80.setProgramCounter(spec.codeStartAddress);
                         
        // randomize constants:
        for(InputParameter parameter:spec.parameters) {
            int value = parameter.minValue + rand.nextInt((parameter.maxValue - parameter.minValue)+1);
            parameter.symbol.exp = Expression.constantExpression(value, config);
            parameter.symbol.clearCache();            
        }
        
        // randomize the memory contents:
        // ...
        
        // execute initial state:
        if (!spec.initCPU(z80, code)) {
            return -1;
        }
        
        while(z80.getProgramCounter() < breakPoint && 
              z80.getTStates() < spec.maxSimulationTime) {
            z80.executeOneInstruction();
        }
        
        // check if the solution worked:
        if (spec.checkGoalState(z80, z80Memory, code)) {
            return (int)z80.getTStates();
        } else {
            return -1;
        }
    }
}
