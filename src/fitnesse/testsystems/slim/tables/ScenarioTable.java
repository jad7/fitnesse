// Copyright (C) 2003-2009 by Object Mentor, Inc. All rights reserved.
// Released under the terms of the CPL Common Public License version 1.0.
package fitnesse.testsystems.slim.tables;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;

import fitnesse.slim.instructions.Instruction;
import fitnesse.testsystems.*;
import fitnesse.testsystems.slim.SlimTestContext;
import fitnesse.testsystems.slim.Table;
import fitnesse.testsystems.slim.results.SlimTestResult;


public class ScenarioTable extends SlimTable {
  private static final String instancePrefix = "scenarioTable";
  private static final String underscorePattern = "\\W_(?=\\W|$)";
  private static Class<? extends ScriptTable> defaultChildClass = ScriptTable.class;
  private String name;
  private List<String> inputs = new ArrayList<>();
  private Set<String> outputs = new HashSet<>();
  private final int colsInHeader = table.getColumnCountInRow(0);
  private boolean parameterized = false;

  public ScenarioTable(Table table, String tableId,
                       SlimTestContext testContext) {
    super(table, tableId, testContext);
  }

  @Override
  protected String getTableType() {
    return instancePrefix;
  }

  @Override
  public List<SlimAssertion> getAssertions() throws SyntaxError {
    parseTable();

    // Note: scenario's only add instructions when needed to,
    // since they might need parameters.
    return Collections.emptyList();
  }

  private void parseTable() throws SyntaxError {
    validateHeader();

    parameterized = determineParameterized();
    name = getScenarioName();
    getTestContext().addScenario(name, this);
    getScenarioArguments();
  }

  protected boolean determineParameterized() {
    String firstNameCell = table.getCellContents(1, 0);
    return isNameParameterized(firstNameCell);
  }

    protected void getScenarioArguments() {
    if (parameterized) {
      getArgumentsForParameterizedName();
    } else {
      getArgumentsForAlternatingName();
    }
  }

  private void getArgumentsForAlternatingName() {
    for (int inputCol = 2; inputCol < colsInHeader; inputCol += 2) {
      String argName = table.getCellContents(inputCol, 0);

      splitInputAndOutputArguments(argName);
    }
  }

  private void splitInputAndOutputArguments(String argName) {
    argName = argName.trim();
    if (argName.endsWith("?")) {
      String disgracedArgName = Disgracer.disgraceMethodName(argName);
      outputs.add(disgracedArgName);
    } else {
      String disgracedArgName = Disgracer.disgraceMethodName(argName);
      inputs.add(disgracedArgName);
    }
  }

  private void getArgumentsForParameterizedName() {
    String argumentString = table.getCellContents(2, 0);
    String[] arguments = argumentString.split(",");

    for (String argument : arguments) {
        splitInputAndOutputArguments(argument);
    }
  }

  protected void addInput(String argument) {
    inputs.add(argument);
  }

  protected void addOutput(String argument) {
    outputs.add(argument);
  }

  public String getScenarioName() {
    if (parameterized) {
      String parameterizedName = table.getCellContents(1, 0);

      return unparameterize(parameterizedName);
    } else {
      return getNameFromAlternatingCells();
    }
  }

  public static boolean isNameParameterized(String firstNameCell) {
    Pattern regPat = Pattern.compile(underscorePattern);
    Matcher underscoreMatcher = regPat.matcher(firstNameCell);

    return underscoreMatcher.find();
  }

  public static String unparameterize(String firstNameCell) {
    String name = firstNameCell.replaceAll(underscorePattern, " ").trim();

    return Disgracer.disgraceClassName(name);
  }

  private String getNameFromAlternatingCells() {
    StringBuilder nameBuffer = new StringBuilder();

    for (int nameCol = 1; nameCol < colsInHeader; nameCol += 2)
      nameBuffer.append(table.getCellContents(nameCol, 0)).append(" ");

    return Disgracer.disgraceClassName(nameBuffer.toString().trim());
  }

  private void validateHeader() throws SyntaxError {
    if (colsInHeader <= 1) {
      throw new SyntaxError("Scenario tables must have a name.");
    }
  }

  public String getName() {
    return name;
  }

  public Set<String> getInputs() {
    return new HashSet<>(inputs);
  }

  public Set<String> getOutputs() {
    return new HashSet<>(outputs);
  }

  public List<SlimAssertion> call(final Map<String, String> scenarioArguments,
                   SlimTable parentTable, int row) throws TestExecutionException {
    Table newTable = getTable().asTemplate(new Table.CellContentSubstitution() {
      @Override
      public String substitute(String content) throws SyntaxError {
        for (Map.Entry<String, String> scenarioArgument : scenarioArguments.entrySet()) {
          String arg = scenarioArgument.getKey();
          if (getInputs().contains(arg)) {
            String argument = scenarioArguments.get(arg);
            content = StringUtils.replace(content, "@" + arg, argument);
            content = StringUtils.replace(content, "@{" + arg + "}", argument);
          } else {
            throw new SyntaxError(String.format("The argument %s is not an input to the scenario.", arg));
          }
        }
        return content;
      }
    });
    ScenarioTestContext testContext = new ScenarioTestContext(parentTable.getTestContext());
    ScriptTable t = createChild(testContext, parentTable, newTable);
    parentTable.addChildTable(t, row);
    List<SlimAssertion> assertions = t.getAssertions();
    assertions.add(makeAssertion(Instruction.NOOP_INSTRUCTION, new ScenarioExpectation(t, row)));
    return assertions;
  }

