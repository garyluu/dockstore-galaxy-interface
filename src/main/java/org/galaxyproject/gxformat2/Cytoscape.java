package org.galaxyproject.gxformat2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Cytoscape {
  public static final String MAIN_TS_PREFIX = "toolshed.g2.bx.psu.edu/repos/";
  private static final String START_ID = "UniqueBeginKey";
  private static final String END_ID = "UniqueEndKey";

  public static Map<String, Object> getElements(final String path) throws Exception {
    final Map<String, Object> object = (Map<String, Object>) IoUtils.readYamlFromPath(path);
    return getElements(object);
  }

  public static Map<String, Object> getElements(final Map<String, Object> object) {
    final String wfClass = (String) object.get("class");
    WorkflowAdapter adapter;
    if (wfClass == null) {
      adapter = new NativeWorkflowAdapter(object);
    } else {
      adapter = new Format2WorkflowAdapter(object);
    }
    final Map<String, Object> elements = new HashMap<>();
    final List<Object> nodeElements = new ArrayList<>();
    final List<Object> edgeElements = new ArrayList<>();
    elements.put("nodes", nodeElements);
    elements.put("edges", edgeElements);
    nodeElements.add(createStartNode());
    nodeElements.add(createEndNode());
    List<WorkflowAdapter.NormalizedStep> normalizedSteps = adapter.normalizedSteps();
    Set<String> endNodeIds =
        normalizedSteps.stream()
            .map(normalizedStep -> ((Integer) normalizedStep.stepDefinition.get("id")).toString())
            .collect(Collectors.toSet());
    for (final WorkflowAdapter.NormalizedStep normalizedStep : adapter.normalizedSteps()) {
      final Map<String, Object> step = normalizedStep.stepDefinition;
      String stepId = ((Integer) step.get("id")).toString();
      String stepType = step.get("type") != null ? (String) step.get("type") : "tool";
      List<String> classes = new ArrayList<>(Collections.singletonList("type_" + stepType));
      if (stepType.equals("tool") || stepType.equals("subworkflow")) {
        classes.add("runnable");
      } else {
        classes.add("input");
      }
      String toolId = (String) step.get("tool_id");
      if (toolId != null && toolId.startsWith(MAIN_TS_PREFIX)) {
        toolId = toolId.substring(MAIN_TS_PREFIX.length());
      }
      String label = normalizedStep.label;
      if ((label == null || isOrderIndexLabel(label)) && toolId != null) {
        label = "tool:" + toolId;
      }
      final String repoLink;
      if (step.containsKey("tool_shed_repository")) {
        final Map<String, String> repo = (Map<String, String>) step.get("tool_shed_repository");
        repoLink =
            "https://"
                + repo.get("tool_shed")
                + "/view/"
                + repo.get("owner")
                + "/"
                + repo.get("name")
                + "/"
                + repo.get("changeset_revision");
      } else {
        repoLink = null;
      }
      final Map<String, Object> nodeData = new HashMap<String, Object>();
      nodeData.put("id", stepId);
      nodeData.put("label", label);
      // dockstore displays name, docker, type, tool, and run
      nodeData.put("name", label);
      // TODO: detect Docker image properly
      nodeData.put("docker", "TBD");
      nodeData.put("run", "TBD");

      nodeData.put("tool_id", step.get("tool_id"));
      nodeData.put("doc", normalizedStep.doc);
      nodeData.put("repo_link", repoLink);
      nodeData.put("step_type", stepType);
      final Map<String, Object> nodeElement = new HashMap<String, Object>();
      nodeElement.put("group", "nodes");
      nodeElement.put("data", nodeData);
      nodeElement.put("classes", classes);
      nodeElement.put("position", elementPosition(step));
      nodeElements.add(nodeElement);

      Object inputConnections = normalizedStep.stepDefinition.get("input_connections");
      if (normalizedStep.inputs.isEmpty() && inputConnections.toString().equals("{}")) {
        edgeElements.add(createEdge(START_ID, stepId));
      }
      for (final WorkflowAdapter.Input input : normalizedStep.inputs) {
        final String edgeId = stepId + "__to__" + input.sourceStepLabel;
        final Map<String, Object> edgeData = new HashMap<>();
        edgeData.put("id", edgeId);
        edgeData.put("source", input.sourceStepLabel);
        // Any node that's a source of an edge is not an end node
        endNodeIds.remove(input.sourceStepLabel);
        edgeData.put("target", stepId);
        edgeData.put("input", input.inputName);
        edgeData.put("output", input.sourceOutputName);
        final Map<String, Object> edgeElement = new HashMap<>();
        edgeElement.put("group", "edges");
        edgeElement.put("data", edgeData);
        edgeElements.add(edgeElement);
      }
    }
    // Create edges for end nodes to direct to the real end node
    endNodeIds.forEach(endNodeId -> edgeElements.add(createEdge(endNodeId, END_ID)));
    return elements;
  }

  static boolean isOrderIndexLabel(final String label) {
    try {
      Integer.parseInt(label);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static CytoscapeDAG.Edge createEdge(String source, String target) {
    String id = source + "__to__" + target;
    CytoscapeDAG.Edge edge = new CytoscapeDAG.Edge();
    CytoscapeDAG.Edge.EdgeData edgeData = new CytoscapeDAG.Edge.EdgeData();
    edgeData.setId(id);
    edgeData.setSource(source);
    edgeData.setTarget(target);
    edge.setData(edgeData);
    return edge;
  }

  private static Map<String, Long> elementPosition(final Map<String, Object> step) {
    Map<String, Object> stepPosition = (Map<String, Object>) step.get("position");
    Map<String, Long> elementPosition = new HashMap();
    elementPosition.put("x", getIntegerValue(stepPosition, "left"));
    elementPosition.put("y", getIntegerValue(stepPosition, "top"));
    return elementPosition;
  }

  private static Long getIntegerValue(final Map<String, Object> fromMap, final String key) {
    final Object value = fromMap.get(key);
    if (value instanceof Float || value instanceof Double) {
      return (long) Math.floor((double) value);
    } else if (value instanceof Integer) {
      return ((Integer) value).longValue();
    } else {
      return (long) value;
    }
  }

  private static Object createStartNode() {
    return createStartOrEndNode(START_ID);
  }

  private static Object createEndNode() {
    return createStartOrEndNode(END_ID);
  }

  private static CytoscapeDAG.Node createStartOrEndNode(String id) {
    CytoscapeDAG.Node.NodeData nodeData = new CytoscapeDAG.Node.NodeData();
    CytoscapeDAG.Node node = new CytoscapeDAG.Node();
    nodeData.setId(id);
    nodeData.setName(id);
    node.setData(nodeData);
    return node;
  }
}