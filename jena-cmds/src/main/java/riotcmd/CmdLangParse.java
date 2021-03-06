/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package riotcmd;

import java.io.IOException ;
import java.io.InputStream ;
import java.io.OutputStream ;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream ;

import arq.cmdline.ModContext ;
import arq.cmdline.ModLangOutput ;
import arq.cmdline.ModLangParse ;
import arq.cmdline.ModTime ;
import jena.cmd.ArgDecl ;
import jena.cmd.CmdException;
import jena.cmd.CmdGeneral ;
import org.apache.jena.Jena ;
import org.apache.jena.atlas.io.IO ;
import org.apache.jena.atlas.lib.InternalErrorException ;
import org.apache.jena.atlas.lib.Pair ;
import org.apache.jena.atlas.web.ContentType ;
import org.apache.jena.atlas.web.TypedInputStream ;
import org.apache.jena.query.ARQ ;
import org.apache.jena.riot.* ;
import org.apache.jena.riot.lang.LabelToNode ;
import org.apache.jena.riot.lang.StreamRDFCounting ;
import org.apache.jena.riot.process.inf.InfFactory ;
import org.apache.jena.riot.process.inf.InferenceSetupRDFS ;
import org.apache.jena.riot.system.* ;
import org.apache.jena.riot.tokens.Tokenizer ;
import org.apache.jena.riot.tokens.TokenizerFactory ;
import org.apache.jena.sparql.core.DatasetGraph ;
import org.apache.jena.sparql.core.DatasetGraphFactory ;
import org.apache.jena.system.JenaSystem ;

/** Common framework for running RIOT parsers */
public abstract class CmdLangParse extends CmdGeneral
{
    static { JenaSystem.init(); }
    protected ModTime modTime                   = new ModTime() ;
    protected ModLangParse modLangParse         = new ModLangParse() ;
    protected ModLangOutput modLangOutput       = new ModLangOutput() ;
    protected InferenceSetupRDFS setup          = null ; 
    protected ModContext modContext             = new ModContext() ;
    protected ArgDecl strictDecl                = new ArgDecl(ArgDecl.NoValue, "strict") ;

    protected boolean cmdStrictMode = false ; 
    
    protected CmdLangParse(String[] argv)
    {
        super(argv) ;
        addModule(modContext) ;
        addModule(modTime) ;
        addModule(modLangOutput) ;
        addModule(modLangParse) ;
        
        super.modVersion.addClass(Jena.class) ;
        // Force - sometimes initialization does not cause these
        // to initialized early enough for reflection.
        String x1 = ARQ.VERSION ;
        String x2 = ARQ.BUILD_DATE ;
        super.modVersion.addClass(RIOT.class) ;
        
    }

    @Override
    protected String getSummary() {
        return getCommandName()+" [--time] [--check|--noCheck] [--sink] [--base=IRI] [--out=FORMAT] [--compress] file ..." ;
    }

    protected List<ParseRecord> outcomes = new ArrayList<>(); 
    
    OutputStream outputWrite = System.out ;
    StreamRDF outputStream = null ;

    @Override
    protected void processModulesAndArgs() {
        cmdStrictMode = super.contains(strictDecl) ;
    }
    
    protected interface PostParseHandler { void postParse(); }
    
    static class ParseRecord {
        final String baseURI;
        final String filename;
        final boolean success;
        final long timeMillis;
        final long triples; 
        final long quads;
        final long tuples = 0;
        
        public ParseRecord(String baseURI, String filename,
                           boolean successful, long timeMillis,
                           long countTriples, long countQuads) {
            this.baseURI = baseURI;
            this.filename = filename;
            this.success = successful;
            this.timeMillis = timeMillis;
            this.triples = countTriples;
            this.quads = countQuads;
        }
    }

    @Override
    protected void exec() {
        boolean oldStrictValue = SysRIOT.isStrictMode() ;
        if ( modLangParse.strictMode() )
            SysRIOT.setStrictMode(true) ;
        try { exec$() ; }
        finally { SysRIOT.setStrictMode(oldStrictValue) ; } 
    }
    
