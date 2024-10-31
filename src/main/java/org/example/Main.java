package org.example;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {
    private static final String TODO_PREFIX = "//todo(codegen-removal): ";

    private static final String SCRIPT_FOOTER_LOG =
            "Remove agent starting and finishing logs, sc-machine is printing them now\n" +
            "If your agent is ScActionInitiatedAgent and uses event only to get action node via event.GetOtherElement() then you can remove event from method arguments and use ScAction & action instead of your action node\n" +
            "If your agent is ScActionInitiatedAgent and is having method like CheckActionClass(ScAddr actionAddr) that checks connector between action class and actionAddr then you can remove it. Before agent is started sc-machine checks that action belongs to class returned by GetActionClass()\n" +
            "Use action.SetResult() to pass result of your action instead of using answer or answerElements\n" +
            "Use SC_AGENT_LOG_SOMETHING() instead of SC_LOG_SOMETHING to automatically include agent name to logs messages\n" +
            "Use auto const & [names of action arguments] = action.GetArguments<amount of arguments>(); to get action arguments\n";

    private static final String FILE_POSTFIX = "";
    private static final String GENERATED_INCLUDE_REGEX = "#include\\s+.*?generated\\..*?\n";
    private static final String SC_CLASS_REGEX = "SC_CLASS\\s*\\(\\s*\\)";
    private static final String GENERATED_BODY_REGEX = "SC_GENERATED_BODY\\s*\\(\\s*\\)";
    private static final String SC_PROPERTY_REGEX = "SC_PROPERTY\\s*\\(";

    private static final String AGENT_NAME_GROUP = "agentName";
    private static final String MODULE_NAME_GROUP = "moduleName";
    private static final String SUBSCRIPTION_COMMAND_GROUP = "subscriptionCommand";
    private static final String SUBSCRIPTION_ELEMENT_GROUP = "subscriptionElement";
    private static final String EVENT_TYPE_GROUP = "eventType";
    private static final String AGENT_NAME_IN_HPP_REGEX = "class\\s+(?<" + AGENT_NAME_GROUP + ">.+?)\\s*:.*?(?<oldParent>ScAgent)";
    private static final String AGENT_NAME_IN_CPP_REGEX = "SC_AGENT_IMPLEMENTATION\\s*\\(\\s*(?<" + AGENT_NAME_GROUP + ">.*?)\\s*\\)";
    private static final String MODULE_NAME_IN_HPP_REGEX = "class\\s+(?<" + MODULE_NAME_GROUP + ">.*?)\\s*:\\s*public\\s*ScModule";
    private static final String MODULE_NAME_IN_CPP_REGEX = "SC_IMPLEMENT_MODULE\\s*\\(\\s*(?<" + MODULE_NAME_GROUP + ">.*?)\\s*\\)";
    // SC_CLASS\s*\((\s*.*?(Event\s*\((?<subscriptionElement>.*?)\s*,\s*(?<eventType>.*?)\).*?)|(.*?CmdClass\s*\(\"(?<subscriptionCommand>.*?)\"\)))\)
    private static final String AGENT_INITIATION_CONDITION_REGEX = "SC_CLASS\\s*\\((\\s*.*?(Event\\s*\\((?<" + SUBSCRIPTION_ELEMENT_GROUP + ">.*?)\\s*,\\s*(?<" + EVENT_TYPE_GROUP + ">.*?)\\).*?)|(.*?CmdClass\\s*\\(\"(?<" + SUBSCRIPTION_COMMAND_GROUP + ">.*?)\"\\)))\\)";
    private static final String OUTDATED_INCLUDES_REGEX = "((#include\\s*[\"<]sc-agents-common/keynodes/coreKeynodes.hpp[\">])|(#include\\s*[\"<]sc-agents-common/utils/AgentUtils.hpp[\">])|(#include\\s*[\"<]sc-memory/sc_wait.hpp[\">])|(using\\s*namespace\\s*scAgentsCommon\\s*;))\n";
    private static final String NEW_AGENT_HPP_BODY =
            "public:\n" +
            "  ScAddr GetActionClass() const override;\n" +
            "\n" +
            "  ScResult DoProgram(%s const & event, ScAction & action) override;\n";

    private static final String GET_ACTION_CLASS_BODY =
            "::GetActionClass() const\n" +
            "{\n" +
            TODO_PREFIX + "replace action with your action class\n" +
            "  return ScKeynodes::action;\n" +
            "}\n";

    private static final String AGENT_HPP_OVERRIDDEN_GET_ACTION_CLASS =
            "\n" +
            "\n" +
            "ScAddr %s" + GET_ACTION_CLASS_BODY;

    private static final String KEYNODE_NAME_GROUP = "keynodeName";
    private static final String KEYNODE_IDENTIFIER_GROUP = "keynodeIdentifier";
    private static final String TYPE_NAME_BEFORE_GROUP = "typeNameBefore";
    private static final String TYPE_NAME_AFTER_GROUP = "typeNameAfter";
    // SC_PROPERTY\s*\(\s*(ForceCreate\s*(\((?<typeNameBefore>.*?)\))?\s*,\s*)?(Keynode\s*\(\s*"(?<keynodeIdentifier>.*?)"\s*\))\s*(,\s*ForceCreate\s*(\((?<typeNameAfter>.+?)\))?\s*)?\)\s+[^;]*\s(?<keynodeName>.*?)\s*;
    private static final String KEYNODE_REGEX = "SC_PROPERTY\\s*\\(\\s*(ForceCreate\\s*(\\((?<" + TYPE_NAME_BEFORE_GROUP + ">.*?)\\))?\\s*,\\s*)?(Keynode\\s*\\(\\s*\"(?<" + KEYNODE_IDENTIFIER_GROUP + ">.*?)\"\\s*\\))\\s*(,\\s*ForceCreate\\s*(\\((?<" + TYPE_NAME_AFTER_GROUP + ">.+?)\\))?\\s*)?\\)\\s+[^;]*\\s(?<" + KEYNODE_NAME_GROUP + ">.*?)\\s*;";

    private static final String KEYNODE_SUBSTITUTION = "static inline ScKeynode const ${keynodeName}(\"${keynodeIdentifier}\"${typeNameBefore:+, ${typeNameBefore}:${typeNameAfter:+, ${typeNameAfter}}});";

    private static final String SC_CLASS_IN_MODULE_REGEX = "SC_CLASS\\s*\\(\\s*(LoadOrder\\s*\\(.*?\\))?\\)";
    private static final String INITIALIZE_OVERRIDE_MODULE_REGEX = "(virtual)?\\s*sc_result\\s+InitializeImpl\\s*\\(\\s*\\)\\s*override\\s*;";
    private static final String SHUTDOWN_OVERRIDE_MODULE_REGEX = "(virtual)?\\s*sc_result\\s+ShutdownImpl\\s*\\(\\s*\\)\\s*override\\s*;";

    private static final String AGENT_REGISTRATION_REGEX = "SC_AGENT_REGISTER\\s*\\(\\s*(?<" + AGENT_NAME_GROUP + ">.*?)\\s*\\)";
    private static final String AGENT_UN_AND_REGISTRATION_REGEX = "SC_AGENT_(UN)?REGISTER\\s*\\(\\s*(?<" + AGENT_NAME_GROUP + ">.*?)\\s*\\)";

    private static final String CODE_BLOCK_GROUP = "codeBlock";
    private static final String CODE_BLOCK_REGEX = "(?<" + CODE_BLOCK_GROUP + ">\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{(?:[^{}]*|\\{[^{}]*\\})*?\\})*?\\})*?\\})*?\\})*?\\})*?\\})*?\\})*?\\})";
    private static final String INITIALIZE_BODY_MODULE_REGEX = "sc_result\\s+\\w+\\s*::\\s*InitializeImpl\\s*\\(\\s*\\)\\s*" + CODE_BLOCK_REGEX;
    private static final String SHUTDOWN_BODY_MODULE_REGEX = "sc_result\\s+\\w+\\s*::\\s*ShutdownImpl\\s*\\(\\s*\\)\\s*" + CODE_BLOCK_REGEX;

    private static final String CODEGEN_CMAKE_REGEX = "(sc_codegen_ex\\s*\\(.*?\\)|include\\s*\\(.*?codegen.cmake.*?\\))\\s*";
    private static final String CORE_KEYNODES_REGEX = "\\b(scAgentsCommon::)?CoreKeynodes::";
    private static final String AGENT_UTILS_REGEX = ".*?\\b(utils::)?\\bAgentUtils::";

    private static final Pattern FILE_WITH_CODEGEN_PATTERN = Pattern.compile(String.format("(%s)|(%s)|(%s)", GENERATED_BODY_REGEX, AGENT_NAME_IN_CPP_REGEX, MODULE_NAME_IN_CPP_REGEX));
    private static final Pattern KEYNODE_PATTERN = Pattern.compile(KEYNODE_REGEX);
    private static final Pattern MODULE_HPP_PATTERN = Pattern.compile(MODULE_NAME_IN_HPP_REGEX);
    private static final Pattern MODULE_NAME_IN_CPP_PATTERN = Pattern.compile(MODULE_NAME_IN_CPP_REGEX);
    private static final Pattern AGENT_REGISTRATION_PATTERN = Pattern.compile(AGENT_REGISTRATION_REGEX);
    private static final Pattern AGENT_UN_AND_REGISTRATION_PATTERN = Pattern.compile(AGENT_UN_AND_REGISTRATION_REGEX);
    private static final Pattern AGENT_INITIATION_CONDITION_PATTERN = Pattern.compile(AGENT_INITIATION_CONDITION_REGEX);
    private static final Pattern AGENT_NAME_IN_HPP_PATTERN = Pattern.compile(AGENT_NAME_IN_HPP_REGEX);
    private static final Pattern AGENT_BODY_IN_HPP_PATTERN = Pattern.compile(AGENT_NAME_IN_HPP_REGEX + "\\s*" + CODE_BLOCK_REGEX);
    private static final Pattern AGENT_NAME_IN_CPP_PATTERN = Pattern.compile(AGENT_NAME_IN_CPP_REGEX);
    private static final Pattern CODEGEN_CMAKE_PATTERN = Pattern.compile(CODEGEN_CMAKE_REGEX);
    private static final Pattern CORE_KEYNODES_PATTERN = Pattern.compile(CORE_KEYNODES_REGEX);
    private static final Pattern AGENT_UTILS_PATTERN = Pattern.compile(AGENT_UTILS_REGEX);

    private static final List<Pair<String, String>> DEPRECATED_METHODS = Arrays.asList(
            new Pair<>("#include [\"<]sc-memory/sc_struct.hpp[\">]", "#include <sc-memory/sc_structure.hpp>"),
            new Pair<>("#include [\"<]sc-memory/kpm/sc_agent.hpp[\">]", "#include <sc-memory/sc_agent.hpp>"),
            new Pair<>("getFirstByInRelation", "getAnyByInRelation"),
            new Pair<>("getFirstByOutRelation", "getAnyByOutRelation"),
            new Pair<>("TripleWithRelation", "Quintuple"),
            new Pair<>("IsEdge", "IsConnector"),
            new Pair<>("CreateNode", "GenerateNode"),
            new Pair<>("CreateLink", "GenerateLink"),
            new Pair<>("CreateEdge", "GenerateConnector"),
            new Pair<>("GetElementOutputArcsCount", "GetElementEdgesAndOutgoingArcsCount"),
            new Pair<>("GetElementInputArcsCount", "GetElementEdgesAndIncomingArcsCount"),
            new Pair<>("GetEdgeSource", "GetArcSourceElement"),
            new Pair<>("GetEdgeTarget", "GetArcTargetElement"),
            new Pair<>("GetEdgeInfo", "GetConnectorIncidentElements"),
            new Pair<>("Iterator3", "CreateIterator3"),
            new Pair<>("Iterator5", "CreateIterator5"),
            new Pair<>("ForEachIter3", "ForEach"),
            new Pair<>("ForEachIter5", "ForEach"),
            new Pair<>("HelperCheckEdge", "CheckConnector"),
            new Pair<>("FindLinksByContent", "SearchLinksByContent"),
            new Pair<>("FindLinksByContentSubstring", "SearchLinksByContentSubstring"),
            new Pair<>("FindLinksContentsByContentSubstring", "SearchLinksContentsByContentSubstring"),
            new Pair<>("HelperSetSystemIdtf", "SetElementSystemIdentifier"),
            new Pair<>("HelperGetSystemIdtf", "GetElementSystemIdentifier"),
            new Pair<>("HelperResolveSystemIdtf", "ResolveElementSystemIdentifier"),
            new Pair<>("HelperFindBySystemIdtf", "SearchElementBySystemIdentifier"),
            new Pair<>("HelperGenTemplate", "GenerateByTemplate"),
            new Pair<>("HelperSearchTemplate", "SearchByTemplate"),
            new Pair<>("HelperSmartSearchTemplate", "SearchByTemplateInterruptibly"),
            new Pair<>("HelperBuildTemplate", "BuildTemplate"),
            new Pair<>("CalculateStat", "CalculateStatistics"),
            new Pair<>("BeingEventsPending", "BeginEventsPending"),
            new Pair<>("BeingEventsBlocking", "BeginEventsBlocking")
    );

    private static final List<Pair<String, String>> DEPRECATED_TYPES = Arrays.asList(
            new Pair<>("ScType::EdgeUCommon", "ScType::CommonEdge"),
            new Pair<>("ScType::EdgeDCommon", "ScType::CommonArc"),
            new Pair<>("ScType::EdgeUCommonConst", "ScType::ConstCommonEdge"),
            new Pair<>("ScType::EdgeDCommonConst", "ScType::ConstCommonArc"),
            new Pair<>("ScType::EdgeAccess", "ScType::MembershipArc"),
            new Pair<>("ScType::EdgeAccessConstPosPerm", "ScType::ConstPermPosArc"),
            new Pair<>("ScType::EdgeAccessConstNegPerm", "ScType::ConstPermNegArc"),
            new Pair<>("ScType::EdgeAccessConstFuzPerm", "ScType::ConstFuzArc"),
            new Pair<>("ScType::EdgeAccessConstPosTemp", "ScType::ConstTempPosArc"),
            new Pair<>("ScType::EdgeAccessConstNegTemp", "ScType::ConstTempNegArc"),
            new Pair<>("ScType::EdgeAccessConstFuzTemp", "ScType::ConstFuzArc"),
            new Pair<>("ScType::EdgeUCommonVar", "ScType::VarCommonEdge"),
            new Pair<>("ScType::EdgeDCommonVar", "ScType::VarCommonArc"),
            new Pair<>("ScType::EdgeAccessVarPosPerm", "ScType::VarPermPosArc"),
            new Pair<>("ScType::EdgeAccessVarNegPerm", "ScType::VarPermNegArc"),
            new Pair<>("ScType::EdgeAccessVarFuzPerm", "ScType::VarFuzArc"),
            new Pair<>("ScType::EdgeAccessVarPosTemp", "ScType::VarTempPosArc"),
            new Pair<>("ScType::EdgeAccessVarNegTemp", "ScType::VarTempNegArc"),
            new Pair<>("ScType::EdgeAccessVarFuzTemp", "ScType::VarFuzArc"),
            new Pair<>("ScType::NodeConst", "ScType::ConstNode"),
            new Pair<>("ScType::NodeVar", "ScType::VarNode"),
            new Pair<>("ScType::Link", "ScType::NodeLink"),
            new Pair<>("ScType::LinkClass", "ScType::NodeLinkClass"),
            new Pair<>("ScType::NodeStruct", "ScType::NodeStructure"),
            new Pair<>("ScType::LinkConst", "ScType::ConstNodeLink"),
            new Pair<>("ScType::LinkConstClass", "ScType::ConstNodeLinkClass"),
            new Pair<>("ScType::NodeConstTuple", "ScType::ConstNodeTuple"),
            new Pair<>("ScType::NodeConstStruct", "ScType::ConstNodeStructure"),
            new Pair<>("ScType::NodeConstRole", "ScType::ConstNodeRole"),
            new Pair<>("ScType::NodeConstNoRole", "ScType::ConstNodeNonRole"),
            new Pair<>("ScType::ConstNodeNoRole", "ScType::ConstNodeNonRole"),
            new Pair<>("ScType::NodeConstClass", "ScType::ConstNodeClass"),
            new Pair<>("ScType::NodeConstMaterial", "ScType::ConstNodeMaterial"),
            new Pair<>("ScType::LinkVar", "ScType::VarNodeLink"),
            new Pair<>("ScType::LinkVarClass", "ScType::VarNodeLinkClass"),
            new Pair<>("ScType::NodeVarStruct", "ScType::VarNodeStructure"),
            new Pair<>("ScType::NodeVarTuple", "ScType::VarNodeTuple"),
            new Pair<>("ScType::NodeVarRole", "ScType::VarNodeRole"),
            new Pair<>("ScType::NodeVarNoRole", "ScType::VarNodeNonRole"),
            new Pair<>("ScType::VarNodeNoRole", "ScType::VarNodeNonRole"),
            new Pair<>("ScType::NodeVarClass", "ScType::VarNodeClass"),
            new Pair<>("ScType::NodeVarMaterial", "ScType::VarNodeMaterial")
    );

    private static final Set<Pattern> METHODS_WITH_CHANGED_SIGNATURE_PATTERNS = new HashSet<>(Arrays.asList(
            Pattern.compile(".*?\\bFindLinksByContentSubstring\\b"),
            Pattern.compile(".*?\\bFindLinksByContent\\b"),
            Pattern.compile(".*?\\bFindLinksContentsByContentSubstring\\b"),
            Pattern.compile(".*?\\bGetEdgeInfo\\b"),
            Pattern.compile(".*?\\bHelperGenTemplate\\b"),
            Pattern.compile(".*?\\bHelperBuildTemplate\\b")
    ));

    private static final Set<String> EVENTS_WITHOUT_EDGE = new HashSet<>();
    static {
        EVENTS_WITHOUT_EDGE.add("ScEventBeforeEraseElement");
        EVENTS_WITHOUT_EDGE.add("ScEventBeforeChangeLinkContent");
    }

    private static final Map<String, String> OLD_TO_NEW_EVENTS_MAP = new HashMap<>();
    private static final List<Pair<String, String>> OLD_TO_NEW_EVENTS_PAIRS = new ArrayList<>();
    static{
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::AddOutputEdge", "ScEventAfterGenerateOutgoingArc");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_ADD_OUTPUT_ARC", "ScEventAfterGenerateOutgoingArc");
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::AddInputEdge", "ScEventAfterGenerateIncomingArc");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_ADD_INPUT_ARC", "ScEventAfterGenerateIncomingArc");
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::RemoveOutputEdge", "ScEventBeforeEraseOutgoingArc");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_REMOVE_OUTPUT_ARC", "ScEventBeforeEraseOutgoingArc");
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::RemoveInputEdge", "ScEventBeforeEraseIncomingArc");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_REMOVE_INPUT_ARC", "ScEventBeforeEraseIncomingArc");
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::EraseElement", "ScEventBeforeEraseElement");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_REMOVE_ELEMENT", "ScEventBeforeEraseElement");
        OLD_TO_NEW_EVENTS_MAP.put("ScEvent::Type::ContentChanged", "ScEventBeforeChangeLinkContent");
        OLD_TO_NEW_EVENTS_MAP.put("SC_EVENT_CONTENT_CHANGED", "ScEventBeforeChangeLinkContent");

        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::AddOutputEdge", "ScEventAfterGenerateOutgoingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventAddOutputEdge", "ScEventAfterGenerateOutgoingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_ADD_OUTPUT_ARC", "ScKeynodes::sc_event_after_generate_outgoing_arc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::AddInputEdge", "ScEventAfterGenerateIncomingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventAddInputEdge", "ScEventAfterGenerateIncomingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_ADD_INPUT_ARC", "ScKeynodes::sc_event_after_generate_incoming_arc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::RemoveOutputEdge", "ScEventBeforeEraseOutgoingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventRemoveOutputEdge", "ScEventBeforeEraseOutgoingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_REMOVE_OUTPUT_ARC", "ScKeynodes::sc_event_before_erase_outgoing_arc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::RemoveInputEdge", "ScEventBeforeEraseIncomingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventRemoveInputEdge", "ScEventBeforeEraseIncomingArc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_REMOVE_INPUT_ARC", "ScKeynodes::sc_event_before_erase_incoming_arc"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::EraseElement", "ScEventBeforeEraseElement"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventEraseElement", "ScEventBeforeEraseElement"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_REMOVE_ELEMENT", "ScKeynodes::sc_event_before_erase_element"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEvent::Type::ContentChanged", "ScEventBeforeChangeLinkContent"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("ScEventContentChanged", "ScEventBeforeChangeLinkContent"));
        OLD_TO_NEW_EVENTS_PAIRS.add(new Pair<>("SC_EVENT_CONTENT_CHANGED", "ScKeynodes::sc_event_before_change_link_content"));
    }


    public static void main(String[] args) {
        System.out.println("Removing codegen at paths:");
        Arrays.stream(args).forEach(System.out::println);
        processCmake(args);
        processCpp(args);
        System.out.println();
        System.out.println("Helpful tips for new api usage:");
        System.out.println(SCRIPT_FOOTER_LOG);
    }

    private static void processCpp(String[] args) {
        List<File> cppFiles = getFilesWithType(args, "c\\+\\+");
        List<Pair<File, String>> filesWithCodegen = cppFiles.stream()
                .map(Main::readLines)
                .filter(Main::fileIsNotEmpty)
                .filter(pair -> FILE_WITH_CODEGEN_PATTERN.matcher(pair.getSecond()).find())
                .collect(Collectors.toList());
        if (!filesWithCodegen.isEmpty()) {
            System.out.println("Files with codegen detected:");
            filesWithCodegen.forEach(pair -> System.out.println(pair.getFirst()));
        }

        replaceKeynodes(filesWithCodegen);
        replaceModule(filesWithCodegen);
        replaceAgent(filesWithCodegen);

        replaceScPropertyKeynode(readFiles(cppFiles));

        removeOldStatement(readFiles(cppFiles));
        replaceCoreKeynodes(readFiles(cppFiles));
        replaceOldEvents(readFiles(cppFiles));
        printMethodsWithChangedSignatureWarning(readFiles(cppFiles));
        replaceDeprecatedMethods(readFiles(cppFiles));
        replaceDeprecatedTypes(readFiles(cppFiles));
        printAgentUtilsWarnings(readFiles(cppFiles));
        printAgentRegistrationWarnings(readFiles(cppFiles));
    }

    private static List<Pair<File, String>> readFiles(List<File> cppFiles) {
        return cppFiles.stream()
                .filter(File::exists)
                .map(Main::readLines)
                .filter(Main::fileIsNotEmpty)
                .collect(Collectors.toList());
    }

    private static void processCmake(String[] args) {
        List<File> cmakeFiles = getFilesWithType(args, "(cmake)|(text/plain)");
        removeCmakeCodegen(cmakeFiles.stream()
                .map(Main::readLines)
                .filter(Main::fileIsNotEmpty)
                .collect(Collectors.toList()));
    }

    private static List<File> getFilesWithType(String[] args, String requiredType) {
        return Arrays.stream(args)
                .map(File::new)
                .filter(File::exists)
                .map(File::toPath)
                .flatMap(path -> {
                    try {
                        return Files.walk(path);
                    } catch (IOException e) {
                        System.err.println("ERROR: cannot get files for directory " + path);
                        e.printStackTrace();
                        return Arrays.stream(new Path[]{new File("").toPath()});
                    }
                })
                .filter(path -> !path.getFileName().toFile().getName().isEmpty())
                .map(Path::toFile)
                .filter(File::isFile)
                .filter(File::canRead)
                .filter(file -> {
                    try {
                        String type = Files.probeContentType(file.toPath());
                        return type != null && type.matches(".*?" + requiredType + ".*?");
                    } catch (IOException e) {
                        System.err.println("ERROR: cannot check type of " + file);
                        return false;
                    }
                })
                .map(File::getAbsoluteFile)
                .collect(Collectors.toList());
    }

    private static Pair<File, String> readLines(File file) {
        try {
            return new Pair<>(file, String.join("\n", Files.readAllLines(file.toPath())));
        } catch (IOException e) {
            System.err.println("ERROR: cannot read " + file);
            return new Pair<>(new File(""), "");
        }
    }

    private static boolean fileIsNotEmpty(Pair<File, String> pair) {
        return !(pair.getFirst().getName().isEmpty() && pair.getSecond().isEmpty());
    }

    private static void replaceKeynodes(List<Pair<File, String>> filesWithCodegen) {
        filesWithCodegen.stream()
                .filter(pair -> KEYNODE_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher matcher = KEYNODE_PATTERN.matcher(pair.getSecond());
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()) {
                        String replacement = getKeynodeReplacement(matcher);
                        matcher.appendReplacement(sb, replacement);
                    }
                    matcher.appendTail(sb);
                    return new Pair<>(pair.getFirst(), sb.toString());
                })
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_INCLUDE_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(SC_CLASS_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_BODY_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll("#pragma\\s+once\n", "#pragma once\n\n#include <sc-memory/sc_keynodes.hpp>\n")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(":\\s+public\\s+ScObject", ": public ScKeynodes")))
                .peek(pair -> {
                    String header = pair.getFirst().getAbsolutePath();
                    String source = getSourceFilename(header);
                    if (source != null) {
                        File sourceFile = new File(source);
                        if (sourceFile.exists() && sourceFile.delete()) {
                            System.out.println("deleted keynodes source file " + source);
                        }
                    }

                })
                .peek(pair -> System.out.println("Keynodes file " + pair.getFirst() + " was updated"))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static String getSourceFilename(String header) {
        String source = null;
        if (header.endsWith(".hpp")) {
            source = header.replaceAll(".hpp$", ".cpp");
        } else if (header.endsWith(".h")) {
            source = header.replaceAll(".h$", ".c");
        }
        return source;
    }

    private static String getKeynodeReplacement(Matcher matcher) {
        String keynodeName = matcher.group(KEYNODE_NAME_GROUP);
        String keynodeIdentifier = matcher.group(KEYNODE_IDENTIFIER_GROUP);
        String typeNameBefore = matcher.group(TYPE_NAME_BEFORE_GROUP);
        String typeNameAfter = matcher.group(TYPE_NAME_AFTER_GROUP);

        StringBuilder sb = new StringBuilder();
        sb.append("static inline ScKeynode const ").append(keynodeName).append("{\"").append(keynodeIdentifier).append("\"");

        if (typeNameBefore != null && !typeNameBefore.isEmpty()) {
            sb.append(", ").append(typeNameBefore);
        } else if (typeNameAfter != null && !typeNameAfter.isEmpty()) {
            sb.append(", ").append(typeNameAfter);
        }

        sb.append("};");
        return sb.toString();
    }

    private static void replaceModule(List<Pair<File, String>> filesWithCodegen) {
        replaceModuleCpp(filesWithCodegen);
        replaceModuleHpp(filesWithCodegen);
    }

    private static void replaceModuleCpp(List<Pair<File, String>> filesWithCodegen) {
        filesWithCodegen.stream()
                .filter(pair -> MODULE_NAME_IN_CPP_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher moduleNameMatcher = MODULE_NAME_IN_CPP_PATTERN.matcher(pair.getSecond());
                    if (!moduleNameMatcher.find()) {
                        throw new IllegalArgumentException("Cannot extract module name for " + pair.getFirst());
                    }
                    String moduleName = moduleNameMatcher.group(MODULE_NAME_GROUP);
                    StringBuilder agentsRegistration = new StringBuilder();
                    agentsRegistration.append("SC_MODULE_REGISTER(").append(moduleName).append(")");

                    Matcher matcher = AGENT_REGISTRATION_PATTERN.matcher(pair.getSecond());
                    while (matcher.find()) {
                        String agentName = matcher.group(AGENT_NAME_GROUP);
                        agentsRegistration.append("\n  ->Agent<").append(agentName).append(">()");
                    }
                    agentsRegistration.append(";");
                    return new Pair<>(pair.getFirst(), moduleNameMatcher.replaceFirst(agentsRegistration.toString()).replaceAll(INITIALIZE_BODY_MODULE_REGEX, String.format("%sif needed override ScModule::Initialize and move all non-keynodes and non-agents code from previous initialization method\n/*\n${%s}\n*/\n", TODO_PREFIX, CODE_BLOCK_GROUP)));
                })
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(SHUTDOWN_BODY_MODULE_REGEX + "\\s*", String.format("%sif needed override ScModule::Shutdown and move all non-agents code from previous shutdown method\n/*\n${%s}\n*/\n", TODO_PREFIX, CODE_BLOCK_GROUP))))
                .peek(pair -> System.out.println("Module source file " + pair.getFirst() + " was updated"))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void replaceModuleHpp(List<Pair<File, String>> filesWithCodegen) {
        filesWithCodegen.stream()
                .filter(pair -> MODULE_HPP_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_INCLUDE_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_BODY_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(SC_CLASS_IN_MODULE_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(INITIALIZE_OVERRIDE_MODULE_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(SHUTDOWN_OVERRIDE_MODULE_REGEX + "\\s*", "")))
                .peek(pair -> System.out.println("Module header file " + pair.getFirst() + " was updated"))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void replaceAgent(List<Pair<File, String>> filesWithCodegen) {
        replaceAgentCpp(filesWithCodegen);
        replaceAgentHpp(filesWithCodegen);
    }

    private static void replaceAgentCpp(List<Pair<File, String>> filesWithCodegen) {
        AtomicBoolean todoBlockInserted = new AtomicBoolean(false);
        filesWithCodegen.stream()
                .filter(pair -> AGENT_NAME_IN_CPP_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher agentNameMatcher = Pattern.compile(AGENT_NAME_IN_CPP_REGEX + "\\s*" + CODE_BLOCK_REGEX).matcher(pair.getSecond());
                    StringBuffer fullSb = new StringBuffer();
                    while (agentNameMatcher.find()) {
                        String agentName = agentNameMatcher.group(AGENT_NAME_GROUP);
                        String agentBody = agentNameMatcher.group();
                        agentBody = agentBody.replaceAll("\\botherAddr\\b", "event.GetOtherElement()");
                        agentBody = agentBody.replaceAll("\\bedgeAddr\\b", "event.GetArc()");
                        agentBody = agentBody.replaceAll("return\\s+SC_RESULT_OK\\b", "return action.FinishSuccessfully()");
                        agentBody = agentBody.replaceAll("return\\s+SC_RESULT_ERROR.*?\\b", "return action.FinishUnsuccessfully()");
                        agentBody = agentBody.replaceAll("SC_LOG_(?<logType>\\w*?)\\b\\(", "SC_AGENT_LOG_${logType}(");
                        agentBody = agentBody.replaceAll("SC_AGENT_LOG_(?<logType>\\w*?)\\b\\(\"" + agentName + "\\s*:?\\s*", "SC_AGENT_LOG_${logType}(\"");
                        Matcher returnMatcher = Pattern.compile("return\\s+(?<returnExpression>[^;]*?);\\n").matcher(agentBody);
                        StringBuffer sb = new StringBuffer();
                        while (returnMatcher.find()) {
                            String returnExpression = returnMatcher.group("returnExpression");
                            if (returnExpression != null && !returnExpression.startsWith("action.Finish")) {
                                returnMatcher.appendReplacement(sb, String.format("return (%s == SC_RESULT_OK) ? action.FinishSuccessfully() : action.FinishUnsuccessfully();\n", returnExpression));
                            } else {
                                returnMatcher.appendReplacement(sb, returnMatcher.group());
                            }
                        }
                        returnMatcher.appendTail(sb);
                        sb.append(String.format(AGENT_HPP_OVERRIDDEN_GET_ACTION_CLASS, agentName));
                        agentBody = sb.toString().replaceFirst(AGENT_NAME_IN_CPP_REGEX, "ScResult " + agentName + "::DoProgram(ScActionInitiatedEvent const & event, ScAction & action)");
                        agentNameMatcher.appendReplacement(fullSb, agentBody);
                    }
                    agentNameMatcher.appendTail(fullSb);
                    return new Pair<>(pair.getFirst(), fullSb.toString().replaceAll("m_memoryCtx", "m_context"));
                })
                .peek(pair -> System.out.println("Agent source file " + pair.getFirst() + " was updated"))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void replaceAgentHpp(List<Pair<File, String>> filesWithCodegen) {
        filesWithCodegen.stream()
                .filter(pair -> AGENT_INITIATION_CONDITION_PATTERN.matcher(pair.getSecond()).find())
                .filter(pair -> AGENT_NAME_IN_HPP_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_INCLUDE_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(OUTDATED_INCLUDES_REGEX + "\\s*", "")))
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(GENERATED_BODY_REGEX + "\\s*", "")))
                .map(pair -> {
                    Matcher nameMatcher = AGENT_BODY_IN_HPP_PATTERN.matcher(pair.getSecond());
                    StringBuffer sb = new StringBuffer();
                    while (nameMatcher.find()) {
                        String agentName = nameMatcher.group(AGENT_NAME_GROUP);
                        Matcher initiationConditionMatcher = AGENT_INITIATION_CONDITION_PATTERN.matcher(nameMatcher.group());
                        if (!initiationConditionMatcher.find()) {
                            System.err.println("Error: cannot find agent initiation condition in file "  + pair.getFirst().getAbsolutePath() + " inside " + nameMatcher.group());
                        }
                        String subscriptionElement = initiationConditionMatcher.group(SUBSCRIPTION_COMMAND_GROUP);
                        String eventType = null;
                        boolean isActionInitiatedAgent = subscriptionElement != null;
                        if (!isActionInitiatedAgent) {
                            subscriptionElement = initiationConditionMatcher.group(SUBSCRIPTION_ELEMENT_GROUP);
                            eventType = initiationConditionMatcher.group(EVENT_TYPE_GROUP);
                            if (!OLD_TO_NEW_EVENTS_MAP.containsKey(eventType)) {
                                throw new IllegalArgumentException("Unknown event " + eventType + " in " + pair.getFirst());
                            }
                            eventType = OLD_TO_NEW_EVENTS_MAP.get(eventType);
                            isActionInitiatedAgent = (subscriptionElement.endsWith("action_initiated") || subscriptionElement.endsWith("question_initiated")) && "ScEventAfterGenerateOutgoingArc".equals(eventType);
                        }
                        String newEvent = isActionInitiatedAgent ? "ScActionInitiatedEvent" : (EVENTS_WITHOUT_EDGE.contains(eventType) ? eventType : eventType + "<ScType::EdgeAccessConstPosPerm>");
                        String newParent = isActionInitiatedAgent ? "ScActionInitiatedAgent" : "ScAgent<" + newEvent + ">";

                        if (!isActionInitiatedAgent) {
                            String sourceFile = getSourceFilename(pair.getFirst().getAbsolutePath());
                            if (sourceFile != null) {
                                sourceFile += FILE_POSTFIX;
                                replaceInFile(sourceFile, agentName + "::DoProgram(ScActionInitiatedEvent const & event, ScAction & action)", agentName + "::DoProgram(" + newEvent + " const & event, ScAction & action)");
                                String getActionClassBody = agentName + GET_ACTION_CLASS_BODY;
                                replaceInFile(sourceFile, getActionClassBody, getActionClassBody + "\nScAddr " + agentName + "::GetEventSubscriptionElement() const\n" +
                                        "{\n" +
                                        "  return " + subscriptionElement + ";\n" +
                                        "}");
                            }
                        }
                        nameMatcher.appendReplacement(sb, "class " + agentName + " : public " + newParent + "\n" + nameMatcher.group(CODE_BLOCK_GROUP).replace(initiationConditionMatcher.group(), getAgentHppBody(newEvent, isActionInitiatedAgent)));
                    }
                    nameMatcher.appendTail(sb);
                    return new Pair<>(pair.getFirst(), sb.toString());
                })
                .peek(pair -> System.out.println("Agent header file " + pair.getFirst() + " was updated"))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static String getAgentHppBody(String newEvent, boolean isActionInitiatedAgent) {
        return String.format(NEW_AGENT_HPP_BODY, newEvent) + (isActionInitiatedAgent ? "" : "\n  ScAddr GetEventSubscriptionElement() const override;\n");
    }

    private static void replaceInFile(String filePath, String from, String to) {
        System.out.println("replacing in " + filePath + " from " + from + " to " + to);
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("\nWARNING: file does not exist " + filePath);
        }
        String content = readLines(file).getSecond();
        content = content.replace(from, to);
        writeToFile(filePath, content);
    }

    private static void replaceScPropertyKeynode(List<Pair<File, String>> cppFiles) {
        cppFiles.stream()
            .filter(pair -> KEYNODE_PATTERN.matcher(pair.getSecond()).find())
            .map(pair -> {
                Matcher matcher = KEYNODE_PATTERN.matcher(pair.getSecond());
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String replacement = getKeynodeReplacement(matcher);
                    matcher.appendReplacement(sb, replacement);
                }
                matcher.appendTail(sb);
                return new Pair<>(pair.getFirst(), sb.toString());
            })
            .peek(pair -> System.out.println("Removed old in-place SC_PROPERTY with Keynode in " + pair.getFirst()))
            .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void removeOldStatement(List<Pair<File, String>> cppFiles) {
        cppFiles.stream()
                .filter(pair -> Pattern.compile(OUTDATED_INCLUDES_REGEX).matcher(pair.getSecond()).find())
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(OUTDATED_INCLUDES_REGEX + "\\s*", "")))
                .peek(pair -> System.out.println("Removed outdated includes from " + pair.getFirst()))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void replaceCoreKeynodes(List<Pair<File, String>> cppFiles) {
        cppFiles.stream()
                .filter(pair -> CORE_KEYNODES_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher matcher = CORE_KEYNODES_PATTERN.matcher(pair.getSecond());
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()) {
                        matcher.appendReplacement(sb, "ScKeynodes::");
                    }
                    matcher.appendTail(sb);
                    return new Pair<>(pair.getFirst(), sb.toString());
                })
                .peek(pair -> System.out.println("Replacing coreKeynodes usage in " + pair.getFirst().getAbsolutePath()))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void replaceDeprecatedMethods(List<Pair<File, String>> cppFiles) {
        replaceDeprecatedStrings(cppFiles, DEPRECATED_METHODS);
    }

    private static void replaceDeprecatedTypes(List<Pair<File, String>> cppFiles) {
        replaceDeprecatedStrings(cppFiles, DEPRECATED_TYPES);
    }

    private static void replaceOldEvents(List<Pair<File, String>> cppFiles) {
        replaceDeprecatedStrings(cppFiles, OLD_TO_NEW_EVENTS_PAIRS);
    }

    private static void replaceDeprecatedStrings(List<Pair<File, String>> files, List<Pair<String, String>> deprecatedPairs) {
        files.stream()
                .filter(pair -> deprecatedPairs.stream().anyMatch(methods -> Pattern.compile(String.format("\\b%s\\b", methods.getFirst())).matcher(pair.getSecond()).find()))
                .map(pair -> {
                    String content = pair.getSecond();
                    content = deprecatedPairs.stream().reduce(content, (code, methods) -> code.replaceAll(String.format("\\b%s\\b", methods.getFirst()), methods.getSecond()), (code1, code2) -> code1);
                    return new Pair<>(pair.getFirst(), content);
                })
                .peek(pair -> System.out.println("Replacing deprecations in " + pair.getFirst()))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void removeCmakeCodegen(List<Pair<File, String>> cmakeFiles) {
        cmakeFiles.stream()
                .filter(pair -> CODEGEN_CMAKE_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> new Pair<>(pair.getFirst(), pair.getSecond().replaceAll(CODEGEN_CMAKE_REGEX, "")))
                .peek(pair -> System.out.println("Removed cmake codegen from " + pair.getFirst()))
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void writeToFile(String filename, String content) {
        try (PrintWriter out = new PrintWriter(filename)) {
            out.println(content);
        } catch (FileNotFoundException e) {
            System.err.println("ERROR: cannot write to a file " + filename);
        }
    }

    private static void printMethodsWithChangedSignatureWarning(List<Pair<File, String>> cppFiles) {
        cppFiles.stream()
                .filter(pair -> METHODS_WITH_CHANGED_SIGNATURE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(pair.getSecond()).find()))
                .map(pair -> {
                    String content = pair.getSecond();
                    content = METHODS_WITH_CHANGED_SIGNATURE_PATTERNS.stream().reduce(content, (code, pattern) -> {
                        Matcher matcher = pattern.matcher(code);
                        StringBuffer sb = new StringBuffer();
                        while (matcher.find()) {
                            matcher.appendReplacement(sb, TODO_PREFIX + "method has signature changed\n" + matcher.group());
                        }
                        matcher.appendTail(sb);
                        return sb.toString();
                    }, (code1, code2) -> code1);
                    return new Pair<>(pair.getFirst(), content);
                })
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void printAgentUtilsWarnings(List<Pair<File, String>> cppFilesAfterCodegenRemoval) {
        System.out.println();
        cppFilesAfterCodegenRemoval.stream()
                .filter(pair -> AGENT_UTILS_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher matcher = AGENT_UTILS_PATTERN.matcher(pair.getSecond());
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()) {
                        matcher.appendReplacement(sb, TODO_PREFIX + "replace AgentUtils:: usage\n" + matcher.group());
                    }
                    matcher.appendTail(sb);
                    return new Pair<>(pair.getFirst(), sb.toString());
                })
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }

    private static void printAgentRegistrationWarnings(List<Pair<File, String>> cppFilesAfterCodegenRemoval) {
        System.out.println();
        cppFilesAfterCodegenRemoval.stream()
                .filter(pair -> AGENT_UN_AND_REGISTRATION_PATTERN.matcher(pair.getSecond()).find())
                .map(pair -> {
                    Matcher matcher = AGENT_UN_AND_REGISTRATION_PATTERN.matcher(pair.getSecond());
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()) {
                        String agentName = matcher.group(AGENT_NAME_GROUP);
                        matcher.appendReplacement(sb, String.format("%sUse agentContext.SubscribeAgent<%s> or UnsubscribeAgent; to register and unregister agent\n" + matcher.group(), TODO_PREFIX, agentName));
                    }
                    matcher.appendTail(sb);
                    return new Pair<>(pair.getFirst(), sb.toString());
                })
                .forEach(pair -> writeToFile(pair.getFirst() + FILE_POSTFIX, pair.getSecond()));
    }
}