/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.instrumentation.reporter;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.javascript.jscomp.instrumentation.reporter.ProfilingReport.ProfilingData;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * This class will read a file that contains the instrumentation mapping generated by the compiler
 * production instrumentation pass, and also a list of other files which are the reports sent by the
 * instrumented production code. It will then take these inputs and generate a single JSON which
 * provides a detailed breakdown of each instrumentation point.
 */
@GwtIncompatible
final class ProductionInstrumentationReporter {

  @Option(
      name = "--mapping_file",
      usage = "The file name of the mapping generated by the production instrumentation pass.",
      required = true)
  private String instrumentationMappingLocation = "";

  @Option(
      name = "--reports_directory",
      usage =
          "The folder/directory which contains all the reports created by the instrumented"
              + " production code.",
      required = true)
  private String instrumentationReportsDirectory = "";

  @Option(
      name = "--result_output",
      usage =
          "Use this flag to provide the name of the final report that will be generated by"
              + "this reporter.",
      required = true)
  private String finalResultOutput = "";

  public static void main(String[] args) {
    try {
      new ProductionInstrumentationReporter().doMain(args);
    } catch (IOException e) {
      System.err.println(e.getMessage());
    }
  }

  /** This function reads a file at the given filePath and converts the contents into a string. */
  public static String readFile(String filePath) throws IOException {
    return CharStreams.toString(Files.newBufferedReader(Paths.get(filePath), UTF_8));
  }

  /**
   * Reads all files found in folder and converts the contents of each file to a Map<String,
   * ProfilingData> data structure where it is a mapping of the unique param value to the encoded
   * values. The folder contains all the reports sent by the instrumented production code.
   */
  private ImmutableList<Map<String, ProfilingData>> getAllExecutionResults(File folder)
      throws IOException {
    List<Map<String, ProfilingData>> result = new ArrayList<>();

    for (final File fileEntry : folder.listFiles()) {
      String executionResult = readFile(fileEntry.getAbsolutePath());
      Type type = new TypeToken<Map<String, ProfilingData>>() {}.getType();
      Map<String, ProfilingData> executedInstrumentationData =
          new Gson().fromJson(executionResult, type);

      result.add(executedInstrumentationData);
    }

    return ImmutableList.copyOf(result);
  }

  /**
   * Creates a file with the given fileName (including extension) with the contents of the file
   * being provided by fileContents.
   */
  private void createFile(String fileName, String fileContents) throws IOException {

    File fold = new File(fileName);
    fold.delete();
    File myObj = new File(fileName);
    myObj.createNewFile();

    try (Writer myWriter = Files.newBufferedWriter(Paths.get(fileName), UTF_8)) {
      myWriter.write(fileContents);
    }
  }

  private void doMain(String[] args) throws IOException {

    parseCmdLineArguments(args);

    InstrumentationMapping instrumentationMapping =
        InstrumentationMapping.parse(instrumentationMappingLocation);

    File folder = new File(instrumentationReportsDirectory);

    ImmutableList<Map<String, ProfilingData>> listOfExecutionResults =
        getAllExecutionResults(folder);

    ProfilingReport profilingReport =
        ProfilingReport.createProfilingReport(instrumentationMapping, listOfExecutionResults);

    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    createFile(finalResultOutput, gson.toJson(profilingReport));
  }

  private void parseCmdLineArguments(String[] args) {
    CmdLineParser parser = new CmdLineParser(this);
    parser.setUsageWidth(80);

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      return;
    }
  }

  public enum InstrumentationType {
    FUNCTION,
    BRANCH,
    BRANCH_DEFAULT;

    public static List<InstrumentationType> convertFromStringList(List<String> typesAsString) {
      return typesAsString.stream().map(InstrumentationType::valueOf).collect(Collectors.toList());
    }
  }
}