    protected void exec$() {
        
        if ( modLangParse.getRDFSVocab() != null )
            setup = new InferenceSetupRDFS(modLangParse.getRDFSVocab()) ;
     
        if ( modLangOutput.compressedOutput() ) {
            try { outputWrite = new GZIPOutputStream(outputWrite, true) ; }
            catch (IOException e) { IO.exception(e);}
        }
            
        outputStream = null ;
        PostParseHandler postParse = null ;

        outputStream = createStreamSink() ;
        if ( outputStream == null ) {
            Pair<StreamRDF, PostParseHandler> p = createAccumulateSink() ;
            outputStream = p.getLeft() ;
            postParse = p.getRight();
        }
        
        try {
            if ( super.getPositional().isEmpty() ) {
                ParseRecord parseRec = parseFile("-");
                outcome(parseRec);
            }
            else {
                boolean b = super.getPositional().size() > 1;
                for ( String fn : super.getPositional() ) {
                    if ( b && !super.isQuiet() )
                        SysRIOT.getLogger().info("File: " + fn);
                    ParseRecord parseRec = parseFile(fn);
                    outcome(parseRec);
                }
            }
            if ( postParse != null )
                postParse.postParse();
            // Post parse information.
            // Total if more then one file.
            if ( super.getPositional().size() > 1 && modTime.timingEnabled() ) {
                long totalMillis = 0; 
                long totalTriples = 0;
                long totalQuads = 0;
                long totalTuples = 0;
                boolean allSuccessful = true;
                
                for ( ParseRecord pRec : outcomes ) {
                    if ( pRec.timeMillis >= 0 ) 
                        totalMillis += pRec.timeMillis;
                    totalTriples += pRec.triples;
                    totalQuads += pRec.quads;
                    totalTuples += pRec.tuples;
                    allSuccessful = allSuccessful & pRec.success;
                }
                output("Total", true, totalTriples, totalQuads, totalTuples, totalMillis);
            }
        } finally {
            if ( outputWrite != System.out )
                IO.close(outputWrite) ;
            else
                IO.flush(outputWrite);    
            System.err.flush() ;
        }
        
        // exit(1) if there were any errors.
        for ( ParseRecord pr : outcomes ) {
            if ( ! pr.success )
                throw new CmdException();
        }
    }
    
    public void outcome(ParseRecord rtn) {
        outcomes.add(rtn);
        if ( modTime.timingEnabled() )
            output(rtn);
    }
    
    public ParseRecord parseFile(String filename) {
        String baseURI = modLangParse.getBaseIRI() ;
        if ( filename.equals("-") ) {
            if ( baseURI == null )
                baseURI = "http://base/";
            TypedInputStream in = TypedInputStream.wrap(System.in) ;
            return parseRIOT(baseURI, "stdin", in) ;
        } else {
            try ( TypedInputStream in = RDFDataMgr.open(filename) ) {
                return parseRIOT(baseURI, filename, in) ;    
            } catch (RiotNotFoundException ex) {
                System.err.println("Can't open '"+filename+"' "+ex.getMessage()) ;
                return new ParseRecord(null, filename, false, -1, -1, -1);
            }
        }
    }

    protected abstract Lang selectLang(String filename, ContentType contentType, Lang dftLang  ) ;

    protected ParseRecord parseRIOT(String baseURI, String filename, TypedInputStream in) {
        ContentType ct = in.getMediaType() ;
        
        baseURI = SysRIOT.chooseBaseIRI(baseURI, filename) ;
        
        boolean checking = true ;
        if ( modLangParse.explicitChecking() )  checking = true ;
        if ( modLangParse.explicitNoChecking() ) checking = false ;
        
        RDFParserBuilder builder = RDFParser.create();

        ErrorHandler errHandler = ErrorHandlerFactory.errorHandlerWarn ;
        if ( checking ) {
            if ( modLangParse.stopOnBadTerm() )
                errHandler = ErrorHandlerFactory.errorHandlerStd  ;
            else
                // Try to go on if possible.  This is the default behaviour.
                errHandler = ErrorHandlerFactory.errorHandlerWarn ;
        }
        
        if ( modLangParse.skipOnBadTerm() ) {
            // TODO skipOnBadterm
        }
        
        Lang lang = selectLang(filename, ct, RDFLanguages.NQUADS) ;  
        if ( ! RDFLanguages.isQuads(lang) && ! RDFLanguages.isTriples(lang) )
            throw new CmdException("Undefined language: "+lang) ; 
        builder.lang(lang);
        
        // Make a flag.
        // Input and output subflags.
        // If input is "label, then output using NodeToLabel.createBNodeByLabelRaw() ;
        // else use NodeToLabel.createBNodeByLabel() ;
        // Also, as URI.
        final boolean labelsAsGiven = false ;
        
//        NodeToLabel labels = SyntaxLabels.createNodeToLabel() ;
//        if ( labelsAsGiven )
//            labels = NodeToLabel.createBNodeByLabelEncoded() ;
        
        if ( labelsAsGiven )
            builder.labelToNode(LabelToNode.createUseLabelAsGiven());

        StreamRDF s = outputStream ; 
        if ( setup != null )
            s = InfFactory.inf(s, setup) ;
        StreamRDFCounting sink = StreamRDFLib.count(s) ;
        s = null ;
        
        boolean successful = true;
        if ( checking ) 
            SysRIOT.setStrictMode(true);
        builder.errorHandler(errHandler);
        
        modTime.startTimer() ;
        sink.start() ;
        
        builder.source(in);
        RDFParser parser = builder.build();
        try {
            parser.parse(sink);
            successful = true;
        } catch (RiotException ex) {
            successful = false;
        }
        sink.finish() ;
        long x = modTime.endTimer() ;
        ParseRecord outcome = new ParseRecord(baseURI, filename, successful, x, sink.countTriples(), sink.countQuads());
        return outcome;
    }
    