  protected ScriptTable createChild(ScenarioTestContext testContext, SlimTable parentTable, Table newTable) throws TableCreationException {
    ScriptTable scriptTable;
    if (parentTable instanceof ScriptTable) {
      scriptTable = createChild((ScriptTable) parentTable, newTable, testContext);
    } else {
      scriptTable = createChild(defaultChildClass, newTable, testContext);
    }
    scriptTable.setCustomComparatorRegistry(customComparatorRegistry);
    return scriptTable;
  }

  protected ScriptTable createChild(ScriptTable parentScriptTable, Table newTable, SlimTestContext testContext) throws TableCreationException {
    return createChild(parentScriptTable.getClass(), newTable, testContext);
  }

  protected ScriptTable createChild(Class<? extends ScriptTable> parentTableClass, Table newTable, SlimTestContext testContext) throws TableCreationException {
      return SlimTableFactory.createTable(parentTableClass, newTable, id, testContext);
  }

  public static void setDefaultChildClass(Class<? extends ScriptTable> defaultChildClass) {
    ScenarioTable.defaultChildClass = defaultChildClass;
  }

  public static Class<? extends ScriptTable> getDefaultChildClass() {
    return defaultChildClass;
  }

  public List<SlimAssertion> call(String[] args, ScriptTable parentTable, int row) throws TestExecutionException {
    Map<String, String> scenarioArguments = new HashMap<>();

    for (int i = 0; (i < inputs.size()) && (i < args.length); i++)
      scenarioArguments.put(inputs.get(i), args[i]);

    return call(scenarioArguments, parentTable, row);
  }

  public boolean isParameterized() {
    return parameterized;
  }

///// scriptTable matcher logic:
  public String[] matchParameters(String invokingString) {
    String parameterizedName;

    if (parameterized) {
      parameterizedName = table.getCellContents(1, 0);
    } else if (!this.inputs.isEmpty()) {
      StringBuilder nameBuffer = new StringBuilder();

      for (int nameCol = 1; nameCol < colsInHeader; nameCol += 2)
        nameBuffer.append(table.getCellContents(nameCol, 0))
          .append(" _ ");

      parameterizedName = nameBuffer.toString().trim();
    } else {
      return null;
    }

    return getArgumentsMatchingParameterizedName(parameterizedName,
      invokingString);
  }

  private String[] getArgumentsMatchingParameterizedName(
    String parameterizedName, String invokingString) {
    Matcher matcher = makeParameterizedNameMatcher(parameterizedName,
      invokingString);

    if (matcher.matches()) {
      return extractNamesFromMatcher(matcher);
    } else {
      return null;
    }
  }

  private Matcher makeParameterizedNameMatcher(String parameterizedName,
                                               String invokingString) {
    String patternString = parameterizedName.replaceAll("_", "(.*)");
    Pattern pattern = Pattern.compile(patternString);
    Matcher matcher = pattern.matcher(invokingString);

    return matcher;
  }

  private String[] extractNamesFromMatcher(Matcher matcher) {
    String[] arguments = new String[matcher.groupCount()];

    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = matcher.group(i + 1);
    }

    return arguments;
  }
//// till here

  private final class ScenarioExpectation extends RowExpectation {
    private ScriptTable scriptTable;

    private ScenarioExpectation(ScriptTable scriptTable, int row) {
      super(-1, row); // We don't care about anything but the row.
      this.scriptTable = scriptTable;
    }

    @Override
    public TestResult evaluateExpectation(Object returnValue) {
      SlimTable parent = scriptTable.getParent();
      ExecutionResult testStatus = ((ScenarioTestContext) scriptTable.getTestContext()).getExecutionResult();
      if (outputs.isEmpty() || testStatus != ExecutionResult.PASS){
    	  // if the scenario has no output parameters
    	  // or the scenario failed
    	  // then the whole line should be flagged
    	  parent.getTable().updateContent(getRow(), new SlimTestResult(testStatus));
      }
      return null;
    }

    @Override
    protected SlimTestResult createEvaluationMessage(String actual, String expected) {
      return null;
    }
  }

  // This context is mainly used to determine if the scenario table evaluated successfully
  // This determines the execution result for the "calling" table row.
  protected final class ScenarioTestContext implements SlimTestContext {

    private final SlimTestContext testContext;
    private final TestSummary testSummary = new TestSummary();

    public ScenarioTestContext(SlimTestContext testContext) {
      this.testContext = testContext;
    }

    @Override
    public String getSymbol(String symbolName) {
      return testContext.getSymbol(symbolName);
    }

    @Override
    public void setSymbol(String symbolName, String value) {
      testContext.setSymbol(symbolName, value);
    }

    @Override
    public void addScenario(String scenarioName, ScenarioTable scenarioTable) {
      testContext.addScenario(scenarioName, scenarioTable);
    }

    @Override
    public ScenarioTable getScenario(String scenarioName) {
      return testContext.getScenario(scenarioName);
    }

    @Override
    public Collection<ScenarioTable> getScenarios() {
      return testContext.getScenarios();
    }

    @Override
    public void incrementPassedTestsCount() {
      increment(ExecutionResult.PASS);
    }

    @Override
    public void incrementFailedTestsCount() {
      increment(ExecutionResult.FAIL);
    }

    @Override
    public void incrementErroredTestsCount() {
      increment(ExecutionResult.ERROR);
    }

    @Override
    public void incrementIgnoredTestsCount() {
      increment(ExecutionResult.IGNORE);
    }

    @Override
    public void increment(ExecutionResult result) {
      testContext.increment(result);
      testSummary.add(result);
    }

    @Override
    public void increment(TestSummary summary) {
      testContext.increment(summary);
      testSummary.add(summary);
    }

    ExecutionResult getExecutionResult() {
      return ExecutionResult.getExecutionResult(testSummary);
    }

    @Override
    public TestPage getPageToTest() {
      return testContext.getPageToTest();
    }
  }
}
