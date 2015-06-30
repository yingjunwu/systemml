/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.transform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;

import com.ibm.bi.dml.parser.DataExpression;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionParser;
import com.ibm.bi.dml.runtime.instructions.MRJobInstruction;
import com.ibm.bi.dml.runtime.instructions.mr.CSVReblockInstruction;
import com.ibm.bi.dml.runtime.matrix.CSVReblockMR;
import com.ibm.bi.dml.runtime.matrix.JobReturn;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.CSVReblockMR.AssignRowIDMRReturn;
import com.ibm.bi.dml.runtime.matrix.data.CSVFileFormatProperties;
import com.ibm.bi.dml.runtime.matrix.data.FileFormatProperties;
import com.ibm.bi.dml.runtime.matrix.data.InputInfo;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.OutputInfo;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration;
import com.ibm.bi.dml.runtime.transform.TransformationAgent.TX_METHOD;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.runtime.util.UtilFunctions;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

public class DataTransform {
	
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	/**
	 * Method to read the header line from the input data file.
	 * 
	 * @param fs
	 * @param prop
	 * @param smallestFile
	 * @return
	 * @throws IOException
	 */
	private static String readHeaderLine(FileSystem fs, CSVFileFormatProperties prop, String smallestFile) throws IOException {
		String line = null;
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(smallestFile))));
		line = br.readLine();
		br.close();
		if(prop.hasHeader()) {
			; // nothing here
		}
		else 
		{
			// construct header with default column names, V1, V2, etc.
			int ncol = Pattern.compile( Pattern.quote(prop.getDelim()) ).split(line, -1).length;
			line = null;
			
			StringBuilder sb = new StringBuilder();
			sb.append("V1");
			for(int i=2; i <= ncol; i++)
				sb.append(prop.getDelim() + "V" + i);
			line = sb.toString();
		}
		return line;
	}
	
	/**
	 * Method to construct a mapping between column names and their
	 * corresponding column IDs. The mapping is used to prepare the
	 * specification file in <code>processSpecFile()</code>.
	 * 
	 * @param fs
	 * @param prop
	 * @param headerLine
	 * @param smallestFile
	 * @return
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	private static HashMap<String, Integer> processColumnNames(FileSystem fs, CSVFileFormatProperties prop, String headerLine, String smallestFile) throws IllegalArgumentException, IOException {
		HashMap<String, Integer> colNames = new HashMap<String,Integer>();
		
		String escapedDelim = Pattern.quote(prop.getDelim());
		Pattern compiledDelim = Pattern.compile(escapedDelim);
		String[] names = compiledDelim.split(headerLine, -1);
			
		for(int i=0; i< names.length; i++)
			colNames.put(UtilFunctions.unquote(names[i].trim()), i+1);

		return colNames;
	}
	
	/**
	 * Convert input transformation specification file with column names into a
	 * specification with corresponding column Ids. This file is sent to all the
	 * relevant MR jobs.
	 * 
	 * @param fs
	 * @param inputPath
	 * @param smallestFile
	 * @param colNames
	 * @param prop
	 * @param specFileWithNames
	 * @return
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	private static String processSpecFile(FileSystem fs, String inputPath, String smallestFile, HashMap<String,Integer> colNames, CSVFileFormatProperties prop, String specFileWithNames) throws IllegalArgumentException, IOException {
		// load input spec file with Names
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(specFileWithNames))));
		JSONObject inputSpec = JSONObject.parse(br);
		br.close();
		
		// Build output spec in JSON format
		JSONObject outputSpec = new JSONObject();

		final String NAME = "name";
		final String METHOD = "method";
		final String VALUE = "value";
		final String MV_METHOD_MEAN = "global_mean";
		final String MV_METHOD_MODE = "global_mode";
		final String MV_METHOD_CONSTANT = "constant";
		final String BIN_METHOD_WIDTH = "equi-width";
		final String BIN_METHOD_HEIGHT = "equi-height";
		final String SCALE_METHOD_Z = "z-score";
		final String SCALE_METHOD_M = "mean-subtraction";
		
		String stmp = null;
		JSONObject entry = null;
		byte btmp = 0;
		// --------------------------------------------------------------------------
		// Missing value imputation
		if( inputSpec.get(TX_METHOD.IMPUTE.toString()) != null ) {
			JSONArray arrtmp = (JSONArray) inputSpec.get(TX_METHOD.IMPUTE.toString());
			
			JSONArray attrList = new JSONArray(arrtmp.size());
			JSONArray txMethods = new JSONArray(arrtmp.size());
			JSONArray repConstants = new JSONArray(arrtmp.size());
			for(int i=0; i<arrtmp.size(); i++) {
				entry = (JSONObject)arrtmp.get(i);
				
				stmp = UtilFunctions.unquote((String) entry.get(NAME));
				attrList.add(colNames.get(stmp));
				
				stmp = UtilFunctions.unquote((String) entry.get(METHOD));
				if(stmp.equals(MV_METHOD_MEAN))
					btmp = (byte)1;
				else if ( stmp.equals(MV_METHOD_MODE))
					btmp = (byte)2;
				else if ( stmp.equals(MV_METHOD_CONSTANT))
					btmp = (byte)3;
				else
					throw new IOException("Unknown missing value imputation method (" + stmp + ") in transformation specification file: " + specFileWithNames);
				txMethods.add( btmp );
				
				repConstants.add( entry.get(VALUE));
			}
			
			JSONObject mvSpec = new JSONObject();
			mvSpec.put(TransformationAgent.JSON_ATTRS, attrList);
			mvSpec.put(TransformationAgent.JSON_MTHD, txMethods);
			mvSpec.put(TransformationAgent.JSON_CONSTS, repConstants);
			outputSpec.put(TX_METHOD.IMPUTE.toString(), mvSpec);
		}
		// --------------------------------------------------------------------------
		// Recoding
		if( inputSpec.get(TX_METHOD.RECODE.toString()) != null ) {
			JSONArray arrtmp = (JSONArray) inputSpec.get(TX_METHOD.RECODE.toString());
			JSONArray attrList = new JSONArray(arrtmp.size());
			for(int i=0; i<arrtmp.size(); i++) {
				stmp = UtilFunctions.unquote( (String)arrtmp.get(i) );
				attrList.add( colNames.get(stmp) );
			}
			JSONObject rcdSpec = new JSONObject();
			rcdSpec.put(TransformationAgent.JSON_ATTRS, attrList);
			outputSpec.put(TX_METHOD.RECODE.toString(), rcdSpec);
		}
		// --------------------------------------------------------------------------
		// Binning
		if( inputSpec.get(TX_METHOD.BIN.toString()) != null ) {
			JSONArray arrtmp = (JSONArray) inputSpec.get(TX_METHOD.BIN.toString());
			
			JSONArray attrList = new JSONArray(arrtmp.size());
			JSONArray txMethods = new JSONArray(arrtmp.size());
			JSONArray numBins = new JSONArray(arrtmp.size());
			
			for(int i=0; i<arrtmp.size(); i++) {
				entry = (JSONObject)arrtmp.get(i);
				
				stmp = UtilFunctions.unquote((String) entry.get(NAME));
				attrList.add( colNames.get(stmp) );
				
				stmp = UtilFunctions.unquote((String) entry.get(METHOD));
				if(stmp.equals(BIN_METHOD_WIDTH))
					btmp = (byte)1;
				else if ( stmp.equals(BIN_METHOD_HEIGHT))
					throw new IOException("Equi-height binning method is not yet supported, in transformation specification file: " + specFileWithNames);
				else
					throw new IOException("Unknown missing value imputation method (" + stmp + ") in transformation specification file: " + specFileWithNames);
				txMethods.add( btmp );
				
				//stmp = UtilFunctions.unquote( (String) entry.get(TransformationAgent.JSON_NBINS) );
				numBins.add(entry.get(TransformationAgent.JSON_NBINS));
			}
			
			JSONObject binSpec = new JSONObject();
			binSpec.put(TransformationAgent.JSON_ATTRS, attrList);
			binSpec.put(TransformationAgent.JSON_MTHD, txMethods);
			binSpec.put(TransformationAgent.JSON_NBINS, numBins);
			outputSpec.put(TX_METHOD.BIN.toString(), binSpec);
		}
		// --------------------------------------------------------------------------
		// Dummycoding
		if( inputSpec.get(TX_METHOD.DUMMYCODE.toString()) != null ) {
			JSONArray arrtmp = (JSONArray) inputSpec.get(TX_METHOD.DUMMYCODE.toString());
			JSONArray attrList = new JSONArray(arrtmp.size());
			for(int i=0; i<arrtmp.size(); i++) {
				stmp = UtilFunctions.unquote( (String)arrtmp.get(i) );
				attrList.add( colNames.get(stmp) );
			}
			JSONObject dcdSpec = new JSONObject();
			dcdSpec.put(TransformationAgent.JSON_ATTRS, attrList);
			outputSpec.put(TX_METHOD.DUMMYCODE.toString(), dcdSpec);
		}
		// --------------------------------------------------------------------------
		// Scaling
		if(inputSpec.get(TX_METHOD.SCALE.toString()) !=null) {
			JSONArray arrtmp = (JSONArray) inputSpec.get(TX_METHOD.SCALE.toString());
			
			JSONArray attrList = new JSONArray(arrtmp.size());
			JSONArray txMethods = new JSONArray(arrtmp.size());
			for(int i=0; i<arrtmp.size(); i++) {
				entry = (JSONObject)arrtmp.get(i);
				
				stmp = UtilFunctions.unquote((String) entry.get(NAME));
				attrList.add( colNames.get(stmp) );
				
				stmp = UtilFunctions.unquote((String) entry.get(METHOD));
				if(stmp.equals(SCALE_METHOD_M))
					btmp = (byte)1;
				else if ( stmp.equals(SCALE_METHOD_Z))
					btmp = (byte)2;
				else
					throw new IOException("Unknown missing value imputation method (" + stmp + ") in transformation specification file: " + specFileWithNames);
				txMethods.add( btmp );
			}
			
			JSONObject scaleSpec = new JSONObject();
			scaleSpec.put(TransformationAgent.JSON_ATTRS, attrList);
			scaleSpec.put(TransformationAgent.JSON_MTHD, txMethods);
			outputSpec.put(TX_METHOD.SCALE.toString(), scaleSpec);
		}
		// --------------------------------------------------------------------------
		
		String specFileWithIDs = MRJobConfiguration.constructTempOutputFilename();
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fs.create(new Path(specFileWithIDs),true)));
		out.write(outputSpec.toString());
		out.close();
		
		return specFileWithIDs;
	}

	
	/**
	 * Private class to hold the relevant input parameters to transform operation.
	 */
	private static class TransformOperands {
		String inputPath=null, txMtdPath=null, applyTxPath=null, specFile=null;
		boolean isApply=false;
		CSVFileFormatProperties inputCSVProperties = null;
		
		TransformOperands(String inst, MatrixObject inputMatrix) {
			String[] instParts = inst.split(Instruction.OPERAND_DELIM);
			
			inputPath = inputMatrix.getFileName();
			txMtdPath = instParts[3];
			
			isApply = Boolean.parseBoolean(instParts[5]);
			if ( isApply ) {
				applyTxPath = instParts[4];
			}
			else {
				specFile = instParts[4];
			}
	
			inputCSVProperties = (CSVFileFormatProperties)inputMatrix.getFileFormatProperties();
		}
	}
	
	/**
	 * Helper function to move transformation metadata files from a temporary
	 * location to permanent location. These files (e.g., header before and
	 * after transformation) are generated by a single mapper, while applying
	 * data transformations. Note that, these files must be ultimately be placed
	 * under the existing metadata directory (txMtdPath), which is
	 * simultaneously read by other mappers. If they are not created at a
	 * temporary location, then MR tasks fail due to changing timestamps on
	 * txMtdPath.
	 * 
	 * @param fs
	 * @param tmpPath
	 * @param txMtdPath
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	private static void moveFilesFromTmp(FileSystem fs, String tmpPath, String txMtdPath) throws IllegalArgumentException, IOException 
	{
		// move files from temporary location to txMtdPath
		fs.rename( new Path(tmpPath + "/" + "column.names.given"), new Path(txMtdPath + "/" + "column.names.given"));
		fs.rename( new Path(tmpPath + "/" + "column.names.transformed"), new Path(txMtdPath + "/" + "column.names.transformed"));
		if(fs.exists(new Path(tmpPath +"/Dummycode/" + "dummyCodeMaps.csv")))
			fs.rename( new Path(tmpPath + "/Dummycode/" + "dummyCodeMaps.csv"), new Path(txMtdPath + "/Dummycode/" + "dummyCodeMaps.csv"));
	}
	
	/**
	 * Helper function to determine the number of columns after applying
	 * transformations. Note that dummycoding changes the number of columns.
	 * 
	 * @param fs
	 * @param header
	 * @param delim
	 * @param tfMtdPath
	 * @return
	 * @throws IllegalArgumentException
	 * @throws IOException
	 * @throws DMLRuntimeException
	 */
	private static int getNumColumnsTf(FileSystem fs, String header, String delim, String tfMtdPath) throws IllegalArgumentException, IOException, DMLRuntimeException {
		String[] columnNames = Pattern.compile(Pattern.quote(delim)).split(header, -1);
		int ret = columnNames.length;
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(tfMtdPath + "/spec.json"))));
		JSONObject spec = JSONObject.parse(br);
		br.close();
		
		// fetch relevant attribute lists
		if ( spec.get(TX_METHOD.DUMMYCODE.toString()) == null )
			return ret;
		
		JSONArray dcdList = (JSONArray) ((JSONObject)spec.get(TX_METHOD.DUMMYCODE.toString())).get(TransformationAgent.JSON_ATTRS);

		// look for numBins among binned columns
		for(Object o : dcdList) 
		{
			int id = ((Long) o).intValue();
			
			Path binpath = new Path( tfMtdPath + "/Bin/" + UtilFunctions.unquote(columnNames[id-1]) + TransformationAgent.BIN_FILE_SUFFIX);
			Path rcdpath = new Path( tfMtdPath + "/Recode/" + UtilFunctions.unquote(columnNames[id-1]) + TransformationAgent.NDISTINCT_FILE_SUFFIX);
			
			if ( TransformationAgent.checkValidInputFile(fs, binpath, false ) )
			{
				br = new BufferedReader(new InputStreamReader(fs.open(binpath)));
				int nbins = UtilFunctions.parseToInt(br.readLine().split(TransformationAgent.TXMTD_SEP)[3]);
				br.close();
				ret += (nbins-1);
			}
			else if ( TransformationAgent.checkValidInputFile(fs, rcdpath, false ) )
			{
				br = new BufferedReader(new InputStreamReader(fs.open(rcdpath)));
				int ndistinct = UtilFunctions.parseToInt(br.readLine());
				br.close();
				ret += (ndistinct-1);
			}
			else
				throw new DMLRuntimeException("Relevant transformation metadata for column (id=" + id + ", name=" + columnNames[id-1] + ") is not found.");
		}
		//System.out.println("Number of columns in transformed data: " + ret);
		return ret;
	}
	
	/**
	 * Main method to create and/or apply transformation metdata using MapReduce.
	 * 
	 * @param jobinst
	 * @param inputMatrices
	 * @param shuffleInst
	 * @param otherInst
	 * @param resultIndices
	 * @param outputMatrices
	 * @param numReducers
	 * @param replication
	 * @return
	 * @throws Exception
	 */
	public static JobReturn mrDataTransform(MRJobInstruction jobinst, MatrixObject[] inputMatrices, String shuffleInst, String otherInst, byte[] resultIndices, MatrixObject[] outputMatrices, int numReducers, int replication) throws Exception {
		
		String[] insts = shuffleInst.split(Instruction.INSTRUCTION_DELIM);
		
		// Parse transform instruction (the first instruction) to obtain relevant fields
		TransformOperands oprnds = new TransformOperands(insts[0], inputMatrices[0]);
		
		JobConf job = new JobConf();
		FileSystem fs = FileSystem.get(job);
		
		// find the first file in alphabetical ordering of partfiles in directory inputPath 
		String smallestFile = CSVReblockMR.findSmallestFile(job, oprnds.inputPath);
		
		// find column names
		String headerLine = readHeaderLine(fs, oprnds.inputCSVProperties, smallestFile);
		HashMap<String, Integer> colNamesToIds = processColumnNames(fs, oprnds.inputCSVProperties, headerLine, smallestFile);
		int numColumns = colNamesToIds.size();
		
		ArrayList<Integer> csvoutputs= new ArrayList<Integer>();
		ArrayList<Integer> bboutputs = new ArrayList<Integer>();
		
		// divide output objects based on output format (CSV or BinaryBlock)
		for(int i=0; i < outputMatrices.length; i++) 
		{
			if(outputMatrices[i].getFileFormatProperties() != null 
					&& outputMatrices[i].getFileFormatProperties().getFileFormat() == FileFormatProperties.FileFormat.CSV)
				csvoutputs.add(i);
			else
				bboutputs.add(i);
		}
		boolean isCSV = (csvoutputs.size() > 0);
		boolean isBB  = (bboutputs.size()  > 0);
		String tmpPath = MRJobConfiguration.constructTempOutputFilename();
		
		JobReturn retCSV = null, retBB = null;
		
		if (!oprnds.isApply) {
			// build specification file with column IDs insteadof column names
			String specFileWithIDs = processSpecFile(fs, oprnds.inputPath, 
														smallestFile, colNamesToIds, 
														oprnds.inputCSVProperties, 
														oprnds.specFile);
			colNamesToIds = null; // enable GC on colNamesToIds

			// Build transformation metadata, including recode maps, bin definitions, etc.
			// Also, generate part offsets file (counters file), which is to be used in csv-reblock
			
			String partOffsetsFile =  MRJobConfiguration.constructTempOutputFilename();
			long numRows = GenTfMtdMR.runJob(oprnds.inputPath, 
												oprnds.txMtdPath, specFileWithIDs, 
												smallestFile, partOffsetsFile, 
												oprnds.inputCSVProperties, numColumns, 
												replication, headerLine);
			
			// store the specFileWithIDs as transformation metadata
			MapReduceTool.copyFileOnHDFS(specFileWithIDs, oprnds.txMtdPath + "/" + "spec.json");
			
			int numColumnsTf = getNumColumnsTf(fs, headerLine, oprnds.inputCSVProperties.getDelim(), oprnds.txMtdPath);
			
			// Apply transformation metadata, and perform actual transformation 
			if(isCSV)
				retCSV = ApplyTfCSVMR.runJob(oprnds.inputPath, specFileWithIDs, 
												oprnds.txMtdPath, tmpPath, 
												outputMatrices[csvoutputs.get(0)].getFileName(), 
												oprnds.inputCSVProperties, numColumns, 
												replication, headerLine);
			
			if(isBB)
				retBB = ApplyTfBBMR.runJob(oprnds.inputPath, 
											insts[1], otherInst, 
											specFileWithIDs, oprnds.txMtdPath, 
											tmpPath, outputMatrices[bboutputs.get(0)].getFileName(), 
											partOffsetsFile, oprnds.inputCSVProperties, 
											numRows, numColumns, numColumnsTf, 
											replication, headerLine);
				
		}
		else {
			colNamesToIds = null; // enable GC on colNamesToIds
			
			// copy given transform metadata (applyTxPath) to specified location (txMtdPath)
			MapReduceTool.deleteFileIfExistOnHDFS(new Path(oprnds.txMtdPath), job);
			MapReduceTool.copyFileOnHDFS(oprnds.applyTxPath, oprnds.txMtdPath);
			
			// path to specification file
			String specFileWithIDs = oprnds.txMtdPath + "/" + "spec.json";
			int numColumnsTf = getNumColumnsTf(fs, headerLine, 
												oprnds.inputCSVProperties.getDelim(), 
												oprnds.txMtdPath);
			
			if (isCSV) 
				// Apply transformation metadata, and perform actual transformation 
				retCSV = ApplyTfCSVMR.runJob(oprnds.inputPath, specFileWithIDs, 
												oprnds.applyTxPath, tmpPath, 
												outputMatrices[csvoutputs.get(0)].getFileName(), 
												oprnds.inputCSVProperties, numColumns, 
												replication, headerLine);
			
			if(isBB) 
			{
				// compute part offsets file
				CSVReblockInstruction rblk = (CSVReblockInstruction) InstructionParser.parseSingleInstruction(insts[1]);
				CSVReblockInstruction newrblk = (CSVReblockInstruction) rblk.clone((byte)0);
				AssignRowIDMRReturn ret1 = CSVReblockMR.runAssignRowIDMRJob(new String[]{oprnds.inputPath}, 
																			new InputInfo[]{InputInfo.CSVInputInfo}, 
																			new int[]{newrblk.brlen}, new int[]{newrblk.bclen}, 
																			newrblk.toString(), replication, new String[]{smallestFile});
				
				// apply transformation metadata, as well as reblock the resulting data
				retBB = ApplyTfBBMR.runJob(oprnds.inputPath, 
													insts[1], otherInst, specFileWithIDs, 
													oprnds.txMtdPath, tmpPath, 
													outputMatrices[bboutputs.get(0)].getFileName(), 
													ret1.counterFile.toString(), 
													oprnds.inputCSVProperties, 
													ret1.rlens[0], ret1.clens[0], numColumnsTf, 
													replication, headerLine);
			}
		}
		
		// copy auxiliary data (old and new header lines) from temporary location to txMtdPath
		moveFilesFromTmp(fs, tmpPath, oprnds.txMtdPath);

		// generate matrix metadata file for outputs
		if ( retCSV != null ) 
		{
			CSVFileFormatProperties prop = new CSVFileFormatProperties(
												false, 
												oprnds.inputCSVProperties.getDelim(), // use the same header as the input
												false, Double.NaN, null);
			
			MapReduceTool.writeMetaDataFile (outputMatrices[csvoutputs.get(0)].getFileName()+".mtd", 
												ValueType.DOUBLE, retCSV.getMatrixCharacteristics(0), 
												OutputInfo.CSVOutputInfo, prop);
			return retCSV;
		}

		if ( retBB != null )
		{
			MapReduceTool.writeMetaDataFile (outputMatrices[bboutputs.get(0)].getFileName()+".mtd", 
					ValueType.DOUBLE, retBB.getMatrixCharacteristics(0), OutputInfo.BinaryBlockOutputInfo);
			return retBB;
		}
		
		return null;
			
	}
	
	/**
	 * Main method to create and/or apply transformation metdata in-memory, on a
	 * single node.
	 * 
	 * @param inst
	 * @param inputMatrices
	 * @param outputMatrices
	 * @return
	 * @throws IOException
	 * @throws DMLRuntimeException 
	 */
	public static JobReturn cpDataTransform(String inst, MatrixObject[] inputMatrices, MatrixObject[] outputMatrices) throws IOException, DMLRuntimeException {
		String[] insts = inst.split(Instruction.INSTRUCTION_DELIM);
		
		// Parse transform instruction (the first instruction) to obtain relevant fields
		TransformOperands oprnds = new TransformOperands(insts[0], inputMatrices[0]);
		
		JobConf job = new JobConf();
		FileSystem fs = FileSystem.get(job);
		// find the first file in alphabetical ordering of partfiles in directory inputPath 
		String smallestFile = CSVReblockMR.findSmallestFile(job, oprnds.inputPath);
		
		// find column names
		String headerLine = readHeaderLine(fs, oprnds.inputCSVProperties, smallestFile);
		HashMap<String, Integer> colNamesToIds = processColumnNames(fs, oprnds.inputCSVProperties, headerLine, smallestFile);
		
		ArrayList<Integer> csvoutputs= new ArrayList<Integer>();
		ArrayList<Integer> bboutputs = new ArrayList<Integer>();
		
		// divide output objects based on output format (CSV or BinaryBlock)
		for(int i=0; i < outputMatrices.length; i++) 
		{
			if(outputMatrices[i].getFileFormatProperties() != null && outputMatrices[i].getFileFormatProperties().getFileFormat() == FileFormatProperties.FileFormat.CSV)
				csvoutputs.add(i);
			else
				bboutputs.add(i);
		}
		boolean isCSV = (csvoutputs.size() > 0);
		boolean isBB  = (bboutputs.size()  > 0);
		
		JobReturn ret = null;
		
		if (!oprnds.isApply) {
			// build specification file with column IDs insteadof column names
			String specFileWithIDs = processSpecFile(fs, oprnds.inputPath, smallestFile, colNamesToIds, oprnds.inputCSVProperties, oprnds.specFile);
			MapReduceTool.copyFileOnHDFS(specFileWithIDs, oprnds.txMtdPath + "/" + "spec.json");
	
			ret = performTransform(job, fs, oprnds.inputPath, colNamesToIds.size(), oprnds.inputCSVProperties, specFileWithIDs, oprnds.txMtdPath, oprnds.isApply, outputMatrices[0], headerLine, isBB, isCSV );
			
		}
		else {
			// copy given transform metadata (applyTxPath) to specified location (txMtdPath)
			MapReduceTool.deleteFileIfExistOnHDFS(new Path(oprnds.txMtdPath), job);
			MapReduceTool.copyFileOnHDFS(oprnds.applyTxPath, oprnds.txMtdPath);
			
			// path to specification file
			String specFileWithIDs = oprnds.txMtdPath + "/" + "spec.json";
			
			ret = performTransform(job, fs, oprnds.inputPath, colNamesToIds.size(), oprnds.inputCSVProperties, specFileWithIDs,  oprnds.txMtdPath, oprnds.isApply, outputMatrices[0], headerLine, isBB, isCSV );
		}
		
		return ret;
	}
	
	/**
	 * Helper function to fetch and sort the list of part files under the given
	 * input directory.
	 * 
	 * @param input
	 * @param fs
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	private static ArrayList<Path> collectInputFiles(String input, FileSystem fs) throws FileNotFoundException, IOException 
	{
		Path path = new Path(input);
		ArrayList<Path> files=new ArrayList<Path>();
		if(fs.isDirectory(path))
		{
			for(FileStatus stat: fs.listStatus(path, CSVReblockMR.hiddenFileFilter))
				files.add(stat.getPath());
			Collections.sort(files);
		}
		else
			files.add(path);

		return files;
	}
	
	private static int countNumRows(ArrayList<Path> files, CSVFileFormatProperties prop, FileSystem fs) throws IOException 
	{
		int numRows=0;
		for(int fileNo=0; fileNo<files.size(); fileNo++)
		{
			BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(files.get(fileNo))));
			if(fileNo==0 && prop.hasHeader() ) 
				br.readLine(); //ignore header
			
			while ( br.readLine() != null)
				numRows++;
			br.close();
		}
		return numRows;
	}
	
	/**
	 * Main method to create and/or apply transformation metdata in-memory, on a single node.
	 * 
	 * @param job
	 * @param fs
	 * @param inputPath
	 * @param ncols
	 * @param prop
	 * @param specFileWithIDs
	 * @param txMtdPath
	 * @param applyTxPath
	 * @param isApply
	 * @param outputPath
	 * @param headerLine
	 * @throws IOException
	 * @throws DMLRuntimeException 
	 */
	private static JobReturn performTransform(JobConf job, FileSystem fs, String inputPath, int ncols, CSVFileFormatProperties prop, String specFileWithIDs, String txMtdPath, boolean isApply, MatrixObject result, String headerLine, boolean isBB, boolean isCSV ) throws IOException, DMLRuntimeException {
		
		// Parse input specification and other parameters
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(specFileWithIDs))));
		JSONObject spec = JSONObject.parse(br);
		br.close();
		
		Pattern _delim = Pattern.compile(Pattern.quote(prop.getDelim()));

		// Initialize transformation agents
		String[] _naStrings = Pattern.compile(Pattern.quote(DataExpression.DELIM_NA_STRING_SEP)).split(prop.getNAStrings(), -1);
		TransformationAgent.init(_naStrings, headerLine, prop.getDelim());
		MVImputeAgent _mia = new MVImputeAgent(spec);
		RecodeAgent _ra = new RecodeAgent(spec);
		BinAgent _ba = new BinAgent(spec);
		DummycodeAgent _da = new DummycodeAgent(spec, ncols);

		// List of files to read
		ArrayList<Path> files = collectInputFiles(inputPath, fs);
				
		
		// ---------------------------------
		// Construct transformation metadata
		// ---------------------------------
		
		String line = null;
		String[] words  = null;
		
		int numRows = 0, numColumnsTf=0;
		
		if (!isApply) {
			for(int fileNo=0; fileNo<files.size(); fileNo++)
			{
				br = new BufferedReader(new InputStreamReader(fs.open(files.get(fileNo))));
				if(fileNo==0 && prop.hasHeader() ) 
					br.readLine(); //ignore header
				
				line = null;
				while ( (line = br.readLine()) != null) {
					//System.out.println(line);
					words = _delim.split(line.toString().trim(), -1);
				
					_mia.prepare(words);
					_ra.prepare(words);
					_ba.prepare(words);
					
					numRows++;
				}
				br.close();
			}
			
			_mia.outputTransformationMetadata(txMtdPath, fs);
			_ba.outputTransformationMetadata(txMtdPath, fs);
			_ra.outputTransformationMetadata(txMtdPath, fs);
		
			// prepare agents for the subsequent phase of applying transformation metadata
			
			// NO need to loadTxMtd for _ra, since the maps are already present in the memory
			Path tmp = new Path(txMtdPath);
			_mia.loadTxMtd(job, fs, tmp);
			_ba.loadTxMtd(job, fs, tmp);
			
			_da.setRecodeMapsCP( _ra.getCPRecodeMaps() );
			_da.setNumBins(_ba.getBinList(), _ba.getNumBins());
			_da.loadTxMtd(job, fs, tmp);
		}
		else {
			// Count the number of rows
			numRows = countNumRows(files, prop, fs);
			
			// Load transformation metadata
			// prepare agents for the subsequent phase of applying transformation metadata
			Path tmp = new Path(txMtdPath);
			_mia.loadTxMtd(job, fs, tmp);
			_ra.loadTxMtd(job, fs, tmp);
			_ba.loadTxMtd(job, fs, tmp);
			
			_da.setRecodeMaps( _ra.getRecodeMaps() );
			_da.setNumBins(_ba.getBinList(), _ba.getNumBins());
			_da.loadTxMtd(job, fs, tmp);
		}
		
		// -----------------------------
		// Apply transformation metadata
		// -----------------------------
        
		numColumnsTf = getNumColumnsTf(fs, headerLine, prop.getDelim(), txMtdPath);

		MapReduceTool.deleteFileIfExistOnHDFS(result.getFileName());
		BufferedWriter out=new BufferedWriter(new OutputStreamWriter(fs.create(new Path(result.getFileName()),true)));		
		StringBuilder sb = new StringBuilder();
		
		MatrixBlock mb = null; 
		if ( isBB ) 
		{
			mb = new MatrixBlock(numRows, numColumnsTf, false );
			mb.allocateDenseBlock();
		}

		int r = 0; // rowid to be used in filling the matrix block
		
		for(int fileNo=0; fileNo<files.size(); fileNo++)
		{
			br = new BufferedReader(new InputStreamReader(fs.open(files.get(fileNo))));
			if(fileNo==0 && prop.hasHeader() ) { 
				String header = br.readLine();
				String dcdHeader = _da.constructDummycodedHeader(header, prop.getDelim());
				//numColumnsTf = _da.generateDummycodeMaps(fs, txMtdPath, ncols);
				DataTransform.generateHeaderFiles(fs, txMtdPath, header, dcdHeader);
				
				if ( isCSV )
					out.write(dcdHeader + "\n");
			}
			
			line = null;
			while ( (line = br.readLine()) != null) {
				words = _delim.split(line.toString().trim(), -1);

				words = _mia.apply(words);
				if(!isApply)
					words = _ra.cp_apply(words);
				else
					words = _ra.apply(words);
				words = _ba.apply(words);
				words = _da.apply(words);

				if (isCSV)
				{
					sb.setLength(0);
					sb.append(words[0]);
					for(int i=1; i<words.length; i++) 
					{
						sb.append(prop.getDelim());
						sb.append(words[i] != null ? words[i] : "");
					}
					out.write(sb.toString());
					out.write("\n");
				}
				if( isBB ) 
				{
					for(int c=0; c<words.length; c++)
					{
						if(words[c] != null)
							mb.setValueDenseUnsafe(r, c, UtilFunctions.parseToDouble(words[c]));
					}
				}
				r++;
			}
			br.close();
		}
		
		if(mb != null)
		{
			mb.recomputeNonZeros();
			mb.examSparsity();
			
			result.acquireModify(mb);
			result.release();
			result.exportData();
		}
		out.close();
		
		MatrixCharacteristics mc = new MatrixCharacteristics(numRows, numColumnsTf, (int) result.getNumRowsPerBlock(), (int) result.getNumColumnsPerBlock());
		JobReturn ret = new JobReturn(new MatrixCharacteristics[]{mc}, true);

		return ret;
	}
	
	public static void generateHeaderFiles(FileSystem fs, String txMtdDir, String origHeader, String newHeader) throws IOException {
		// write out given header line
		Path pt=new Path(txMtdDir+"/column.names.given");
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		br.write(origHeader);
		br.close();

		// write out the new header line (after all transformations)
		pt = new Path(txMtdDir + "/column.names.transformed");
		br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		br.write(newHeader);
		br.close();
	}
}