    /** Create a streaming output sink if possible */
    protected StreamRDF createStreamSink() {
        if ( modLangParse.toBitBucket() )
            return StreamRDFLib.sinkNull() ;
        
        RDFFormat fmt = modLangOutput.getOutputStreamFormat() ;
        if ( fmt == null )
            return null ;
        /** Create an accumulating output stream for later pretty printing */        
        return StreamRDFWriter.getWriterStream(outputWrite, fmt) ;
    }
    
    /** Create an accumulating output stream for later pretty printing */
    protected Pair<StreamRDF, PostParseHandler> createAccumulateSink() {
        final DatasetGraph dsg = DatasetGraphFactory.create() ;
        StreamRDF sink = StreamRDFLib.dataset(dsg) ;
        final RDFFormat fmt = modLangOutput.getOutputFormatted() ;
        PostParseHandler handler = new PostParseHandler() {
            @Override
            public void postParse() {
                // Try as dataset, then as graph.
                WriterDatasetRIOTFactory w = RDFWriterRegistry.getWriterDatasetFactory(fmt) ;
                if ( w != null ) {
                    RDFDataMgr.write(outputWrite, dsg, fmt) ;
                    return ;
                }
                WriterGraphRIOTFactory wg = RDFWriterRegistry.getWriterGraphFactory(fmt) ;
                if ( wg != null ) {
                    RDFDataMgr.write(System.out, dsg.getDefaultGraph(), fmt) ;
                    return ;
                }
                throw new InternalErrorException("failed to find the writer: "+fmt) ;  
            }
        } ;
        return Pair.create(sink, handler) ;
    }
    
    protected Tokenizer makeTokenizer(InputStream in) {
        Tokenizer tokenizer = TokenizerFactory.makeTokenizerUTF8(in) ;
        return tokenizer ;
    }
    
    protected void output(ParseRecord rtn) {
        output(rtn.filename, rtn.success,
               rtn.triples, rtn.quads, rtn.tuples,
               rtn.timeMillis) ;
    }
    
    protected void output(String label, boolean success, long numberTriples, long numberQuads, long numberTuples, long timeMillis) {
        double timeSec = timeMillis/1000.0 ;
        long total = numberTriples + numberQuads + numberTuples;
        StringBuilder sb = new StringBuilder();
        if ( total > 0 ) {
            sb.append(label);
            if ( success )
                appendFmt(sb, " : %,5.2f sec", timeSec);
            appendCount(sb, numberTriples, "Triple", "Triples", "TPS");
            appendCount(sb, numberQuads,   "Quad",   "Quads",   "QPS");
            appendCount(sb, numberTuples,  "Tuple",  "Tuples",  "TPS");
            if ( success && timeMillis > 0 )
                appendFmt(sb," : %,.2f %s", numberTriples/timeSec, "per second");
        } else {
            appendFmt(sb, "%s :  (No Output)", label) ;
        }
        System.err.println(sb.toString());
    }
    
    private void appendFmt(StringBuilder sb, String fmt, Object ... args) {
        sb.append(String.format(fmt, args)) ;
    }

    private void appendCount(StringBuilder sb, long number, String itemName, String itemsName, String rateName) {
        if ( number > 0 ) {
            String str = itemName;
            if ( number > 1 )
                str=itemsName;
            sb.append(String.format(" : %,d %s", number, str));
        }
    }

    protected void output(String label) {
        System.err.printf("%s : \n", label) ;
    }
}
